// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Kotlin adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

package com.caochaoxing.core.service

import com.caochaoxing.core.crypto.ChaoxingCrypto
import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingClientPreset
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.model.ChaoxingSchool
import com.caochaoxing.core.model.ChaoxingUserProfile
import com.caochaoxing.core.network.ChaoxingHttpClient
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Configurable endpoint descriptors for Chaoxing services and loopback `MockWebServer` tests.
 */
internal data class ChaoxingEndpoints(
    val loginUrl: String = "https://passport2.chaoxing.com/fanyalogin",
    val userInfoUrl: String = "https://sso.chaoxing.com/apis/login/userLogin4Uname.do",
    val courseListUrl: String = "https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1",
    val queryClassNameBase: String = "https://mooc1-api.chaoxing.com/gas/clazz?fields=name&view=json",
    val activeListBase: String = "https://mobilelearn.chaoxing.com/v2/apis/active/student/activelist?fid=0&showNotStartedActive=0",
    val signInfoBase: String = "https://mobilelearn.chaoxing.com/v2/apis/active/getPPTActiveInfo",
    val easemobTokenUrl: String = "https://a1-vip6.easemob.com/cx-dev/cxstudy/token",
    val easemobGroupsTemplate: String = "https://a1-vip6.easemob.com/cx-dev/cxstudy/users/%s/joined_chatgroups?detail=true&version=v3&pagenum=1&pagesize=200",
    val easemobMessageRoamingTemplate: String = "https://a1-vip6.easecdn.com/cx-dev/cxstudy/users/%s/messageroaming",
    val captchaConfBase: String = "https://captcha.chaoxing.com/captcha/get/conf?callback=cx_captcha_function",
    val captchaImageBase: String = "https://captcha.chaoxing.com/captcha/get/verification/image",
    val captchaResultBase: String = "https://captcha.chaoxing.com/captcha/check/verification/result?callback=cx_captcha_function",
    val cloudTokenUrl: String = "https://pan-yz.chaoxing.com/api/token/uservalid",
    val cloudUploadBase: String = "https://pan-yz.chaoxing.com/upload?_from=mobilelearn&_token=",
    val profileFaceUrl: String = "https://mobilelearn.chaoxing.com/v2/apis/sign/collectionfilephotoEnc?DB_STRATEGY=DEFAULT",
    val checkFaceBase: String = "https://mobilelearn.chaoxing.com/pptSign/check-face-result?DB_STRATEGY=PRIMARY_KEY&STRATEGY_PARA=activeId",
    val preSignBase: String = "https://mobilelearn.chaoxing.com/newsign/preSign",
    val analysisBase: String = "https://mobilelearn.chaoxing.com/pptSign/analysis?vs=1&DB_STRATEGY=RANDOM",
    val analysis2Base: String = "https://mobilelearn.chaoxing.com/pptSign/analysis2?DB_STRATEGY=RANDOM",
    val stuSignBase: String = "https://mobilelearn.chaoxing.com/pptSign/stuSignajax",
    val signDetailBase: String = "https://mobilelearn.chaoxing.com/newsign/signDetail",
    val checkSignCodeBase: String = "https://mobilelearn.chaoxing.com/widget/sign/pcStuSignController/checkSignCode",
    val nominatimSearchUrl: String = "https://nominatim.openstreetmap.org/search",
    val studyBase: String = "https://mooc1-2.chaoxing.com",
    val mooc2Base: String = "https://mooc2-ans.chaoxing.com",
    val zhiboBase: String = "https://zhibo.chaoxing.com",
    val gasClazzTreeBase: String = "https://mooc1-api.chaoxing.com/gas/clazz",
    val assessmentBase: String = "https://mooc1-api.chaoxing.com/phone/moocAnalysis",
    val assessmentFallbackBases: List<String> = listOf(
        "https://mooc1.chaoxing.com/phone/moocAnalysis",
        "https://mooc1-2.chaoxing.com/phone/moocAnalysis",
    ),
) {
    /** 作业 / 考试列表的候选主机：首选 api 网关，失败时依次回退。 */
    val assessmentBases: List<String>
        get() = listOf(assessmentBase) + assessmentFallbackBases

    companion object {
        internal val PRODUCTION: ChaoxingEndpoints = ChaoxingEndpoints()

        internal fun forTesting(baseUrl: String): ChaoxingEndpoints {
            val base = baseUrl.trimEnd('/')
            return ChaoxingEndpoints(
                loginUrl = "$base/fanyalogin",
                userInfoUrl = "$base/apis/login/userLogin4Uname.do",
                courseListUrl = "$base/mycourse/backclazzdata?view=json&rss=1",
                queryClassNameBase = "$base/gas/clazz?fields=name&view=json",
                activeListBase = "$base/v2/apis/active/student/activelist?fid=0&showNotStartedActive=0",
                signInfoBase = "$base/v2/apis/active/getPPTActiveInfo",
                easemobTokenUrl = "$base/cx-dev/cxstudy/token",
                easemobGroupsTemplate = "$base/cx-dev/cxstudy/users/%s/joined_chatgroups?detail=true&version=v3&pagenum=1&pagesize=200",
                easemobMessageRoamingTemplate = "$base/cx-dev/cxstudy/users/%s/messageroaming",
                captchaConfBase = "$base/captcha/get/conf?callback=cx_captcha_function",
                captchaImageBase = "$base/captcha/get/verification/image",
                captchaResultBase = "$base/captcha/check/verification/result?callback=cx_captcha_function",
                cloudTokenUrl = "$base/api/token/uservalid",
                cloudUploadBase = "$base/upload?_from=mobilelearn&_token=",
                profileFaceUrl = "$base/v2/apis/sign/collectionfilephotoEnc?DB_STRATEGY=DEFAULT",
                checkFaceBase = "$base/pptSign/check-face-result?DB_STRATEGY=PRIMARY_KEY&STRATEGY_PARA=activeId",
                preSignBase = "$base/newsign/preSign",
                analysisBase = "$base/pptSign/analysis?vs=1&DB_STRATEGY=RANDOM",
                analysis2Base = "$base/pptSign/analysis2?DB_STRATEGY=RANDOM",
                stuSignBase = "$base/pptSign/stuSignajax",
                signDetailBase = "$base/newsign/signDetail",
                checkSignCodeBase = "$base/widget/sign/pcStuSignController/checkSignCode",
                nominatimSearchUrl = "$base/search",
                studyBase = base,
                mooc2Base = base,
                zhiboBase = base,
                gasClazzTreeBase = "$base/gas/clazz",
                assessmentBase = "$base/phone/moocAnalysis",
                assessmentFallbackBases = emptyList(),
            )
        }
    }
}

