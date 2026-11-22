// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Kotlin adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

package com.caochaoxing.core.service

import com.caochaoxing.core.crypto.ChaoxingCrypto
import com.caochaoxing.core.crypto.EasemobProtobufDecoder
import com.caochaoxing.core.model.ChaoxingEasemobIMConfig
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.model.ChaoxingGroupSignActivity
import com.caochaoxing.core.model.ChaoxingIMGroup
import com.caochaoxing.core.network.ChaoxingHttpClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64

/**
 * Interacts with Easemob IM endpoints to fetch joined Chaoxing course groups and decode group sign messages.
 */
object ChaoxingGroupService {
    const val EASEMOB_USER_AGENT: String = "Easemob-SDK(Android) 4.9.0.1"
    private const val PAGE_SIZE = 200
    private const val MAX_PAGES = 100

    suspend fun getEasemobConfig(client: ChaoxingHttpClient): ChaoxingEasemobIMConfig =
        getEasemobConfig(ChaoxingEndpoints.PRODUCTION, client)

    internal suspend fun getEasemobConfig(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
    ): ChaoxingEasemobIMConfig {
        val imPassword = ChaoxingCrypto.decryptIMPasswordDES(client.session.imEncryptedPassword)
        val payload = buildJsonObject {
            put("grant_type", "password")
            put("password", imPassword)
            put("username", client.session.uid)
        }.toString().toByteArray(Charsets.UTF_8)

        val response = client.postRaw(
            url = endpoints.easemobTokenUrl,
            body = payload,
            contentType = "application/x-www-form-urlencoded",
            headers = mapOf("User-Agent" to EASEMOB_USER_AGENT),
        )
        val json = if (response.isSuccessful) parseJsonObjectOrNull(response.body) else null
        val token = json?.stringOnlyOrNull("access_token")
        val userObj = json?.objectOrNull("user")
        val uuid = userObj?.stringOnlyOrNull("uuid")
        val username = userObj?.stringOnlyOrNull("username")
        if (token == null || uuid == null || username == null) {
            throw ChaoxingError.ParseFailed("获取环信 IM 配置失败")
        }
        return ChaoxingEasemobIMConfig(
            accessToken = token,
            uuid = uuid,
            username = username,
        )
    }

    suspend fun fetchJoinedGroups(
        client: ChaoxingHttpClient,
        imConfig: ChaoxingEasemobIMConfig,
    ): List<ChaoxingIMGroup> =
        fetchJoinedGroups(ChaoxingEndpoints.PRODUCTION, client, imConfig)

    internal suspend fun fetchJoinedGroups(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        imConfig: ChaoxingEasemobIMConfig,
    ): List<ChaoxingIMGroup> {
        val encodedUsername = percentEncodeAlphanumericOnly(imConfig.username)
        if (encodedUsername.isEmpty()) {
            throw ChaoxingError.ParseFailed("群聊列表 URL 无效")
        }
        val baseUrl = endpoints.easemobGroupsTemplate.replace("%s", encodedUsername).toHttpUrlOrNull()
            ?: throw ChaoxingError.ParseFailed("群聊列表 URL 无效")

        val groups = mutableListOf<ChaoxingIMGroup>()
        val seen = HashSet<String>()
        // The endpoint explicitly exposes pagenum/pagesize. Stop on a short page;
        // reject repeated full pages instead of silently returning an incomplete list.
        for (page in 1..MAX_PAGES) {
            currentCoroutineContext().ensureActive()
            val pageUrl = baseUrl.newBuilder()
                .setQueryParameter("pagenum", page.toString())
                .build()
                .toString()
            val response = client.get(
                url = pageUrl,
                headers = mapOf(
                    "Authorization" to "Bearer ${imConfig.accessToken}",
                    "User-Agent" to EASEMOB_USER_AGENT,
                ),
            )
            if (!response.isSuccessful) {
                if (response.statusCode == 401 || response.statusCode == 403) {
                    throw ChaoxingError.SessionExpired("群聊会话已过期，请重新加载群聊")
                }
                throw ChaoxingError.NetworkError(statusCode = response.statusCode, message = "获取群聊列表失败")
            }
            val root = parseJsonObjectOrNull(response.body)
                ?: throw ChaoxingError.ParseFailed("获取群聊列表失败")
            val items = root.arrayOrNull("data")
                ?: throw ChaoxingError.ParseFailed("获取群聊列表失败")

            val previousCount = groups.size
            for (element in items) {
                val item = element as? JsonObject ?: continue
                val group = parseJoinedGroup(item) ?: continue
                if (seen.add(group.id)) {
                    groups.add(group)
                }
            }
            if (items.size < PAGE_SIZE) {
                return groups
            }
            if (groups.size <= previousCount) {
                throw ChaoxingError.ParseFailed("群聊分页未返回新数据，请稍后重试")
            }
        }
        throw ChaoxingError.ParseFailed("群聊分页超过处理上限，未能获取完整列表")
    }

