// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.ui.screens

import com.caochaoxing.core.model.ChaoxingAssessmentItem
import com.caochaoxing.core.model.ChaoxingAssessmentKind
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 把手机列表页里的作业链接换成学习通电脑版作业页。
 *
 * 手机页：`/work/phone/task-work?courseId=…&classId=…&workId=…`
 * 电脑页：`/mooc-ans/work/doHomeWorkNew?courseId=…&classId=…&workId=…`
 *
 * 只转换作业；考试页不转换（考试入口可能涉及开考状态，保持原样更安全）。
 */
internal fun desktopAssessmentUrl(item: ChaoxingAssessmentItem): String? {
    if (item.kind != ChaoxingAssessmentKind.WORK) return null
    val raw = item.taskUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!raw.startsWith("http://", ignoreCase = true) &&
        !raw.startsWith("https://", ignoreCase = true)
    ) {
        return null
    }
    // 直接按字符串取查询串，避免 java.net.URI 因为中文标题、空格等字符解析失败。
    val query = parseQuery(raw.substringAfter('?', "").substringBefore('#'))
    val workId = query["workid"] ?: query["id"] ?: return null
    val courseId = query["courseid"] ?: item.courseId.takeIf { it > 0 }?.toString() ?: return null
    val classId = query["classid"] ?: item.classId.takeIf { it > 0 }?.toString() ?: return null
    return "https://mooc1.chaoxing.com/mooc-ans/work/doHomeWorkNew" +
        "?courseId=${encode(courseId)}&classId=${encode(classId)}&workId=${encode(workId)}"
}

private fun encode(value: String): String =
    runCatching { URLEncoder.encode(value, "UTF-8") }.getOrDefault(value)

private fun parseQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    return rawQuery.split('&').mapNotNull { pair ->
        val index = pair.indexOf('=')
        if (index <= 0) return@mapNotNull null
        val key = pair.substring(0, index).lowercase()
        val value = pair.substring(index + 1)
        key to runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }.toMap()
}
