// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Testing
import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
@testable import ChaoxingCore

@Suite("ChaoxingCore Tests")
struct ChaoxingCoreTests {
    // All accounts, credentials, courses and locations below are synthetic offline fixtures.
    @Test("AES login encryption matches expected Base64 output format")
    func testAESEncryption() throws {
        let encrypted = try ChaoxingCrypto.encryptLoginField("test-account-001")
        #expect(!encrypted.isEmpty)
        #expect(Data(base64Encoded: encrypted) != nil)
    }

    @Test("MD5 hex produces 32 lowercase hex chars")
    func testMD5() {
        let digest = ChaoxingCrypto.md5Hex("hello")
        #expect(digest == "5d41402abc4b2a76b9719d911017c592")
    }

    @Test("Device code generation produces valid Base64 of 64 bytes")
    func testDeviceCode() {
        let code = ChaoxingCrypto.randomizedDeviceCode()
        let decoded = Data(base64Encoded: code)
        #expect(decoded?.count == 64)
    }

    @Test("QRCode parser parses SIGNIN and URL formats")
    func testQRCodeParser() throws {
        let signinQR = "SIGNIN:aid=123456&source=15&Code=abc999&enc=deadbeef1234-extra"
        let r1 = try ChaoxingQRCodeParser.parse(rawValue: signinQR)
        #expect(r1.aid == 123456)
        #expect(r1.code == "abc999")
        #expect(r1.enc == "deadbeef1234")

        let urlQR = "https://mobilelearn.chaoxing.com/widget/sign/e?id=654321&c=xyz123&enc=cafebabe"
        let r2 = try ChaoxingQRCodeParser.parse(rawValue: urlQR)
        #expect(r2.aid == 654321)
        #expect(r2.code == "xyz123")
        #expect(r2.enc == "cafebabe")
    }

    @Test("Course list JSON parser extracts courses properly")
    func testCourseListParser() throws {
        let sampleJSON = """
        {
          "result": 1,
          "channelList": [
            {
              "cataName": "课程",
              "content": {
                "id": 998877,
                "course": {
                  "data": [
                    {
                      "id": 112233,
                      "name": "高等数学",
                      "teacherfactor": "张老师",
                      "imageurl": "http://example.com/cover.jpg",
                      "schools": "示例大学"
                    }
                  ]
                }
              }
            }
          ]
        }
        """
        let courses = try ChaoxingCourseService.parseCourseListJSON(Data(sampleJSON.utf8))
        #expect(courses.count == 1)
        #expect(courses[0].courseId == 112233)
        #expect(courses[0].classId == 998877)
        #expect(courses[0].courseName == "高等数学")
        #expect(courses[0].imageURL == "https://example.com/cover.jpg")
    }

