// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.ui.screens

import com.caochaoxing.core.model.ChaoxingAssessmentItem
import com.caochaoxing.core.model.ChaoxingAssessmentKind
import java.net.URI
import java.net.URLDecoder

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
    val raw = item.taskUrl?.takeIf { it.isNotBlank() } ?: return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    val query = parseQuery(uri.rawQuery)
    val workId = query["workId"] ?: query["id"] ?: return null
    val courseId = query["courseId"] ?: item.courseId.takeIf { it > 0 }?.toString() ?: return null
    val classId = query["classId"] ?: item.classId.takeIf { it > 0 }?.toString() ?: return null
    return "https://mooc1.chaoxing.com/mooc-ans/work/doHomeWorkNew" +
        "?courseId=$courseId&classId=$classId&workId=$workId"
}

private fun parseQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    return rawQuery.split('&').mapNotNull { pair ->
        val index = pair.indexOf('=')
        if (index <= 0) return@mapNotNull null
        val key = pair.substring(0, index)
        val value = pair.substring(index + 1)
        key to runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }.toMap()
}
