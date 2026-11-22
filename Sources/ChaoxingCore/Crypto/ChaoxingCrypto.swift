// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Swift adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// Adapted for Apple platforms; notices updated 2026-11-14.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

import Foundation
import CommonCrypto
import CryptoKit

public enum ChaoxingCrypto {
    public static let transferKey = "u2oh6Vu^HWe4_AES"
    public static let imDesKey = "SL2(M/eD"

    public static func encryptLoginField(_ message: String, key: String = transferKey) throws -> String {
        guard let data = message.data(using: .utf8),
              let keyData = key.data(using: .utf8), keyData.count == kCCKeySizeAES128 else {
            throw ChaoxingError.invalidInput("AES 协议密钥必须为 16 字节")
        }
        let cryptLength = data.count + kCCBlockSizeAES128
        var cryptData = Data(count: cryptLength)
        var numBytesEncrypted: size_t = 0

        let status = cryptData.withUnsafeMutableBytes { cryptBytes in
            data.withUnsafeBytes { dataBytes in
                keyData.withUnsafeBytes { keyBytes in
                    CCCrypt(
                        CCOperation(kCCEncrypt),
                        CCAlgorithm(kCCAlgorithmAES),
                        CCOptions(kCCOptionPKCS7Padding),
                        keyBytes.baseAddress,
                        kCCKeySizeAES128,
                        keyBytes.baseAddress,
                        dataBytes.baseAddress,
                        data.count,
                        cryptBytes.baseAddress,
                        cryptLength,
                        &numBytesEncrypted
                    )
                }
            }
        }
        guard status == kCCSuccess else {
            throw ChaoxingError.parseFailed("AES 加密失败: \(status)")
        }
        cryptData.count = numBytesEncrypted
        return cryptData.base64EncodedString()
    }

    public static func decryptIMPasswordDES(_ hexOrPlain: String, key: String = imDesKey) -> String {
        guard hexOrPlain.count >= 16, let cipherData = Data(hexString: hexOrPlain),
              cipherData.count.isMultiple(of: kCCBlockSizeDES),
              let keyData = key.data(using: .utf8), keyData.count == kCCKeySizeDES else {
            return hexOrPlain
        }
        let outLength = cipherData.count + kCCBlockSizeDES
        var outData = Data(count: outLength)
        var numBytesDecrypted: size_t = 0

        let status = outData.withUnsafeMutableBytes { outBytes in
            cipherData.withUnsafeBytes { cipherBytes in
                keyData.withUnsafeBytes { keyBytes in
                    CCCrypt(
                        CCOperation(kCCDecrypt),
                        CCAlgorithm(kCCAlgorithmDES),
                        CCOptions(kCCOptionPKCS7Padding | kCCOptionECBMode),
                        keyBytes.baseAddress,
                        kCCKeySizeDES,
                        nil,
                        cipherBytes.baseAddress,
                        cipherData.count,
                        outBytes.baseAddress,
                        outLength,
                        &numBytesDecrypted
                    )
                }
            }
        }
        guard status == kCCSuccess else {
            return hexOrPlain
        }
        outData.count = numBytesDecrypted
        return String(data: outData, encoding: .utf8) ?? hexOrPlain
    }

    public static func md5Hex(_ value: String) -> String {
        let digest = Insecure.MD5.hash(data: Data(value.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    public static func randomizedDeviceCode() -> String {
        let seed = UUID().uuidString.replacingOccurrences(of: "-", with: "")
            + UUID().uuidString.replacingOccurrences(of: "-", with: "")
        let digest = Data(SHA256.hash(data: Data(seed.utf8)))
        var doubled = Data()
        doubled.append(digest)
        doubled.append(digest)
        return doubled.base64EncodedString()
    }

    public static func urlFormEncodeBase64(_ base64: String) -> String {
        base64
            .replacingOccurrences(of: "+", with: "%2B")
            .replacingOccurrences(of: "/", with: "%2F")
            .replacingOccurrences(of: "=", with: "%3D")
            .replacingOccurrences(of: " ", with: "%2B")
    }
}

extension Data {
    init?(hexString: String) {
        let len = hexString.count
        guard len % 2 == 0 else { return nil }
        var data = Data(capacity: len / 2)
        var index = hexString.startIndex
        for _ in 0..<(len / 2) {
            let nextIndex = hexString.index(index, offsetBy: 2)
            guard let byte = UInt8(hexString[index..<nextIndex], radix: 16) else { return nil }
            data.append(byte)
            index = nextIndex
        }
        self = data
    }
}
