// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

class ChaoxingModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `stored cookie infers hostOnly matching swift compatibility rule`() {
        val exactHost = json.decodeFromString<ChaoxingStoredCookie>(
            """{"name":"k","value":"v","domain":"passport2.chaoxing.com"}"""
        )
        val dotDomain = json.decodeFromString<ChaoxingStoredCookie>(
            """{"name":"k","value":"v","domain":".chaoxing.com"}"""
        )
        val rootFid = json.decodeFromString<ChaoxingStoredCookie>(
            """{"name":"fid","value":"123","domain":"chaoxing.com"}"""
        )
        val upperRoot = json.decodeFromString<ChaoxingStoredCookie>(
            """{"name":"fid","value":"123","domain":"CHAOXING.COM"}"""
        )
        assertTrue(exactHost.hostOnly)
        assertEquals("/", exactHost.path)
        assertFalse(exactHost.isSecure)
        assertFalse(dotDomain.hostOnly)
        assertFalse(rootFid.hostOnly)
        assertFalse(upperRoot.hostOnly)
    }

    @Test
    fun `stored cookie honors explicit hostOnly and null defaults`() {
        val explicitTrue = json.decodeFromString<ChaoxingStoredCookie>(
            """{"name":"fid","value":"123","domain":"chaoxing.com","hostOnly":true,"path":null,"isSecure":null,"expiresAt":null}"""
        )
        assertTrue(explicitTrue.hostOnly)
        assertEquals("/", explicitTrue.path)
        assertFalse(explicitTrue.isSecure)
        assertNull(explicitTrue.expiresAt)

        val explicitFalse = json.decodeFromString<ChaoxingStoredCookie>(
            """{"name":"sid","value":"abc","domain":"passport2.chaoxing.com","hostOnly":false,"expiresAt":"2026-11-16T14:23:01Z"}"""
        )
        assertFalse(explicitFalse.hostOnly)
        assertEquals(Instant.parse("2026-11-16T14:23:01Z"), explicitFalse.expiresAtInstant())
    }

    @Test
    fun `account session roundtrips and uses whole-second UTC timestamps`() {
        val ts = ChaoxingModelDefaults.nowIso8601()
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$""").matches(ts))

        val session = ChaoxingAccountSession(
            phoneNumber = "13800000001",
            encryptedPassword = "enc",
            name = "测试用户",
            uid = 100,
            puid = 200,
            configuredFid = 300,
            schools = listOf(ChaoxingSchool(fid = 300, name = "示例大学")),
            avatarURL = "https://example.invalid/avatar.jpg",
            imEncryptedPassword = "imEnc",
            clientId = "client-1",
            deviceCode = "device-1",
            cookies = listOf(
                ChaoxingStoredCookie(
                    name = "UID",
                    value = "200",
                    domain = "chaoxing.com",
                    expiresAt = ts,
                    hostOnly = false,
                )
            ),
            isPrimary = true,
            lastLoginAt = ts,
            tags = listOf("一班", "主力"),
        )
        assertEquals("13800000001", session.id)
        assertEquals(300, session.schools.first().id)

        val encoded = json.encodeToString(session)
        val decoded = json.decodeFromString<ChaoxingAccountSession>(encoded)
        assertEquals(session, decoded)

        val legacyMinimal = json.decodeFromString<ChaoxingAccountSession>(
            """{"phoneNumber":"13800000002","encryptedPassword":"enc2","deviceCode":"dev2"}"""
        )
        assertEquals("13800000002", legacyMinimal.phoneNumber)
        assertEquals(0, legacyMinimal.configuredFid)
        assertEquals(emptyList<String>(), legacyMinimal.tags)
        assertEquals(emptyList<ChaoxingStoredCookie>(), legacyMinimal.cookies)
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$""").matches(legacyMinimal.lastLoginAt))
    }

    @Test
    fun `client presets match upstream user agents and raw values`() {
        assertEquals("@@default", ChaoxingClientPreset.DEFAULT.rawValue)
        assertEquals("官方通用版", ChaoxingClientPreset.DEFAULT.displayName)
        assertEquals(
            "Dalvik/2.1.0 (Linux; U; Android 12; SM-N9006 Build/8aba9e4.0) (schild:2d97f7b9439f21333c946878fc4d6ccb) (device:SM-N9006) Language/zh_CN com.chaoxing.mobile/ChaoXingStudy_3_6.7.5_android_phone_10941_314 (@Kalimdor)_68f184fd763546c1a04ab3a09b3deebb",
            ChaoxingClientPreset.DEFAULT.userAgent,
        )
        assertEquals("@@xuezaixidian", ChaoxingClientPreset.XUE_ZAI_XI_DIAN.rawValue)
        assertEquals("学在西电定制版", ChaoxingClientPreset.XUE_ZAI_XI_DIAN.displayName)
        assertEquals(
            "Mozilla/5.0 (Linux; Android 16; 23113RKC6C Build/BP2A.250605.031.A3; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/147.0.7727.137 Mobile Safari/537.36 (schild:be536573b69ec1ae359e359d11f7f3e3) (device:23113RKC6C) Language/zh_CN com.chaoxing.mobile.xuezaixidian/ChaoXingStudy_1000149_6.3.7_android_phone_6005_249 (@Kalimdor)_f8777230ca1e45b2831ec7e36a9da1ea",
            ChaoxingClientPreset.XUE_ZAI_XI_DIAN.userAgent,
        )
        assertEquals(ChaoxingClientPreset.XUE_ZAI_XI_DIAN, ChaoxingClientPreset.fromRawValue("@@xuezaixidian"))
        assertNull(ChaoxingClientPreset.fromRawValue("unknown"))
        assertEquals(""""@@default"""", json.encodeToString(ChaoxingClientPreset.DEFAULT))

        val profile = ChaoxingCustomClientProfile(name = "Custom", userAgent = "UA")
        assertEquals(profile.id, UUID.fromString(profile.id).toString())
    }

    @Test
    fun `sign type parses both otherId and chinese atypeName`() {
        assertEquals(ChaoxingSignType.PHOTO_OR_CLICK, ChaoxingSignType.fromOtherId("0"))
        assertEquals(ChaoxingSignType.QR_CODE, ChaoxingSignType.fromOtherId("2"))
        assertEquals(ChaoxingSignType.GESTURE, ChaoxingSignType.fromOtherId("3"))
        assertEquals(ChaoxingSignType.LOCATION, ChaoxingSignType.fromOtherId("4"))
        assertEquals(ChaoxingSignType.PASSWORD, ChaoxingSignType.fromOtherId("5"))
        assertEquals(ChaoxingSignType.UNKNOWN, ChaoxingSignType.fromOtherId(null))
        assertEquals(ChaoxingSignType.UNKNOWN, ChaoxingSignType.fromOtherId("999"))

        assertEquals(ChaoxingSignType.PHOTO_OR_CLICK, ChaoxingSignType.fromAtypeName("拍照签到"))
        assertEquals(ChaoxingSignType.PHOTO_OR_CLICK, ChaoxingSignType.fromAtypeName("普通签到"))
        assertEquals(ChaoxingSignType.PHOTO_OR_CLICK, ChaoxingSignType.fromAtypeName("签到"))
        assertEquals(ChaoxingSignType.QR_CODE, ChaoxingSignType.fromAtypeName("二维码签到"))
        assertEquals(ChaoxingSignType.GESTURE, ChaoxingSignType.fromAtypeName("手势签到"))
        assertEquals(ChaoxingSignType.LOCATION, ChaoxingSignType.fromAtypeName("位置签到"))
        assertEquals(ChaoxingSignType.PASSWORD, ChaoxingSignType.fromAtypeName("密码签到"))
        assertEquals(ChaoxingSignType.PASSWORD, ChaoxingSignType.fromAtypeName("签到码签到"))
        assertEquals(ChaoxingSignType.UNKNOWN, ChaoxingSignType.fromAtypeName("未知"))
        assertEquals(""""4"""", json.encodeToString(ChaoxingSignType.LOCATION))
    }

    @Test
    fun `course activity and group models expose expected computed helpers`() {
        val course = ChaoxingCourse(
            courseName = "高等数学",
            teacherName = "张老师",
            courseId = 112233L,
            classId = 998877,
            imageURL = "https://example.invalid/cover.jpg",
        )
        assertEquals("112233_998877", course.id)

        val activity = ChaoxingSignActivity(
            id = 556677L,
            startTimeMs = 1_700_000_000_000L,
            endTimeMs = 1_700_000_300_000L,
            userStatus = 2,
            otherId = "4",
            isLook = false,
            type = 2,
            activeType = 2,
            nameOne = "随堂定位签到",
            status = 1,
            nameFour = "",
            courseId = 112233L,
            classId = 998877,
            courseName = "高等数学",
            extContent = "{}",
        )
        assertEquals(ChaoxingSignType.LOCATION, activity.signType)
        assertTrue(activity.isAvailable)
        assertTrue(activity.isAlreadySignedByStatus)
        assertFalse(activity.isLateAt(1_700_000_200_000L))
        assertTrue(activity.isLateAt(1_700_000_400_000L))
        assertFalse(activity.copy(endTimeMs = null).isLateAt(1_700_000_400_000L))

        val groupActivity = ChaoxingGroupSignActivity(
            activeId = 887766L,
            title = "位置打卡",
            classId = 678,
            courseId = 12345L,
            courseName = "高数群",
            startTimeTitle = "刚刚",
            activeTypeName = "位置签到",
            isUnsupportedLegacySign = false,
            messageTimestampMs = 1_700_000_000_000L,
        )
        assertEquals(887766L, groupActivity.id)
        assertEquals(ChaoxingSignType.LOCATION, groupActivity.signType)
    }

    @Test
    fun `location sign info offsets within range and preset preserves uuid`() {
        repeat(20) {
            val randomized = ChaoxingLocationSignInfo(
                latitude = 39.9042,
                longitude = 116.4074,
                address = "主楼",
                enableRandomOffset = true,
            )
            assertTrue(abs(randomized.randomizedLatitude - 39.9042) <= 0.000045)
            assertTrue(abs(randomized.randomizedLongitude - 116.4074) <= 0.000045)

            val disabled = randomized.disableRandomOffset()
            assertFalse(disabled.enableRandomOffset)
            assertEquals(39.9042, disabled.randomizedLatitude)
            assertEquals(116.4074, disabled.randomizedLongitude)
        }

        val preset = ChaoxingLocationPreset(
            name = "图书馆",
            address = "主馆",
            latitude = 34.1234,
            longitude = 108.5678,
        )
        assertEquals(preset.id, UUID.fromString(preset.id).toString())
        val exactSignInfo = preset.toSignInfo(enableRandomOffset = false)
        assertEquals(34.1234, exactSignInfo.randomizedLatitude)
        assertEquals(108.5678, exactSignInfo.randomizedLongitude)
        assertEquals(
            setOf(CoordinateSystem.BD09, CoordinateSystem.GCJ02, CoordinateSystem.WGS84),
            CoordinateSystem.entries.toSet(),
        )
    }

    @Test
    fun `captcha qr result and error hierarchy align with swift semantics`() {
        val qr = ChaoxingQRCodeParseResult(aid = 1001L, code = "999", enc = "abc123")
        assertEquals(qr, json.decodeFromString<ChaoxingQRCodeParseResult>(json.encodeToString(qr)))

        val captchaReq = ChaoxingError.CaptchaRequired(enc2 = "myEnc2")
        assertInstanceOf(Exception::class.java, captchaReq)
        assertEquals("需要完成滑块验证码", captchaReq.message)
        assertEquals("myEnc2", captchaReq.enc2)

        val wrongPosWithDist = ChaoxingError.WrongPosition(distanceMeters = 123.45)
        assertEquals("位置不在设置范围内，距离签到点约 123.5 米", wrongPosWithDist.message)
        assertEquals("位置不在设置范围内", ChaoxingError.WrongPosition().message)

        val netWithCode = ChaoxingError.NetworkError(statusCode = 502, message = "Bad Gateway")
        assertEquals("网络错误 (502): Bad Gateway", netWithCode.message)
        assertEquals("网络错误: Timeout", ChaoxingError.NetworkError(message = "Timeout").message)
        assertNotEquals(netWithCode, ChaoxingError.NetworkError(statusCode = 500, message = "Bad Gateway"))
        assertEquals("已经签到过了", ChaoxingError.AlreadySigned().message)
        assertEquals("暂不支持此签到类型", ChaoxingError.UnsupportedSignType().message)
        assertEquals("签到已截止", ChaoxingError.SignExpired().message)
    }
}
