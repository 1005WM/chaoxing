// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caochaoxing.android.state.CourseAssessmentUiState
import com.caochaoxing.android.ui.components.CxBadge
import com.caochaoxing.android.ui.components.CxFlowRow
import com.caochaoxing.android.ui.components.CxIconTile
import com.caochaoxing.android.ui.components.CxSectionHeader
import com.caochaoxing.android.ui.components.CxTone
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.model.ChaoxingAssessmentItem
import com.caochaoxing.core.model.ChaoxingAssessmentKind
import com.caochaoxing.core.model.ChaoxingCourse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val assessmentTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/**
 * 课程详情「作业 / 考试」分栏：只读展示每项任务的服务端状态与完成统计。
 */
fun LazyListScope.courseAssessmentItems(
    course: ChaoxingCourse,
    state: CourseAssessmentUiState,
    onRetry: () -> Unit,
) {
    val summary = state.summary

    item(key = "assessment_overview") {
        AssessmentOverviewCard(state = state)
    }

    if (summary == null) {
        item(key = "assessment_placeholder") {
            when {
                state.isLoading -> Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("正在读取作业与考试…", color = CxPalette.inkSecondary)
                }

                state.errorMessage != null -> Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = CxPalette.cinnabar)
                    Text("作业与考试加载失败", style = CxTypography.headline, color = CxPalette.ink)
                    Text(
                        state.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = CxPalette.inkSecondary,
                    )
                    TextButton(onClick = onRetry) { Text("重试") }
                }

                else -> Text(
                    "打开此分栏后会自动读取这门课的作业与考试。",
                    modifier = Modifier.padding(vertical = 24.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = CxPalette.inkSecondary,
                )
            }
        }
        return
    }

    state.errorMessage?.let { message ->
        item(key = "assessment_stale") {
            AssessmentNotice(
                text = "刷新失败，正在显示上次结果：$message",
                tone = CxTone.AMBER,
            )
        }
    }

    summary.warnings.forEachIndexed { index, warning ->
        item(key = "assessment_warning_$index") {
            AssessmentNotice(text = warning, tone = CxTone.AMBER)
        }
    }

    item(key = "assessment_work_header") {
        CxSectionHeader(
            title = "作业",
            trailing = when {
                summary.works.isEmpty() -> "0 项"
                else -> "${summary.works.size} 项 · ${summary.works.count { it.status.isPending }} 项未完成"
            },
            modifier = Modifier.padding(top = 14.dp),
        )
    }
    if (summary.works.isEmpty()) {
        item(key = "assessment_work_empty") {
            EmptyAssessmentRow(text = "这门课暂无作业")
        }
    } else {
        itemsIndexed(
            items = summary.works,
            key = { index, item -> "work_${index}_${item.title}" },
        ) { _, item -> AssessmentRow(item = item) }
    }

    item(key = "assessment_exam_header") {
        CxSectionHeader(
            title = "考试",
            trailing = when {
                summary.exams.isEmpty() -> "0 项"
                else -> "${summary.exams.size} 项 · ${summary.exams.count { it.status.isPending }} 项未完成"
            },
            modifier = Modifier.padding(top = 18.dp),
        )
    }
    if (summary.exams.isEmpty()) {
        item(key = "assessment_exam_empty") {
            EmptyAssessmentRow(text = "这门课暂无考试")
        }
    } else {
        itemsIndexed(
            items = summary.exams,
            key = { index, item -> "exam_${index}_${item.title}" },
        ) { _, item -> AssessmentRow(item = item) }
    }
}

@Composable
private fun AssessmentOverviewCard(state: CourseAssessmentUiState) {
    val summary = state.summary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .background(CxPalette.surface, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = summary?.headline() ?: "作业与考试",
            style = CxTypography.headline,
            color = CxPalette.ink,
        )
        if (summary != null) {
            CxFlowRow(horizontalSpacing = 8.dp, verticalSpacing = 6.dp) {
                CxBadge(text = "未完成 ${summary.pendingCount}", tone = CxTone.AMBER)
                CxBadge(text = "已完成 ${summary.completedCount}", tone = CxTone.SAGE)
                if (summary.expiredCount > 0) {
                    CxBadge(text = "已过期 ${summary.expiredCount}", tone = CxTone.NEUTRAL)
                }
                if (summary.unknownCount > 0) {
                    CxBadge(text = "状态未知 ${summary.unknownCount}", tone = CxTone.NEUTRAL)
                }
            }
        }
        val caption = when {
            state.isLoading && summary == null -> "正在读取…"
            summary == null -> "尚未读取"
            else -> buildString {
                append("共 ${summary.items.size} 项")
                if (summary.fetchedAtMs > 0) {
                    append(" · 更新于 ")
                    append(assessmentTimeFormatter.format(Instant.ofEpochMilli(summary.fetchedAtMs)))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.isLoading && summary != null) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
            }
            Text(caption, style = CxTypography.mono(11.sp), color = CxPalette.inkTertiary)
        }
    }
}

@Composable
private fun AssessmentRow(item: ChaoxingAssessmentItem) {
    val tone = when {
        item.status.isCompleted -> CxTone.SAGE
        item.status.isPending -> CxTone.AMBER
        item.status.isExpired -> CxTone.NEUTRAL
        else -> CxTone.NEUTRAL
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CxIconTile(
                icon = if (item.kind == ChaoxingAssessmentKind.EXAM) Icons.Rounded.Quiz else Icons.AutoMirrored.Rounded.Assignment,
                tone = tone,
                size = 34.dp,
                contentDescription = item.kind.displayName,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = item.title,
                    style = CxTypography.headline,
                    color = CxPalette.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.detail.isNotBlank()) {
                    Text(
                        text = if (item.kind == ChaoxingAssessmentKind.EXAM && item.detail != "--") {
                            "分数 ${item.detail}"
                        } else {
                            item.detail
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = CxPalette.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            CxBadge(
                text = item.displayStatus,
                tone = tone,
                icon = if (item.status.isCompleted) Icons.Rounded.Check else null,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        HorizontalDivider(color = CxPalette.border, thickness = 0.5.dp)
    }
}

@Composable
private fun AssessmentNotice(text: String, tone: CxTone) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .background(tone.softFill(), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = tone.foreground(), modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tone.foreground())
    }
}

@Composable
private fun EmptyAssessmentRow(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
        style = MaterialTheme.typography.bodySmall,
        color = CxPalette.inkSecondary,
    )
}