    private fun parseJoinedGroup(obj: JsonObject): ChaoxingIMGroup? {
        val id = obj.stringOnlyOrNull("id")?.takeIf { it.isNotEmpty() } ?: return null
        val rawName = obj.stringOnlyOrNull("name")?.takeIf { it.isNotEmpty() }
        var courseName: String? = null
        var imageUrl: String? = null

        val descStr = obj.stringOnlyOrNull("description")
        if (descStr != null) {
            val descJson = parseJsonObjectOrNull(descStr)
            val courseInfo = descJson?.objectOrNull("courseInfo")
            if (courseInfo != null) {
                courseName = courseInfo.stringOnlyOrNull("coursename")?.takeIf { it.isNotEmpty() }
                imageUrl = courseInfo.stringOnlyOrNull("imageUrl")?.replace("http://", "https://")
            }
        }
        return ChaoxingIMGroup(
            id = id,
            name = rawName ?: courseName ?: "未命名群聊",
            imageURL = imageUrl,
        )
    }

    suspend fun fetchGroupSignActivities(
        client: ChaoxingHttpClient,
        group: ChaoxingIMGroup,
        imConfig: ChaoxingEasemobIMConfig,
    ): List<ChaoxingGroupSignActivity> =
        fetchGroupSignActivities(ChaoxingEndpoints.PRODUCTION, client, group, imConfig)

    internal suspend fun fetchGroupSignActivities(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        group: ChaoxingIMGroup,
        imConfig: ChaoxingEasemobIMConfig,
    ): List<ChaoxingGroupSignActivity> {
        val encodedUsername = percentEncodeAlphanumericOnly(imConfig.username)
        if (encodedUsername.isEmpty()) {
            throw ChaoxingError.InvalidInput("群聊用户名无效")
        }
        val url = endpoints.easemobMessageRoamingTemplate.replace("%s", encodedUsername)
        if (url.toHttpUrlOrNull() == null) {
            throw ChaoxingError.ParseFailed("群聊消息 URL 无效")
        }
        val body = buildJsonObject {
            put("end", "-1")
            put("queue", "${group.id}@conference.easemob.com")
            put("start", "-1")
        }.toString().toByteArray(Charsets.UTF_8)

        val response = client.postRaw(
            url = url,
            body = body,
            contentType = "text/plain;charset=UTF-8",
            headers = mapOf(
                "Authorization" to "Bearer ${imConfig.accessToken}",
                "User-Agent" to EASEMOB_USER_AGENT,
            ),
        )
        if (!response.isSuccessful) {
            if (response.statusCode == 401 || response.statusCode == 403) {
                throw ChaoxingError.SessionExpired("群聊会话已过期，请重新加载群聊")
            }
            throw ChaoxingError.NetworkError(statusCode = response.statusCode, message = "获取群聊消息失败")
        }
        val root = parseJsonObjectOrNull(response.body)
            ?: throw ChaoxingError.ParseFailed("群聊消息响应格式错误")
        val msgs = root.objectOrNull("data")?.arrayOrNull("msgs")
            ?: throw ChaoxingError.ParseFailed("群聊消息响应格式错误")

        val activities = mutableListOf<ChaoxingGroupSignActivity>()
        val seenActiveIds = HashSet<Long>()

        for (element in msgs) {
            val msgObj = element as? JsonObject ?: continue
            val msgStr = msgObj.stringOnlyOrNull("msg") ?: continue
            val msgData = decodeBase64IgnoringUnknown(msgStr) ?: continue
            val decoded = EasemobProtobufDecoder.decodeMetaMessage(msgData) ?: continue
            val attachmentStr = decoded.attachmentJSON ?: continue
            val activity = parseGroupSignActivity(
                attachmentJson = attachmentStr,
                timestampMs = decoded.timestampMs,
                fallbackGroupName = group.name,
            ) ?: continue
            if (seenActiveIds.add(activity.activeId)) {
                activities.add(activity)
            }
        }
        return activities.sortedByDescending { it.messageTimestampMs }
    }

