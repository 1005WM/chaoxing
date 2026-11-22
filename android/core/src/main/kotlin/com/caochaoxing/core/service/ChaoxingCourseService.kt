// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Kotlin adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

package com.caochaoxing.core.service

import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.network.ChaoxingHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Fetches and parses Chaoxing course lists, class validity, and class metadata.
 */
object ChaoxingCourseService {
    private const val DEFAULT_COURSE_COVER =
        "https://p.ananas.chaoxing.com/star3/270_160c/669ca80d6a0c5f74835bb936a41aabca.jpg"

    suspend fun fetchCourses(client: ChaoxingHttpClient): List<ChaoxingCourse> =
        fetchCourses(ChaoxingEndpoints.PRODUCTION, client)

    internal suspend fun fetchCourses(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
    ): List<ChaoxingCourse> {
        var didReauthenticate = false
        var response = client.get(endpoints.courseListUrl)
        if (response.statusCode == 401 || response.statusCode == 403 || response.statusCode == 302) {
            didReauthenticate = true
            ChaoxingAuthService.reLogin(endpoints, client)
            response = client.get(endpoints.courseListUrl)
        }
        if (!response.isSuccessful) {
            throw ChaoxingError.NetworkError(statusCode = response.statusCode, message = "获取课程列表失败")
        }

        try {
            return parseCourseListJson(response.body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChaoxingError) {
            if (didReauthenticate) throw e
            // Session cookies may yield HTTP 200 with result=0 or an HTML login page; retry once after reLogin.
        }

        ChaoxingAuthService.reLogin(endpoints, client)
        val retryResponse = client.get(endpoints.courseListUrl)
        if (!retryResponse.isSuccessful) {
            throw ChaoxingError.NetworkError(statusCode = retryResponse.statusCode, message = "获取课程列表失败")
        }
        return parseCourseListJson(retryResponse.body)
    }

    fun parseCourseListJson(data: ByteArray): List<ChaoxingCourse> =
        parseCourseListJson(data.toString(Charsets.UTF_8))

    fun parseCourseListJson(rawJson: String): List<ChaoxingCourse> {
        val json = parseJsonObjectOrNull(rawJson)
            ?: throw ChaoxingError.ParseFailed("课程列表 JSON 解析失败")
        if (json.numberIntOrNull("result") == 0 || json.strictBooleanOrNull("result") == false) {
            throw ChaoxingError.SessionExpired()
        }
        val channelList = json.arrayOrNull("channelList")
            ?: throw ChaoxingError.SessionExpired("课程列表为空或会话已过期")

        val courses = mutableListOf<ChaoxingCourse>()
        for (element in channelList) {
            val item = element as? JsonObject ?: continue
            val cataName = item["cataName"]
            if (cataName == null || cataName is JsonNull) continue
            val content = item.objectOrNull("content") ?: continue
            val courseWrapper = content.objectOrNull("course") ?: continue
            val dataArray = courseWrapper.arrayOrNull("data") ?: continue
            val courseObj = dataArray.firstOrNull() as? JsonObject ?: continue

            val courseName = courseObj.stringOnlyOrNull("name") ?: "未命名课程"
            val teacherName = courseObj.stringOnlyOrNull("teacherfactor") ?: ""
            val courseId = courseObj.numberLongOrNull("id") ?: 0L
            val classId = content.numberIntOrNull("id") ?: 0
            val imageUrl = courseObj.stringOnlyOrNull("imageurl")?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_COURSE_COVER
            val schools = courseObj.stringOnlyOrNull("schools")
            val cpi = content.longOrStringLongOrNull("cpi") ?: item.longOrStringLongOrNull("cpi")

            courses.add(
                ChaoxingCourse(
                    courseName = courseName,
                    teacherName = teacherName,
                    courseId = courseId,
                    classId = classId,
                    imageURL = imageUrl.replace("http://", "https://"),
                    schools = schools,
                    cpi = cpi,
                ),
            )
        }
        return courses
    }

    suspend fun checkClassValid(client: ChaoxingHttpClient, classId: Int): Boolean? =
        checkClassValid(ChaoxingEndpoints.PRODUCTION, client, classId)

    internal suspend fun checkClassValid(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        classId: Int,
    ): Boolean? {
        val response = try {
            client.get(endpoints.courseListUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        if (!response.isSuccessful) return null
        val json = parseJsonObjectOrNull(response.body) ?: return null
        if (json.numberIntOrNull("result") == 0 || json.strictBooleanOrNull("result") == false) {
            return null
        }
        val channelList = json.arrayOrNull("channelList") ?: return null
        for (element in channelList) {
            val item = element as? JsonObject ?: continue
            val content = item.objectOrNull("content") ?: continue
            if (content.numberIntOrNull("id") == classId) {
                return true
            }
        }
        return false
    }

    suspend fun getCourseIdFromClassId(client: ChaoxingHttpClient, classId: Int): Long? =
        getCourseIdFromClassId(ChaoxingEndpoints.PRODUCTION, client, classId)

    internal suspend fun getCourseIdFromClassId(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        classId: Int,
    ): Long? {
        val courses = try {
            fetchCourses(endpoints, client)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return courses.firstOrNull { it.classId == classId }?.courseId
    }

    suspend fun queryClassName(client: ChaoxingHttpClient, classId: Int): String? =
        queryClassName(ChaoxingEndpoints.PRODUCTION, client, classId)

    internal suspend fun queryClassName(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        classId: Int,
    ): String? {
        val url = ChaoxingHttpClient.encodeChaoxingUrl(
            endpoints.queryClassNameBase,
            listOf("id" to classId.toString()),
        )
        val response = try {
            client.get(url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        if (!response.isSuccessful) return null
        val json = parseJsonObjectOrNull(response.body) ?: return null
        val dataArray = json.arrayOrNull("data") ?: return null
        val firstObj = dataArray.firstOrNull() as? JsonObject ?: return null
        return firstObj.stringOnlyOrNull("name")
    }
}
