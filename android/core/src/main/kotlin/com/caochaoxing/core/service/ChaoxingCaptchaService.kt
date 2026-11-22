// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Kotlin adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

package com.caochaoxing.core.service

import com.caochaoxing.core.crypto.ChaoxingCrypto
import com.caochaoxing.core.model.ChaoxingCaptchaData
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.network.ChaoxingCookiePolicy
import com.caochaoxing.core.network.ChaoxingHttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/**
 * Result of fetching a Chaoxing slider captcha challenge and its corresponding background and cutout images.
 */
data class ChaoxingCaptchaChallenge(
    val captcha: ChaoxingCaptchaData,
    val shadeImageBytes: ByteArray,
    val cutoutImageBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChaoxingCaptchaChallenge) return false
        return captcha == other.captcha &&
            shadeImageBytes.contentEquals(other.shadeImageBytes) &&
            cutoutImageBytes.contentEquals(other.cutoutImageBytes)
    }

    override fun hashCode(): Int {
        var result = captcha.hashCode()
        result = 31 * result + shadeImageBytes.contentHashCode()
        result = 31 * result + cutoutImageBytes.contentHashCode()
        return result
    }
}

/**
 * Fetches and verifies Chaoxing slider captcha challenges using JSONP endpoints.
 */
object ChaoxingCaptchaService {
    const val CAPTCHA_ID: String = "Qt9FIw9o4pwRjOyqM6yizZBh682qN2TU"
    private const val CAPTCHA_VERSION = "1.1.20"
    private const val CAPTCHA_TYPE = "slide"
    private const val TOKEN_LIFETIME_MS = 300_000L

    suspend fun fetchCaptcha(
        client: ChaoxingHttpClient,
        refererUrl: String,
    ): ChaoxingCaptchaChallenge =
        fetchCaptcha(ChaoxingEndpoints.PRODUCTION, client, refererUrl)

    internal suspend fun fetchCaptcha(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        refererUrl: String,
    ): ChaoxingCaptchaChallenge {
        val t = getCaptchaConf(endpoints, client)
        if (t <= 0L || t > Long.MAX_VALUE - TOKEN_LIFETIME_MS) {
            throw ChaoxingError.CaptchaFailed("验证码配置时间无效")
        }
        val expiresAt = t + TOKEN_LIFETIME_MS
        val nowMs = System.currentTimeMillis()
        val captchaKey = ChaoxingCrypto.md5Hex("$t${UUID.randomUUID()}")
        val iv = ChaoxingCrypto.md5Hex("$CAPTCHA_ID$CAPTCHA_TYPE$nowMs${UUID.randomUUID()}")
        val token = "${ChaoxingCrypto.md5Hex("$t$CAPTCHA_ID$CAPTCHA_TYPE$captchaKey")}:$expiresAt"

        val url = ChaoxingHttpClient.encodeChaoxingUrl(
            endpoints.captchaImageBase,
            listOf(
                "callback" to "cx_captcha_function",
                "captchaId" to CAPTCHA_ID,
                "type" to CAPTCHA_TYPE,
                "version" to CAPTCHA_VERSION,
                "captchaKey" to captchaKey,
                "token" to token,
                "referer" to refererUrl,
                "iv" to iv,
                "_" to nowMs.toString(),
            ),
        )
        val response = client.get(url)
        val json = if (response.isSuccessful) parseJsonp(response.body) else null
        val respToken = json?.stringOnlyOrNull("token")
        val imageVo = json?.objectOrNull("imageVerificationVo")
        val rawShadeUrl = imageVo?.stringOnlyOrNull("shadeImage")
        val rawCutoutUrl = imageVo?.stringOnlyOrNull("cutoutImage")
        if (respToken == null || rawShadeUrl == null || rawCutoutUrl == null) {
            throw ChaoxingError.CaptchaFailed("获取滑块验证码失败")
        }

        val shadeUrl = upgradeHttpToHttpsUnlessLoopback(rawShadeUrl)
        val cutoutUrl = upgradeHttpToHttpsUnlessLoopback(rawCutoutUrl)
        if (shadeUrl.toHttpUrlOrNull() == null || cutoutUrl.toHttpUrlOrNull() == null) {
            throw ChaoxingError.CaptchaFailed("验证码图片地址无效")
        }

        val (shadeResponse, cutoutResponse) = coroutineScope {
            val shadeDeferred = async { client.get(shadeUrl) }
            val cutoutDeferred = async { client.get(cutoutUrl) }
            shadeDeferred.await() to cutoutDeferred.await()
        }
        if (
            !shadeResponse.isSuccessful ||
            !cutoutResponse.isSuccessful ||
            shadeResponse.body.isEmpty() ||
            cutoutResponse.body.isEmpty()
        ) {
            throw ChaoxingError.CaptchaFailed("下载验证码图片失败")
        }

        val captcha = ChaoxingCaptchaData(
            captchaId = CAPTCHA_ID,
            type = CAPTCHA_TYPE,
            version = CAPTCHA_VERSION,
            token = respToken,
            captchaKey = captchaKey,
            iv = iv,
            shadeImageURL = shadeUrl,
            cutoutImageURL = cutoutUrl,
        )
        return ChaoxingCaptchaChallenge(
            captcha = captcha,
            shadeImageBytes = shadeResponse.body,
            cutoutImageBytes = cutoutResponse.body,
        )
    }