    /**
     * Parses an Easemob group sign attachment JSON, supporting both `att_chat_course` and `att_chat_sign`
     * payloads as well as URL-embedded activity/class/course identifiers.
     */
    fun parseGroupSignActivity(
        attachmentJson: String,
        timestampMs: Long,
        fallbackGroupName: String,
    ): ChaoxingGroupSignActivity? {
        val attJson = parseJsonObjectOrNull(attachmentJson) ?: return null
        val attachmentType = attJson.numberIntOrNull("attachmentType")
        val hasCourseAtt = attJson["att_chat_course"].let { it != null && it !is JsonNull }
        if (attachmentType != 15 && !hasCourseAtt) {
            return null
        }

        val signInfo = attJson.objectOrNull("att_chat_course")
            ?: attJson.objectOrNull("att_chat_sign")
            ?: attJson

        val atype = signInfo.numberIntOrNull("atype") ?: 0
        val activeTypeName = signInfo.stringOnlyOrNull("atypeName") ?: ""
        val rawUrl = signInfo.stringOnlyOrNull("url") ?: signInfo.stringOnlyOrNull("pcUrl") ?: ""
        val isSignKeyword = activeTypeName.contains("签到") ||
            activeTypeName.contains("打卡") ||
            rawUrl.contains("activePrimaryId") ||
            rawUrl.contains("stuSign") ||
            rawUrl.contains("preSign")
        if (atype != 2 && atype != 74 && !isSignKeyword) {
            return null
        }

        val activeId = signInfo.longOrStringLongOrNull("aid")
            ?: signInfo.longOrStringLongOrNull("activeId")
            ?: extractActiveIdFromUrl(rawUrl)
            ?: 0L
        if (activeId == 0L) return null

        val courseInfo = signInfo.objectOrNull("courseInfo")
        val classId = courseInfo?.intOrStringIntOrNull("classid")
            ?: signInfo.intOrStringIntOrNull("classId")
            ?: extractQueryInt(rawUrl, listOf("classId", "classid"))
            ?: 0
        val courseId = courseInfo?.longOrStringLongOrNull("courseid")
            ?: signInfo.longOrStringLongOrNull("courseId")
            ?: extractQueryInt64(rawUrl, listOf("courseId", "courseid"))
            ?: 0L
        val courseName = courseInfo?.stringOnlyOrNull("coursename") ?: fallbackGroupName
        val title = signInfo.stringOnlyOrNull("title") ?: activeTypeName.ifEmpty { "群聊签到" }
        val startTimeTitle = signInfo.stringOnlyOrNull("subTitle") ?: ""

        return ChaoxingGroupSignActivity(
            activeId = activeId,
            title = title,
            classId = classId,
            courseId = courseId,
            courseName = courseName,
            startTimeTitle = startTimeTitle,
            activeTypeName = activeTypeName,
            isUnsupportedLegacySign = false,
            messageTimestampMs = timestampMs,
        )
    }

    private fun decodeBase64IgnoringUnknown(input: String): ByteArray? =
        try {
            Base64.getMimeDecoder().decode(input)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun extractActiveIdFromUrl(urlString: String): Long? =
        extractQueryInt64(urlString, listOf("activePrimaryId", "activeId", "aid", "id"))

    private fun extractQueryInt64(urlString: String, keys: List<String>): Long? {
        val httpUrl = urlString.toHttpUrlOrNull()
        if (httpUrl != null) {
            for (key in keys) {
                for (i in 0 until httpUrl.querySize) {
                    if (httpUrl.queryParameterName(i).equals(key, ignoreCase = true)) {
                        val num = httpUrl.queryParameterValue(i)?.toLongOrNull()
                        if (num != null && num != 0L) return num
                    }
                }
            }
            return null
        }

        val query = urlString.substringAfter('?', missingDelimiterValue = "")
            .substringBefore('#')
            .takeIf { it.isNotEmpty() }
            ?: return null
        val pairs = query.split('&').mapNotNull { segment ->
            val eqIndex = segment.indexOf('=')
            if (eqIndex <= 0) null else segment.substring(0, eqIndex) to segment.substring(eqIndex + 1)
        }
        for (key in keys) {
            val num = pairs.firstOrNull { it.first.equals(key, ignoreCase = true) }
                ?.second
                ?.toLongOrNull()
            if (num != null && num != 0L) return num
        }
        return null
    }

    private fun extractQueryInt(urlString: String, keys: List<String>): Int? =
        extractQueryInt64(urlString, keys)?.toInt()

    private fun percentEncodeAlphanumericOnly(input: String): String {
        if (input.isEmpty()) return ""
        val bytes = input.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size)
        for (byte in bytes) {
            val code = byte.toInt() and 0xFF
            val isAlphaNum = (code in '0'.code..'9'.code) ||
                (code in 'A'.code..'Z'.code) ||
                (code in 'a'.code..'z'.code)
            if (isAlphaNum) {
                sb.append(code.toChar())
            } else {
                sb.append('%')
                sb.append(HEX_DIGITS[code ushr 4])
                sb.append(HEX_DIGITS[code and 0x0F])
            }
        }
        return sb.toString()
    }

    private val HEX_DIGITS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
    )
}
