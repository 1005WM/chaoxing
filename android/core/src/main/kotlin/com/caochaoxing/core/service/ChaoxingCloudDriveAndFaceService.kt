// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Kotlin adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

package com.caochaoxing.core.service

import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.network.ChaoxingHttpClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/**
 * Handles Chaoxing Cloud Drive token caching and multipart photo uploads with single 401/403 retry.
 */
object ChaoxingCloudDriveService {
    suspend fun getCloudToken(client: ChaoxingHttpClient): String =
        getCloudToken(ChaoxingEndpoints.PRODUCTION, client)

    internal suspend fun getCloudToken(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
    ): String {
        client.storageCloudToken?.let { return it }
        val response = client.get(endpoints.cloudTokenUrl)
        val json = if (response.isSuccessful) parseJsonObjectOrNull(response.body) else null
        val token = json?.stringOnlyOrNull("_token")?.takeIf { it.isNotEmpty() }
            ?: throw ChaoxingError.ParseFailed("获取超星云盘 Token 失败")
        client.storageCloudToken = token
        return token
    }

    suspend fun uploadImage(
        client: ChaoxingHttpClient,
        imageBytes: ByteArray,
        filename: String = "${UUID.randomUUID()}.jpg",
    ): String =
        uploadImage(ChaoxingEndpoints.PRODUCTION, client, imageBytes, filename)

    internal suspend fun uploadImage(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        imageBytes: ByteArray,
        filename: String = "${UUID.randomUUID()}.jpg",
    ): String =
        try {
            performUpload(endpoints, client, imageBytes, filename)
        } catch (error: ChaoxingError.NetworkError) {
            if (error.statusCode != 401 && error.statusCode != 403) {
                throw error
            }
            // Only an explicit authorization rejection is safe to retry. A timeout or
            // malformed success response may follow an upload already stored by the server.
            currentCoroutineContext().ensureActive()
            client.storageCloudToken = null
            performUpload(endpoints, client, imageBytes, filename)
        }

    private suspend fun performUpload(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        imageBytes: ByteArray,
        filename: String,
    ): String {
        val token = getCloudToken(endpoints, client)
        currentCoroutineContext().ensureActive()
        val url = endpoints.cloudUploadBase + ChaoxingHttpClient.formPercentEncode(token)
        if (url.toHttpUrlOrNull() == null) {
            throw ChaoxingError.ParseFailed("云盘上传 URL 无效")
        }
        val response = client.postMultipartImage(
            url = url,
            puid = client.session.puid,
            filename = filename,
            imageBytes = imageBytes,
        )
        if (!response.isSuccessful) {
            throw ChaoxingError.NetworkError(statusCode = response.statusCode, message = "上传照片到超星云盘失败")
        }
        val json = parseJsonObjectOrNull(response.body)
        return json?.stringOnlyOrNull("objectId")?.takeIf { it.isNotEmpty() }
            ?: throw ChaoxingError.ParseFailed("上传照片到超星云盘失败")
    }
}

/**
 * Fetches stored user face profile object IDs and submits face check-in verifications for `faceEnc`.
 */
object ChaoxingFaceService {
    const val SHARED_IMAGE_URL_FORMAT: String = "https://p.cldisk.com/star4/%s/origin.jpg"

    suspend fun fetchUserProfileFaceObjectId(client: ChaoxingHttpClient): String =
        fetchUserProfileFaceObjectId(ChaoxingEndpoints.PRODUCTION, client)

    internal suspend fun fetchUserProfileFaceObjectId(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
    ): String {
        val response = client.get(endpoints.profileFaceUrl)
        val json = if (response.isSuccessful) parseJsonObjectOrNull(response.body) else null
        val oldObjectId = json?.objectOrNull("data")
            ?.stringOnlyOrNull("oldObjectId")
            ?.takeIf { it.isNotEmpty() }
        return oldObjectId
            ?: throw ChaoxingError.FaceError("获取人脸底库照片失败，可能当前账号未设置过人脸识别照片")
    }

    suspend fun fetchUserProfileFaceImageUrl(client: ChaoxingHttpClient): String =
        fetchUserProfileFaceImageUrl(ChaoxingEndpoints.PRODUCTION, client)

    internal suspend fun fetchUserProfileFaceImageUrl(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
    ): String {
        val objectId = fetchUserProfileFaceObjectId(endpoints, client)
        val formatted = SHARED_IMAGE_URL_FORMAT.replace("%s", objectId)
        if (formatted.toHttpUrlOrNull() == null) {
            throw ChaoxingError.FaceError("人脸图片地址无效")
        }
        return formatted
    }

    suspend fun checkFaceResultAndGetEnc(
        client: ChaoxingHttpClient,
        objectId: String,
        activeId: Long,
    ): String =
        checkFaceResultAndGetEnc(ChaoxingEndpoints.PRODUCTION, client, objectId, activeId)

    internal suspend fun checkFaceResultAndGetEnc(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        objectId: String,
        activeId: Long,
    ): String {
        val cxtime = System.currentTimeMillis().toString()
        val faceResultStr = buildJsonObject {
            put("currentFaceId", objectId)
            put("LiveDetectionStatus", 1)
            put("collectStatus", 1)
            put("cxtime", cxtime)
        }.toString()

        val url = ChaoxingHttpClient.encodeChaoxingUrl(
            endpoints.checkFaceBase,
            listOf(
                "activeId" to activeId.toString(),
                "faceResult" to faceResultStr,
            ),
        )
        val response = client.get(url)
        val json = if (response.isSuccessful) parseJsonObjectOrNull(response.body) else null
        return json?.stringOnlyOrNull("enc")?.takeIf { it.isNotEmpty() }
            ?: throw ChaoxingError.FaceError("获取人脸校验凭证 faceEnc 失败")
    }
}