    suspend fun verifyCaptcha(
        client: ChaoxingHttpClient,
        captcha: ChaoxingCaptchaData,
        xPosition: Int,
        refererUrl: String,
    ): String? =
        verifyCaptcha(ChaoxingEndpoints.PRODUCTION, client, captcha, xPosition, refererUrl)

    internal suspend fun verifyCaptcha(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        captcha: ChaoxingCaptchaData,
        xPosition: Int,
        refererUrl: String,
    ): String? {
        val url = ChaoxingHttpClient.encodeChaoxingUrl(
            endpoints.captchaResultBase,
            listOf(
                "captchaId" to captcha.captchaId,
                "type" to captcha.type,
                "token" to captcha.token,
                "textClickArr" to """[{"x":$xPosition}]""",
                "coordinate" to "[]",
                "runEnv" to "10",
                "version" to captcha.version,
                "t" to "a",
                "iv" to captcha.iv,
                "_" to System.currentTimeMillis().toString(),
            ),
        )
        val response = client.get(url, headers = mapOf("Referer" to refererUrl))
        val json = if (response.isSuccessful) parseJsonp(response.body) else null
        if (json == null) {
            throw ChaoxingError.CaptchaFailed("验证码响应解析失败")
        }
        if (json.numberIntOrNull("error") == 1) {
            val msg = json.stringOnlyOrNull("msg") ?: "未知错误"
            throw ChaoxingError.CaptchaFailed("${msg}，验证码校验失败")
        }
        if (json.strictBooleanOrNull("result") != true) {
            return null
        }

        val extraJson = json.stringOnlyOrNull("extraData")
            ?.let { parseJsonObjectOrNull(it.replace("\\\"", "\"")) }
            ?: json.objectOrNull("extraData")
        return extraJson?.stringOnlyOrNull("validate")
    }

    private suspend fun getCaptchaConf(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
    ): Long {
        val url = ChaoxingHttpClient.encodeChaoxingUrl(
            endpoints.captchaConfBase,
            listOf(
                "captchaId" to CAPTCHA_ID,
                "_" to System.currentTimeMillis().toString(),
            ),
        )
        val response = client.get(url)
        val json = if (response.isSuccessful) parseJsonp(response.body) else null
        return json?.numberLongOrNull("t")
            ?: throw ChaoxingError.CaptchaFailed("获取验证码配置失败")
    }

    internal fun upgradeHttpToHttpsUnlessLoopback(rawUrl: String): String {
        val parsed = rawUrl.toHttpUrlOrNull() ?: return rawUrl
        if (parsed.scheme == "http" && !ChaoxingCookiePolicy.isLoopbackHost(parsed.host)) {
            return parsed.newBuilder().scheme("https").build().toString()
        }
        return rawUrl
    }

    internal fun parseJsonp(data: ByteArray): JsonObject? {
        var text = data.toString(Charsets.UTF_8).trim { it.isWhitespace() }
        if (text.endsWith(";")) {
            text = text.dropLast(1).trim { it.isWhitespace() }
        }
        if (text.startsWith("cx_captcha_function(") && text.endsWith(")")) {
            text = text.removePrefix("cx_captcha_function(").dropLast(1)
        }
        return parseJsonObjectOrNull(text)
    }
}