    @Test("Course cpi accepts positive integer IDs in either field without rounding or truncating")
    func testCourseListCpiRepresentations() throws {
        let cases: [(content: String, wrapper: String, expected: Int64?)] = [
            ("301", "302", 301), (#""301""#, "302", 301),
            ("null", #""302""#, 302), ("true", "302", 302),
            ("12.5", "null", nil), (#""12.5""#, "null", nil),
            ("0", "null", nil), ("-7", "null", nil),
            ("9223372036854775808", "null", nil), (#""9223372036854775808""#, "null", nil),
            ("9223372036854775807", "null", .max)
        ]
        for sample in cases {
            let json = """
            {"result":1,"channelList":[{"cataName":"课程","cpi":\(sample.wrapper),
             "content":{"id":2,"cpi":\(sample.content),"course":{"data":[{"id":1}]}}}]}
            """
            let courses = try ChaoxingCourseService.parseCourseListJSON(Data(json.utf8))
            #expect(courses.count == 1)
            #expect(courses.first?.cpi == sample.expected)
        }
    }

    @Test("Easemob protobuf decoder extracts timestamp and attachment JSON")
    func testEasemobProtobufDecoder() {
        // Construct Meta { field4 = 1700000000000; field6 = MessageBody { field5 = KeyValue { key="attachment", stringValue="{\"aid\":1}" } } }
        var kv = Data()
        let key = Data("attachment".utf8)
        kv.append(0x0A) // field 1, wire 2
        kv.append(UInt8(key.count))
        kv.append(key)
        let val = Data("{\"aid\":1}".utf8)
        kv.append(0x32) // field 6, wire 2
        kv.append(UInt8(val.count))
        kv.append(val)

        var msgBody = Data()
        msgBody.append(0x2A) // field 5, wire 2
        msgBody.append(UInt8(kv.count))
        msgBody.append(kv)

        var meta = Data()
        meta.append(0x20) // field 4, wire 0
        // varint 1000
        meta.append(0xE8)
        meta.append(0x07)
        meta.append(0x32) // field 6, wire 2
        meta.append(UInt8(msgBody.count))
        meta.append(msgBody)

        let decoded = EasemobProtobufDecoder.decodeMetaMessage(from: meta)
        #expect(decoded != nil)
        #expect(decoded?.timestampMs == 1000)
        #expect(decoded?.attachmentJSON == "{\"aid\":1}")
    }

    @Test("Persistence store saves and loads snapshot")
    func testPersistenceStore() async throws {
        let tempFile = FileManager.default.temporaryDirectory
            .appendingPathComponent("chaoxing_test_\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: tempFile) }

        let store = ChaoxingPersistenceStore(customFileURL: tempFile)
        var snapshot = ChaoxingAppStateSnapshot()
        snapshot.locationPresets.append(
            ChaoxingLocationPreset(name: "教学楼", address: "A101", latitude: 31.23, longitude: 121.47)
        )
        try await store.save(snapshot)

        let loaded = try await store.load()
        #expect(loaded.locationPresets.count == 1)
        #expect(loaded.locationPresets[0].name == "教学楼")
        #expect(loaded.enableLocationJitter == true)
        #expect(loaded.batchSignDelayMs == 1200)
    }

    @Test("Image obfuscator produces distinct valid JPEGs")
    func testImageObfuscatorProducesDistinctValidJPEGs() {
        let width = 64
        let height = 64
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
        let ctx = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: colorSpace,
            bitmapInfo: bitmapInfo
        )!
        ctx.setFillColor(red: 0.2, green: 0.5, blue: 0.8, alpha: 1.0)
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let cgImg = ctx.makeImage()!

        let mutable = NSMutableData()
        let dest = CGImageDestinationCreateWithData(mutable, UTType.jpeg.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(dest, cgImg, nil)
        CGImageDestinationFinalize(dest)
        let originalData = mutable as Data

        let out1 = ChaoxingImageObfuscator.obfuscateJPEG(originalData)
        let out2 = ChaoxingImageObfuscator.obfuscateJPEG(originalData)

        #expect(out1 != out2)
        #expect(out1 != originalData)
        #expect(CGImageSourceCreateWithData(out1 as CFData, nil) != nil)
        #expect(CGImageSourceCreateWithData(out2 as CFData, nil) != nil)
    }

    @Test("Snapshot backup export and import respects includeAccounts flag")
    func testSnapshotBackupExportImport() throws {
        let account = ChaoxingAccountSession(
            phoneNumber: "test-account-001",
            encryptedPassword: "enc",
            name: "测试",
            puid: 123,
            deviceCode: "dev"
        )
        let history = ChaoxingSignHistoryEntry(
            activeId: 999,
            activityName: "随堂签到",
            courseName: "高等数学",
            signTypeRaw: "0",
            successCount: 1,
            totalCount: 1,
            summary: "签到成功"
        )
        let snapshot = ChaoxingAppStateSnapshot(
            accounts: [account],
            locationPresets: [ChaoxingLocationPreset(name: "图书馆", address: "南门", latitude: 30.0, longitude: 120.0)],
            batchSignDelayMs: 2500,
            signHistory: [history]
        )

        let publicBackup = try snapshot.encodedBackupData(includeAccounts: false)
        let decodedPublic = try ChaoxingAppStateSnapshot.decodedBackupData(publicBackup)
        #expect(decodedPublic.accounts.isEmpty)
        #expect(decodedPublic.locationPresets.count == 1)
        #expect(decodedPublic.batchSignDelayMs == 2500)
        #expect(decodedPublic.signHistory.count == 1)

        let fullBackup = try snapshot.encodedBackupData(includeAccounts: true)
        let decodedFull = try ChaoxingAppStateSnapshot.decodedBackupData(fullBackup)
        #expect(decodedFull.accounts.count == 1)
    }

    @Test("Group sign parser supports legacy and URL-embedded sign payloads")
    func testGroupSignParserExtended() {
        let json = """
        {
          "attachmentType": 15,
          "att_chat_course": {
            "atype": 0,
            "atypeName": "位置签到",
            "title": "请完成位置打卡",
            "url": "https://mobilelearn.chaoxing.com/newsign/preSign?activePrimaryId=887766&courseId=12345&classId=678"
          }
        }
        """
        let act = ChaoxingGroupService.parseGroupSignActivity(
            attachmentJSON: json,
            timestampMs: 1700000000000,
            fallbackGroupName: "高数群"
        )
        #expect(act != nil)
        #expect(act?.activeId == 887766)
        #expect(act?.courseId == 12345)
        #expect(act?.classId == 678)
        #expect(act?.isUnsupportedLegacySign == false)
    }

    @Test("Account tags and course location bindings persist properly")
    func testAccountTagsAndCourseBindings() throws {
        let account = ChaoxingAccountSession(
            phoneNumber: "test-account-002",
            encryptedPassword: "enc",
            name: "测试用户B",
            deviceCode: "dev2",
            tags: ["示例分组A", "示例分组B"]
        )
        let binding = ChaoxingLocationPreset(name: "一教302", address: "一教", latitude: 34.12, longitude: 108.83)
        let snapshot = ChaoxingAppStateSnapshot(
            accounts: [account],
            courseLocationBindings: ["12345": binding],
            starredCourseIds: [12345]
        )
        let data = try snapshot.encodedBackupData(includeAccounts: true)
        let decoded = try ChaoxingAppStateSnapshot.decodedBackupData(data)
        #expect(decoded.accounts.first?.tags == ["示例分组A", "示例分组B"])
        #expect(decoded.courseLocationBindings["12345"]?.name == "一教302")
        #expect(decoded.starredCourseIds == [12345])
    }

    @Test("Primary text remains high contrast in both appearances")
    func testPrimaryPaletteContrast() {
        // The interface now uses a neutral/blue palette; readability is independent of hue.
        #expect(CxPaletteSpecs.inkLight.contrastRatio(with: CxPaletteSpecs.canvasLight) >= 12.0)
        #expect(CxPaletteSpecs.inkDark.contrastRatio(with: CxPaletteSpecs.canvasDark) >= 12.0)
        #expect(CxPaletteSpecs.inkSecondaryLight.contrastRatio(with: CxPaletteSpecs.surfaceLight) >= 4.5)
        #expect(CxPaletteSpecs.inkSecondaryDark.contrastRatio(with: CxPaletteSpecs.surfaceDark) >= 4.5)
    }
}
