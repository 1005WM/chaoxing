// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.ui.screens

import com.caochaoxing.core.model.ChaoxingAssessmentItem
import com.caochaoxing.core.model.ChaoxingAssessmentKind
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

private const val DESKTOP_WORK_BASE = "https://mooc1.chaoxing.com/mooc-ans"
private const val DESKTOP_COURSE_PATH = "/visit/stucoursemiddle"

internal fun assessmentItemUrl(item: ChaoxingAssessmentItem): String? {
    val url = item.taskUrl?.trim()?.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
        ?: return null
    val path = assessmentPath(url) ?: return null
    return url.takeUnless { isCourseStudyUrl(it) || isHomeworkListPath(path) }
}

/** 优先使用服务端已给出的电脑版作业链接。考试保持原链接。 */
internal fun desktopAssessmentUrl(item: ChaoxingAssessmentItem, courseCpi: Long? = null): String? {
    if (item.kind != ChaoxingAssessmentKind.WORK) return null
    val raw = assessmentItemUrl(item) ?: return null
    val path = assessmentPath(raw) ?: return null
    if (isDesktopAssessmentPath(path)) return raw
    if (!isMobileHomeworkUrl(raw)) return null

    val query = parseQuery(raw.substringAfter('?', "").substringBefore('#'))
    val workId = query["workid"] ?: query["work_id"] ?: query["id"]
        ?: path.substringAfterLast('/').takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?: return null
    val courseId = query["courseid"] ?: item.courseId.takeIf { it > 0 }?.toString() ?: return null
    val classId = query["classid"] ?: query["clazzid"] ?: item.classId.takeIf { it > 0 }?.toString() ?: return null
    val parameters = linkedMapOf("courseId" to courseId, "classId" to classId)
    val cpi = query["cpi"] ?: courseCpi?.takeIf { it > 0 }?.toString()
    cpi?.let { parameters["cpi"] = it }
    parameters["workId"] = workId
    val answerId = query["answerid"] ?: query["workanswerid"]
    val enc = query["enc"]
    answerId?.let { parameters["answerId"] = it }
    enc?.let { parameters["enc"] = it }
    query["ut"]?.let { parameters["ut"] = it }
    val target = if (cpi != null && answerId != null && enc != null) "/mooc2/work/task"
    else "/work/doHomeWorkNew"
    return buildDesktopWorkUrl(target, parameters)
}

internal fun desktopAssessmentCourseFallback(url: String): String? {
    val path = assessmentPath(url) ?: return null
    if (!isDesktopAssessmentPath(path) || !isHomeworkListPath(path)) return null
    val query = parseQuery(url.substringAfter('?', "").substringBefore('#'))
    val courseId = query["courseid"] ?: return null
    val classId = query["classid"] ?: query["clazzid"] ?: return null
    return desktopCourseUrl(courseId, classId, query["cpi"])
}

private fun desktopCourseUrl(courseId: String, classId: String, cpi: String?): String =
    "https://mooc1.chaoxing.com$DESKTOP_COURSE_PATH?courseid=${encode(courseId)}" +
        "&clazzid=${encode(classId)}&cpi=${encode(cpi ?: "0")}&vc=1&ismooc2=1&v=2"

private fun buildDesktopWorkUrl(path: String, parameters: Map<String, String>): String =
    "$DESKTOP_WORK_BASE$path?" + parameters.entries.joinToString("&") { (key, value) ->
        "$key=${encode(value)}"
    }

internal fun isDesktopAssessmentUrl(url: String): Boolean =
    assessmentPath(url)?.let(::isDesktopAssessmentPath) == true || isDesktopAssessmentCourseUrl(url)

internal fun isDesktopAssessmentCourseUrl(url: String): Boolean = when (assessmentPath(url)) {
    DESKTOP_COURSE_PATH, "/mooc2-ans/mycourse/studentcourse", "/mooc-ans/mycourse/studentcourse" -> true
    else -> false
}

internal fun isCourseStudyUrl(url: String): Boolean {
    val path = assessmentPath(url) ?: return false
    return isDesktopAssessmentCourseUrl(url) ||
        path.substringAfterLast('/') in setOf("studentstudy", "studentstudycourselist", "studentstudyajax")
}

internal fun isHomeworkDetailUrl(url: String): Boolean {
    val path = assessmentPath(url) ?: return false
    return !isHomeworkListPath(path) && (isDesktopAssessmentPath(path) || isMobileHomeworkUrl(url))
}

internal fun isHomeworkListUrl(url: String): Boolean =
    assessmentPath(url)?.let(::isHomeworkListPath) == true

private fun isHomeworkListPath(path: String): Boolean = when (path.removePrefix("/mooc-ans")) {
    "/mooc2/work/list", "/work/task-list", "/work/phone/task-list", "/phone/moocanalysis/worklist" -> true
    else -> false
}

private fun isDesktopAssessmentPath(path: String): Boolean =
    path.startsWith("/mooc-ans/mooc2/work/") || path.startsWith("/mooc2/work/") ||
        path == "/mooc-ans/work/dohomeworknew" || path == "/mooc-ans/work/stu-work"

internal fun isMobileHomeworkUrl(url: String): Boolean {
    val path = assessmentPath(url)?.removePrefix("/mooc-ans") ?: return false
    return path.startsWith("/work/phone/") || path == "/work/task-list" ||
        path == "/phone/moocanalysis/worklist"
}

private fun assessmentPath(url: String): String? = runCatching {
    // 部分列表链接的查询参数含有未转义的标题，只解析主机和路径。
    val address = url.substringBefore('#').substringBefore('?')
    val https = address.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
    if (!isAllowedAssessmentUrl(https)) return null
    URI(https).path.lowercase(Locale.ROOT).trimEnd('/')
}.getOrNull()

/** 只接受 https 且主机名为 chaoxing.com 或其子域名。 */
internal fun isAllowedAssessmentUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    uri.scheme.equals("https", ignoreCase = true) &&
        (host == "chaoxing.com" || host.endsWith(".chaoxing.com"))
}.getOrDefault(false)

private fun encode(value: String): String =
    runCatching { URLEncoder.encode(value, "UTF-8") }.getOrDefault(value)

private fun parseQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    return rawQuery.split('&').mapNotNull { pair ->
        val index = pair.indexOf('=')
        if (index <= 0) return@mapNotNull null
        val key = pair.substring(0, index).lowercase(Locale.ROOT)
        val value = pair.substring(index + 1)
        val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        decoded.takeIf { it.isNotBlank() }?.let { key to it }
    }.toMap()
}