internal val chaoxingJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal fun parseJsonObjectOrNull(bytes: ByteArray): JsonObject? =
    parseJsonObjectOrNull(bytes.toString(Charsets.UTF_8))

internal fun parseJsonObjectOrNull(text: String): JsonObject? =
    try {
        chaoxingJson.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

internal fun JsonPrimitive.isJsonBoolean(): Boolean =
    !isString && (content == "true" || content == "false")

internal fun JsonObject.stringOnlyOrNull(key: String): String? {
    val prim = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
    return if (prim.isString) prim.content else null
}

internal fun JsonObject.stringOrNumberString(key: String): String? {
    val prim = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
    return if (!prim.isJsonBoolean()) prim.content else null
}

internal fun JsonObject.strictBooleanOrNull(key: String): Boolean? {
    val prim = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
    return if (prim.isJsonBoolean()) prim.content == "true" else null
}

internal fun JsonObject.numberIntOrNull(key: String): Int? {
    val prim = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
    if (prim.isString || prim.isJsonBoolean()) return null
    return prim.content.toIntOrNull() ?: prim.content.toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()
}

internal fun JsonObject.numberLongOrNull(key: String): Long? {
    val prim = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
    if (prim.isString || prim.isJsonBoolean()) return null
    return prim.content.toLongOrNull() ?: prim.content.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
}

internal fun JsonObject.numberDoubleOrNull(key: String): Double? {
    val prim = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
    if (prim.isString || prim.isJsonBoolean()) return null
    return prim.content.toDoubleOrNull()?.takeIf { it.isFinite() }
}

internal fun JsonObject.intOrStringIntOrNull(key: String): Int? {
    val prim = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
    if (prim.isJsonBoolean()) return null
    return prim.content.toIntOrNull()
}

internal fun JsonObject.longOrStringLongOrNull(key: String): Long? {
    val prim = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
    if (prim.isJsonBoolean()) return null
    return prim.content.toLongOrNull()
}

internal fun JsonObject.doubleOrStringDoubleOrNull(key: String): Double? {
    val prim = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
    if (prim.isJsonBoolean()) return null
    return prim.content.toDoubleOrNull()?.takeIf { it.isFinite() }
}

internal fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

/**
 * Handles Chaoxing credential authentication, single-flight session re-login, and user profile lookup.
 */
object ChaoxingAuthService {
    suspend fun login(
        phoneNumber: String,
        password: String,
        isEncryptedPassword: Boolean = false,
        userAgent: String = ChaoxingClientPreset.DEFAULT.userAgent,
        existingDeviceCode: String? = null,
        isPrimary: Boolean = false,
    ): ChaoxingAccountSession =
        login(
            endpoints = ChaoxingEndpoints.PRODUCTION,
            phoneNumber = phoneNumber,
            password = password,
            isEncryptedPassword = isEncryptedPassword,
            userAgent = userAgent,
            existingDeviceCode = existingDeviceCode,
            isPrimary = isPrimary,
        )

    internal suspend fun login(
        endpoints: ChaoxingEndpoints,
        phoneNumber: String,
        password: String,
        isEncryptedPassword: Boolean = false,
        userAgent: String = ChaoxingClientPreset.DEFAULT.userAgent,
        existingDeviceCode: String? = null,
        isPrimary: Boolean = false,
        clientFactory: (ChaoxingAccountSession, String) -> ChaoxingHttpClient = { session, ua ->
            ChaoxingHttpClient(session = session, userAgent = ua)
        },
    ): ChaoxingAccountSession {
        val trimmedPhone = phoneNumber.trim { it.isWhitespace() }
        if (trimmedPhone.isEmpty()) {
            throw ChaoxingError.InvalidInput("手机号/账号不能为空")
        }
        if (!isEncryptedPassword) {
            if (password.isEmpty()) {
                throw ChaoxingError.InvalidInput("密码不能为空")
            }
            if (password.length !in 8..16) {
                throw ChaoxingError.InvalidInput("密码位数应在 8-16 位之间")
            }
        }

        val deviceCode = existingDeviceCode?.takeIf { it.isNotEmpty() }
            ?: ChaoxingCrypto.randomizedDeviceCode()

        val encryptedPassword = if (isEncryptedPassword) {
            password
        } else {
            ChaoxingCrypto.encryptLoginField(password)
        }
        val tempSession = ChaoxingAccountSession(
            phoneNumber = trimmedPhone,
            encryptedPassword = encryptedPassword,
            deviceCode = deviceCode,
            isPrimary = isPrimary,
        )
        val client = clientFactory(tempSession, userAgent)
        try {
            performLogin(
                endpoints = endpoints,
                client = client,
                phoneNumber = trimmedPhone,
                encryptedPassword = tempSession.encryptedPassword,
            )
            val profile = fetchUserProfile(endpoints, client)
            return client.applyAuthenticatedProfile(profile, matching = tempSession)
        } finally {
            client.invalidate()
        }
    }

    suspend fun reLogin(client: ChaoxingHttpClient): ChaoxingAccountSession =
        reLogin(ChaoxingEndpoints.PRODUCTION, client)

    internal suspend fun reLogin(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
    ): ChaoxingAccountSession =
        client.reauthenticate {
            performReLogin(endpoints, client)
        }

    private suspend fun performReLogin(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
    ): ChaoxingAccountSession {
        val context = client.authenticationContext()
        val session = context.session
        if (session.phoneNumber.isEmpty() || session.encryptedPassword.isEmpty()) {
            throw ChaoxingError.SessionExpired()
        }
        performLogin(
            endpoints = endpoints,
            client = client,
            phoneNumber = session.phoneNumber,
            encryptedPassword = session.encryptedPassword,
        )
        val profile = fetchUserProfile(endpoints, client)
        return client.applyAuthenticatedProfile(
            profile = profile,
            matching = session,
            generation = context.generation,
        )
    }

    private suspend fun performLogin(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        phoneNumber: String,
        encryptedPassword: String,
    ) {
        val encryptedPhone = ChaoxingCrypto.encryptLoginField(phoneNumber)
        val encodedBody = listOf(
            "fid=-1",
            "uname=${ChaoxingCrypto.urlFormEncodeBase64(encryptedPhone)}",
            "password=${ChaoxingCrypto.urlFormEncodeBase64(encryptedPassword)}",
            "refer=https%3A%2F%2Fi.chaoxing.com",
            "t=true",
            "forbidotherlogin=0",
            "validate=",
            "doubleFactorLogin=0",
            "independentId=0",
            "independentNameId=0",
        ).joinToString("&")

        val response = client.postFormEncoded(endpoints.loginUrl, encodedBody = encodedBody)
        if (!response.isSuccessful) {
            throw ChaoxingError.NetworkError(statusCode = response.statusCode, message = "登录请求失败")
        }
        val json = parseJsonObjectOrNull(response.body)
            ?: throw ChaoxingError.ParseFailed("登录响应格式错误")
        val status = json.strictBooleanOrNull("status") ?: false
        if (!status) {
            val msg2 = json.stringOnlyOrNull("msg2")?.trim { it.isWhitespace() }?.takeIf { it.isNotEmpty() }
            throw ChaoxingError.LoginFailed(msg2 ?: "登录失败，请检查账号或密码")
        }
    }

    internal fun positiveIdentifier(value: JsonElement?): Int? {
        val prim = (value as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
        if (prim.isJsonBoolean()) return null
        val integer = prim.content.toIntOrNull() ?: return null
        return integer.takeIf { it > 0 }
    }

    suspend fun fetchUserProfile(client: ChaoxingHttpClient): ChaoxingUserProfile =
        fetchUserProfile(ChaoxingEndpoints.PRODUCTION, client)

    internal suspend fun fetchUserProfile(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
    ): ChaoxingUserProfile {
        val response = client.get(endpoints.userInfoUrl)
        if (!response.isSuccessful) {
            if (response.statusCode == 403 || response.statusCode == 302) {
                throw ChaoxingError.SessionExpired()
            }
            throw ChaoxingError.NetworkError(statusCode = response.statusCode, message = "获取用户信息失败")
        }
        val root = parseJsonObjectOrNull(response.body)
            ?: throw ChaoxingError.SessionExpired("获取用户信息失败，会话可能已过期")
        val msg = root.objectOrNull("msg")
            ?: throw ChaoxingError.SessionExpired("获取用户信息失败，会话可能已过期")

        val uid = positiveIdentifier(msg["uid"])
            ?: throw ChaoxingError.SessionExpired("用户信息缺少有效账号标识，请重新登录")
        val puid = positiveIdentifier(msg["puid"])
            ?: throw ChaoxingError.SessionExpired("用户信息缺少有效账号标识，请重新登录")

        val fid = positiveIdentifier(msg["fid"]) ?: 0
        val name = msg.stringOnlyOrNull("name") ?: ""
        val uname = msg.stringOnlyOrNull("uname") ?: ""
        val pic = (msg.stringOnlyOrNull("pic") ?: "").replace("http://", "https://")
        val clientId = msg.stringOnlyOrNull("clientId")?.takeIf { it.isNotEmpty() }

        val imPassword = msg.objectOrNull("accountInfo")
            ?.objectOrNull("imAccount")
            ?.stringOnlyOrNull("password")
            ?: ""

        val schoolsList = mutableListOf<Pair<Int, String>>()
        val defaultSchoolName = msg.stringOnlyOrNull("schoolname")?.takeIf { it.isNotEmpty() } ?: "未知学校"
        schoolsList.add(fid to defaultSchoolName)

        val configs = msg.arrayOrNull("unitConfigInfos")
        if (configs != null) {
            for (element in configs) {
                val config = element as? JsonObject ?: continue
                val configFid = config.numberIntOrNull("fid") ?: continue
                val schoolName = config.stringOnlyOrNull("schoolname")?.takeIf { it.isNotEmpty() } ?: "未知学校"
                val existingIndex = schoolsList.indexOfFirst { it.first == configFid }
                if (existingIndex >= 0) {
                    if (schoolsList[existingIndex].second == "未知学校" && schoolName != "未知学校") {
                        schoolsList[existingIndex] = configFid to schoolName
                    }
                } else {
                    schoolsList.add(configFid to schoolName)
                }
            }
        }

        val schools = schoolsList.map { ChaoxingSchool(fid = it.first, name = it.second) }
        return ChaoxingUserProfile(
            uid = uid,
            puid = puid,
            fid = fid,
            name = name,
            uname = uname,
            avatarURL = pic,
            phoneNumber = client.session.phoneNumber,
            imEncryptedPassword = imPassword,
            clientId = clientId,
            schools = schools,
        )
    }
}
