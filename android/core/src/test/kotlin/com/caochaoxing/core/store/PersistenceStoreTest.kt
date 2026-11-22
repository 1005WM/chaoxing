// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.store

import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingClientPreset
import com.caochaoxing.core.model.ChaoxingCustomClientProfile
import com.caochaoxing.core.model.ChaoxingLocationPreset
import com.caochaoxing.core.model.ChaoxingSchool
import com.caochaoxing.core.model.ChaoxingStoredCookie
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Instant

class PersistenceStoreTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `missing state file loads empty defaults without creating file`() = runTest {
        val missing = File(tempDir, "nested/first-run/state.json")
        val store = FilePersistenceStore(missing)

        val loaded = store.load()
        assertEquals(ChaoxingAppStateSnapshot(), loaded)
        assertFalse(missing.exists())
        assertEquals(ChaoxingClientPreset.DEFAULT.rawValue, loaded.selectedClientPreset)
        assertEquals(1200, loaded.batchSignDelayMs)
        assertTrue(loaded.enableLocationJitter)
        assertTrue(loaded.enableImageObfuscation)
        assertTrue(loaded.autoSolveCaptcha)
    }

    @Test
    fun `roundtrips snapshot and preserves swift json schema`() = runTest {
        val file = File(tempDir, "nested/state.json")
        val store = FilePersistenceStore(file)
        val profile = ChaoxingCustomClientProfile(
            id = "660e8400-e29b-41d4-a716-446655440001",
            name = "校园定制客户端",
            userAgent = "CustomAgent/1.0",
        )
        val historyEntry = ChaoxingSignHistoryEntry(
            id = "770e8400-e29b-41d4-a716-446655440002",
            timestamp = "2026-11-16T12:00:00Z",
            activeId = 998877L,
            activityName = "随堂定位签到",
            courseName = "高等数学",
            signTypeRaw = "4",
            successCount = 1,
            alreadySignedCount = 1,
            totalCount = 2,
            summary = "签到成功",
        )
        val snap = ChaoxingAppStateSnapshot(
            accounts = listOf(
                ChaoxingAccountSession(
                    phoneNumber = "13800000001",
                    encryptedPassword = "enc",
                    name = "测试用户",
                    uid = 100,
                    puid = 200,
                    configuredFid = 300,
                    schools = listOf(ChaoxingSchool(fid = 300, name = "示例大学")),
                    avatarURL = "https://example.invalid/avatar.jpg",
                    imEncryptedPassword = "imEnc",
                    clientId = "cid-1",
                    deviceCode = "dev",
                    cookies = listOf(
                        ChaoxingStoredCookie(
                            name = "UID",
                            value = "200",
                            domain = "chaoxing.com",
                            expiresAt = "2026-11-16T12:00:00Z",
                            hostOnly = false,
                        )
                    ),
                    isPrimary = true,
                    lastLoginAt = "2026-11-16T12:00:00Z",
                    tags = listOf("一班"),
                )
            ),
            locationPresets = listOf(
                ChaoxingLocationPreset(
                    id = "550e8400-e29b-41d4-a716-446655440000",
                    name = "图书馆",
                    address = "主馆",
                    latitude = 39.9,
                    longitude = 116.4,
                )
            ),
            selectedClientPreset = ChaoxingClientPreset.XUE_ZAI_XI_DIAN.rawValue,
            customUserAgent = "CustomUA/2.0",
            customClientProfiles = listOf(profile),
            selectedCustomClientId = profile.id,
            enableLocationJitter = false,
            batchSignDelayMs = 2500,
            enableImageObfuscation = false,
            autoSolveCaptcha = false,
            signHistory = listOf(historyEntry),
            courseLocationBindings = mapOf(
                "12345" to ChaoxingLocationPreset(
                    id = "880e8400-e29b-41d4-a716-446655440003",
                    name = "一教",
                    address = "A101",
                    latitude = 39.91,
                    longitude = 116.41,
                )
            ),
            starredCourseIds = listOf(12345L),
        )

        store.save(snap)
        val loaded = store.load()
        assertEquals(snap, loaded)
        assertEquals("13800000001", loaded.accounts.first().phoneNumber)
        assertEquals(listOf("一班"), loaded.accounts.first().tags)
        assertEquals(listOf(12345L), loaded.starredCourseIds)
        assertEquals("一教", loaded.courseLocationBindings["12345"]?.name)
        assertEquals(Instant.parse("2026-11-16T12:00:00Z"), loaded.signHistory.first().timestampInstant())

        val rawJsonObject = ChaoxingSnapshotJson.parseToJsonElement(file.readText()).jsonObject
        val expectedSnapshotKeys = setOf(
            "accounts",
            "locationPresets",
            "selectedClientPreset",
            "customUserAgent",
            "customClientProfiles",
            "selectedCustomClientId",
            "enableLocationJitter",
            "batchSignDelayMs",
            "enableImageObfuscation",
            "autoSolveCaptcha",
            "signHistory",
            "courseLocationBindings",
            "starredCourseIds",
        )
        assertEquals(expectedSnapshotKeys, rawJsonObject.keys)

        val historyObject = rawJsonObject["signHistory"]!!.jsonArray.first().jsonObject
        val expectedHistoryKeys = setOf(
            "id",
            "timestamp",
            "activeId",
            "activityName",
            "courseName",
            "signTypeRaw",
            "successCount",
            "alreadySignedCount",
            "totalCount",
            "summary",
        )
        assertEquals(expectedHistoryKeys, historyObject.keys)
    }

    @Test
    fun `save refuses to overwrite corrupt state file and archiveAndReset preserves original bytes`() = runTest {
        val file = File(tempDir, "state.json")
        file.writeText("{corrupt-json")
        val store = FilePersistenceStore(file)

        assertThrowsSuspend<Exception> { store.load() }
        assertEquals("{corrupt-json", file.readText())

        assertThrowsSuspend<Exception> { store.save(ChaoxingAppStateSnapshot()) }
        assertEquals("{corrupt-json", file.readText())

        val archived = store.archiveAndReset()
        assertTrue(archived.exists())
        assertTrue(archived.name.startsWith("state.recovery-"))
        assertTrue(archived.name.endsWith(".json"))
        assertEquals(file.parentFile, archived.parentFile)
        assertEquals("{corrupt-json", archived.readText())
        assertEquals(emptyList<ChaoxingAccountSession>(), store.load().accounts)

        val newSnapshot = ChaoxingAppStateSnapshot(starredCourseIds = listOf(42L))
        store.save(newSnapshot)
        assertEquals(listOf(42L), store.load().starredCourseIds)
    }

    @Test
    fun `empty file and invalid utf8 bytes are rejected without destruction`() = runTest {
        val emptyFile = File(tempDir, "empty-state.json")
        emptyFile.writeBytes(ByteArray(0))
        val emptyStore = FilePersistenceStore(emptyFile)
        assertThrowsSuspend<Exception> { emptyStore.load() }
        assertThrowsSuspend<Exception> { emptyStore.save(ChaoxingAppStateSnapshot()) }
        assertEquals(0, emptyFile.readBytes().size)

        val invalidUtf8Bytes = byteArrayOf(
            '{'.code.toByte(),
            '"'.code.toByte(),
            'a'.code.toByte(),
            '"'.code.toByte(),
            ':'.code.toByte(),
            0xFF.toByte(),
            0xFE.toByte(),
            '}'.code.toByte(),
        )
        val binaryFile = File(tempDir, "binary-state.json")
        binaryFile.writeBytes(invalidUtf8Bytes)
        val binaryStore = FilePersistenceStore(binaryFile)
        assertThrowsSuspend<Exception> { binaryStore.load() }
        assertThrowsSuspend<Exception> { binaryStore.save(ChaoxingAppStateSnapshot()) }
        assertArrayEquals(invalidUtf8Bytes, binaryFile.readBytes())

        val archived = binaryStore.archiveAndReset()
        assertArrayEquals(invalidUtf8Bytes, archived.readBytes())
        assertEquals(ChaoxingAppStateSnapshot(), binaryStore.load())
    }

    @Test
    fun `directory at stateFile path is never treated as first run or overwritten`() = runTest {
        val dir = File(tempDir, "state-directory").apply { mkdirs() }
        val store = FilePersistenceStore(dir)

        assertThrowsSuspend<IOException> { store.load() }
        assertThrowsSuspend<IOException> { store.save(ChaoxingAppStateSnapshot()) }
        assertThrowsSuspend<IOException> { store.archiveAndReset() }
        assertTrue(dir.exists() && dir.isDirectory)
    }

    @Test
    fun `encodedBackupJson scrubs accounts when includeAccounts is false while preserving presets bindings and stars`() {
        val preset = ChaoxingLocationPreset(
            id = "550e8400-e29b-41d4-a716-446655440010",
            name = "体育馆",
            address = "东门",
            latitude = 34.12,
            longitude = 108.83,
        )
        val binding = ChaoxingLocationPreset(
            id = "550e8400-e29b-41d4-a716-446655440011",
            name = "一教302",
            address = "一教",
            latitude = 34.13,
            longitude = 108.84,
        )
        val snapshot = ChaoxingAppStateSnapshot(
            accounts = listOf(
                ChaoxingAccountSession(
                    phoneNumber = "13800000001",
                    encryptedPassword = "secret-password",
                    name = "主力号",
                    deviceCode = "dev-1",
                    tags = listOf("主力"),
                )
            ),
            locationPresets = listOf(preset),
            selectedClientPreset = ChaoxingClientPreset.XUE_ZAI_XI_DIAN.rawValue,
            customUserAgent = "BackupUA/1.0",
            batchSignDelayMs = 2500,
            signHistory = listOf(
                ChaoxingSignHistoryEntry(
                    activeId = 777L,
                    activityName = "点名",
                    courseName = "线性代数",
                    signTypeRaw = "0",
                    successCount = 1,
                    alreadySignedCount = 0,
                    totalCount = 2,
                    summary = "主力号 13800000001 的详细结果",
                )
            ),
            courseLocationBindings = mapOf("12345" to binding),
            starredCourseIds = listOf(12345L, 67890L),
        )

        val scrubbedJson = snapshot.encodedBackupJson(includeAccounts = false)
        assertFalse(scrubbedJson.contains("secret-password"))
        assertFalse(scrubbedJson.contains("13800000001"))
        assertFalse(scrubbedJson.contains("主力号"))
        val decodedScrubbed = ChaoxingAppStateSnapshot.decodedBackupJson(scrubbedJson)
        assertTrue(decodedScrubbed.accounts.isEmpty())
        assertEquals(listOf(preset), decodedScrubbed.locationPresets)
        assertEquals(mapOf("12345" to binding), decodedScrubbed.courseLocationBindings)
        assertEquals(listOf(12345L, 67890L), decodedScrubbed.starredCourseIds)
        assertEquals(2500, decodedScrubbed.batchSignDelayMs)
        assertEquals(ChaoxingClientPreset.XUE_ZAI_XI_DIAN.rawValue, decodedScrubbed.selectedClientPreset)
        assertEquals("BackupUA/1.0", decodedScrubbed.customUserAgent)
        assertEquals(snapshot.signHistory.map { it.copy(summary = "") }, decodedScrubbed.signHistory)

        val fullData = snapshot.encodedBackupData(includeAccounts = true)
        val decodedFull = ChaoxingAppStateSnapshot.decodedBackupData(fullData)
        assertEquals(snapshot, decodedFull)
    }

    @Test
    fun `decodes authentic swift-exported json fixture with legacy cookies and explicit nulls`() = runTest {
        val swiftExportedJson = """
            {
              "accounts": [
                {
                  "avatarURL": "https://photo.chaoxing.com/p/100_80",
                  "clientId": null,
                  "configuredFid": 1001,
                  "cookies": [
                    {
                      "domain": "passport2.chaoxing.com",
                      "expiresAt": "2026-11-16T12:00:00Z",
                      "name": "fid",
                      "path": "/",
                      "value": "1001"
                    },
                    {
                      "domain": "chaoxing.com",
                      "expiresAt": "2026-11-16T12:00:00Z",
                      "name": "UID",
                      "value": "987654"
                    },
                    {
                      "domain": ".chaoxing.com",
                      "expiresAt": null,
                      "isSecure": true,
                      "name": "_uid",
                      "path": "/newsign",
                      "value": "987654"
                    }
                  ],
                  "deviceCode": "swift-device-code-01",
                  "encryptedPassword": "swift-encrypted-pwd",
                  "imEncryptedPassword": "swift-im-pwd",
                  "isPrimary": true,
                  "lastLoginAt": "2026-11-16T12:00:00Z",
                  "name": "李华",
                  "phoneNumber": "13900001234",
                  "puid": 987654,
                  "schools": [
                    {
                      "fid": 1001,
                      "name": "示例大学"
                    }
                  ],
                  "tags": null,
                  "uid": 987654
                }
              ],
              "autoSolveCaptcha": false,
              "batchSignDelayMs": 1800,
              "courseLocationBindings": {
                "556677": {
                  "address": "主楼东区 102",
                  "id": "550E8400-E29B-41D4-A716-446655440000",
                  "latitude": 34.1234,
                  "longitude": 108.5678,
                  "name": "主楼102"
                }
              },
              "customClientProfiles": [
                {
                  "id": "660E8400-E29B-41D4-A716-446655440001",
                  "name": "定制终端",
                  "userAgent": "SwiftExportedUA/6.7"
                }
              ],
              "customUserAgent": null,
              "enableImageObfuscation": true,
              "enableLocationJitter": false,
              "legacyUnknownSwiftField": "should-be-ignored",
              "locationPresets": [
                {
                  "address": "南门图书馆",
                  "id": "770E8400-E29B-41D4-A716-446655440002",
                  "latitude": 34.2234,
                  "longitude": 108.6678,
                  "name": "图书馆"
                }
              ],
              "selectedClientPreset": "@@xuezaixidian",
              "selectedCustomClientId": null,
              "signHistory": [
                {
                  "activeId": 445566,
                  "activityName": "课前打卡",
                  "courseName": "计算机网络",
                  "id": "880E8400-E29B-41D4-A716-446655440003",
                  "signTypeRaw": "4",
                  "successCount": 1,
                  "summary": "全部成功",
                  "timestamp": "2026-11-16T12:30:45Z",
                  "totalCount": 1
                }
              ],
              "starredCourseIds": [
                556677,
                889900
              ]
            }
        """.trimIndent()

        val decoded = ChaoxingAppStateSnapshot.decodedBackupJson(swiftExportedJson)
        assertEquals(1, decoded.accounts.size)
        val account = decoded.accounts.single()
        assertEquals("13900001234", account.phoneNumber)
        assertEquals("李华", account.name)
        assertNull(account.clientId)
        assertEquals(emptyList<String>(), account.tags)
        assertEquals("2026-11-16T12:00:00Z", account.lastLoginAt)
        assertEquals(listOf(ChaoxingSchool(fid = 1001, name = "示例大学")), account.schools)

        assertEquals(3, account.cookies.size)
        val hostCookie = account.cookies[0]
        assertTrue(hostCookie.hostOnly)
        assertFalse(hostCookie.isSecure)
        assertEquals("/", hostCookie.path)
        assertEquals(Instant.parse("2026-11-16T12:00:00Z"), hostCookie.expiresAtInstant())

        val rootCookie = account.cookies[1]
        assertFalse(rootCookie.hostOnly)
        assertEquals("/", rootCookie.path)
        assertEquals(Instant.parse("2026-11-16T12:00:00Z"), rootCookie.expiresAtInstant())

        val dotCookie = account.cookies[2]
        assertFalse(dotCookie.hostOnly)
        assertTrue(dotCookie.isSecure)
        assertEquals("/newsign", dotCookie.path)
        assertNull(dotCookie.expiresAt)

        assertFalse(decoded.autoSolveCaptcha)
        assertEquals(1800, decoded.batchSignDelayMs)
        assertEquals("", decoded.customUserAgent)
        assertFalse(decoded.enableLocationJitter)
        assertTrue(decoded.enableImageObfuscation)
        assertEquals(ChaoxingClientPreset.XUE_ZAI_XI_DIAN.rawValue, decoded.selectedClientPreset)
        assertNull(decoded.selectedCustomClientId)
        assertEquals("主楼102", decoded.courseLocationBindings["556677"]?.name)
        assertEquals("图书馆", decoded.locationPresets.single().name)
        assertEquals("定制终端", decoded.customClientProfiles.single().name)
        assertEquals(listOf(556677L, 889900L), decoded.starredCourseIds)
        assertEquals(1, decoded.signHistory.size)
        assertNull(decoded.signHistory.single().alreadySignedCount)
        assertEquals(1, decoded.signHistory.single().successCount)
        assertEquals(Instant.parse("2026-11-16T12:30:45Z"), decoded.signHistory.single().timestampInstant())

        val file = File(tempDir, "swift-state.json")
        file.writeText(swiftExportedJson)
        val store = FilePersistenceStore(file)
        assertEquals(decoded, store.load())

        store.save(decoded)
        assertEquals(decoded, store.load())
    }

    @Test
    fun `concurrent saves and loads serialize cleanly without leaving temp files`() = runTest {
        val file = File(tempDir, "concurrent-state.json")
        val store = FilePersistenceStore(file)

        val tasks = (1..20).map { index ->
            async(Dispatchers.Default) {
                if (index % 2 == 0) {
                    store.save(ChaoxingAppStateSnapshot(starredCourseIds = listOf(index.toLong())))
                } else {
                    store.load()
                }
            }
        }
        tasks.awaitAll()

        val loaded = store.load()
        assertEquals(1, loaded.starredCourseIds.size)
        val tempFiles = tempDir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertTrue(tempFiles.isEmpty())
    }

    private suspend inline fun <reified T : Throwable> assertThrowsSuspend(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e is T) {
                return e
            }
            throw AssertionError("Expected ${T::class.java.name} but threw ${e::class.java.name}: ${e.message}", e)
        }
        throw AssertionError("Expected ${T::class.java.name} to be thrown, but completed normally")
    }
}
