// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.ui.screens

import com.caochaoxing.core.model.ChaoxingAssessmentItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 截止时间显示：今年省略年份，精确到分。 */
internal fun formatAssessmentDeadline(epochMs: Long): String {
    val dateTime = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    val clock = "%02d:%02d".format(dateTime.hour, dateTime.minute)
    return if (dateTime.year == LocalDate.now().year) {
        "${dateTime.monthValue}月${dateTime.dayOfMonth}日 $clock"
    } else {
        "${dateTime.year}年${dateTime.monthValue}月${dateTime.dayOfMonth}日 $clock"
    }
}

/** 距离截止还有多久；已过截止返回「已截止」。 */
internal fun relativeAssessmentDeadline(epochMs: Long, nowMs: Long): String? {
    val diff = epochMs - nowMs
    if (diff <= 0) return "已截止"
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "不足 1 分钟"
        minutes < 60 -> "剩 $minutes 分钟"
        minutes < 24 * 60 -> "剩 ${minutes / 60} 小时 ${minutes % 60} 分"
        minutes < 7 * 24 * 60 -> "剩 ${minutes / (24 * 60)} 天 ${(minutes % (24 * 60)) / 60} 小时"
        else -> "剩 ${minutes / (24 * 60)} 天"
    }
}

/** 未完成的排前面，其次按截止时间升序。 */
internal fun sortAssessments(items: List<ChaoxingAssessmentItem>): List<ChaoxingAssessmentItem> =
    items.sortedWith(
        compareBy<ChaoxingAssessmentItem> { if (it.status.isPending) 0 else 1 }
            .thenBy { it.deadlineAtMs ?: Long.MAX_VALUE },
    )
