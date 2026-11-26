// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caochaoxing.android.state.AndroidBatchWatchWorker
import com.caochaoxing.android.state.CourseStudyController
import com.caochaoxing.android.state.CourseStudyUiState
import com.caochaoxing.android.state.StudyLogEntry
import com.caochaoxing.android.ui.components.CxBadge
import com.caochaoxing.android.ui.components.CxFlowRow
import com.caochaoxing.android.ui.components.CxIconTile
import com.caochaoxing.android.ui.components.CxPrimaryButton
import com.caochaoxing.android.ui.components.CxSecondaryButton
import com.caochaoxing.android.ui.components.CxSectionHeader
import com.caochaoxing.android.ui.components.CxSurfaceCard
import com.caochaoxing.android.ui.components.CxTone
import com.caochaoxing.android.ui.components.CxWellBox
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.model.ChaoxingAttachmentKind
import com.caochaoxing.core.model.ChaoxingChapterAttachment
import com.caochaoxing.core.model.ChaoxingChapterContent
import com.caochaoxing.core.model.ChaoxingChapterNode
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingLiveTask
import com.caochaoxing.core.model.ChaoxingVideoTask
import java.util.Locale

private val CONCURRENCY_OPTIONS = listOf(1, 2, 3, 5)

/**
 * Keeps the screen awake only while legal video heartbeat workers are actively running.
 */
@Composable
fun KeepScreenOnWhileWatching(isWatching: Boolean) {
    val context = LocalContext.current
    DisposableEffect(context, isWatching) {
        val window = context.findHostActivity()?.window
        if (isWatching && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (isWatching && window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

/**
 * Renders the complete Android course-study section (legal multi-way watching controls,
 * active worker progress cards, expandable chapter task points, and study logs)
 * directly inside a parent [LazyListScope].
 */
fun LazyListScope.courseStudyItems(
    course: ChaoxingCourse,
    allCourses: List<ChaoxingCourse>,
    studyState: CourseStudyUiState,
    controller: CourseStudyController,
    expandedChapterKeys: Set<String>,
    onToggleChapter: (ChaoxingChapterNode) -> Unit,
    showLogs: Boolean,
    onToggleLogs: () -> Unit,
) {
    val chapterResult = studyState.chaptersByCourse[course.id]
    val chapters = chapterResult?.chapters.orEmpty()
    val isLoadingChapters = course.id in studyState.loadingChapterCourseIds
    val chapterError = studyState.chapterErrorsByCourse[course.id]
    val displayedChapters = if (studyState.onlyPendingChapters) {
        chapters.filter { it.pendingJobCount != 0 }
    } else {
        chapters
    }

    // 1. Legal Multi-Way Simultaneous Watch Control Card
    item(key = "ai_follow_study") { AiFollowStudyCard() }
    item(key = "study_control_card") {
        StudyControlCard(
            course = course,
            allCourses = allCourses,
            studyState = studyState,
            controller = controller,
        )
    }

    // 2. Active Watch Workers
    if (studyState.activeWorkers.isNotEmpty()) {
        item(key = "study_workers_header") {
            CxSectionHeader(
                title = "进行中的任务",
                trailing = "${studyState.activeWorkers.size} 路进行中",
                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
            )
        }
        items(
            items = studyState.activeWorkers,
            key = { "worker_${it.taskId}" },
        ) { worker ->
            ActiveWorkerCard(
                worker = worker,
                onSkip = { controller.skipWorker(worker.taskId) },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }

    // 3. Chapter Directory Header + Pending Filter
    item(key = "study_chapters_header") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CxSectionHeader(
                title = "课程章节与任务点",
                trailing = if (chapterResult != null) {
                    val pendingChapters = chapters.count { it.pendingJobCount != 0 }
                    "${chapters.size} 节 · 待查/未完 $pendingChapters"
                } else {
                    null
                },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = studyState.onlyPendingChapters,
                onClick = { controller.setOnlyPendingChapters(!studyState.onlyPendingChapters) },
                label = { Text("仅看未完成", style = MaterialTheme.typography.labelMedium) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CxPalette.accent.copy(alpha = 0.14f),
                    selectedLabelColor = CxPalette.accent,
                ),
            )
        }
    }

    // 4. Chapter Loading / Error / Empty / List
    when {
        chapterError != null && chapters.isEmpty() -> {
            item(key = "study_chapters_error") {
                ActivityListNotice(
                    title = "章节目录加载失败：$chapterError",
                    action = "重试",
                    onClick = { controller.loadChapters(course, forceRefresh = true) },
                )
            }
        }

        chapterResult == null || (isLoadingChapters && chapters.isEmpty()) -> {
            item(key = "study_chapters_loading") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = CxPalette.accent,
                    )
                    Text("正在读取章节目录…", color = CxPalette.inkSecondary)
                }
            }
        }

        displayedChapters.isEmpty() -> {
            item(key = "study_chapters_empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (studyState.onlyPendingChapters && chapters.isNotEmpty()) {
                            "本课全部章节均已显示完成"
                        } else {
                            "未解析到课程章节"
                        },
                        style = CxTypography.headline,
                        color = CxPalette.ink,
                    )
                    if (studyState.onlyPendingChapters && chapters.isNotEmpty()) {
                        TextButton(onClick = { controller.setOnlyPendingChapters(false) }) {
                            Text("显示全部 ${chapters.size} 节")
                        }
                    } else {
                        TextButton(onClick = { controller.loadChapters(course, forceRefresh = true) }) {
                            Text("重新加载章节")
                        }
                    }
                }
            }
        }

        else -> {
            items(
                items = displayedChapters,
                key = { "chapter_${it.courseId}_${it.classId}_${it.id}" },
            ) { chapter ->
                val chKey = CourseStudyController.chapterKey(chapter)
                val isExpanded = chKey in expandedChapterKeys
                val content = studyState.contentByChapterKey[chKey]
                val isLoadingContent = chKey in studyState.loadingChapterKeys

                ChapterItemRow(
                    course = course,
                    chapter = chapter,
                    isExpanded = isExpanded,
                    isLoadingContent = isLoadingContent,
                    content = content,
                    completingDocumentIds = studyState.completingDocumentIds,
                    activeWorkerTaskIds = studyState.activeWorkers.mapTo(HashSet()) { it.taskId },
                    onToggleExpand = { onToggleChapter(chapter) },
                    onRefreshChapter = { controller.loadChapterContent(course, chapter, forceRefresh = true) },
                    onWatchSingleVideo = { video -> controller.startSingleVideoWatch(course, chapter, video) },
                    onWatchSingleLive = { live -> controller.startSingleLiveWatch(course, chapter, live) },
                    onCompleteDocument = { att -> controller.completeDocument(course.id, chapter, att) },
                )
            }
        }
    }

    // 5. Recent Study Logs
    if (studyState.logs.isNotEmpty()) {
        item(key = "study_logs_card") {
            StudyLogsCard(
                logs = studyState.logs,
                expanded = showLogs,
                onToggleExpanded = onToggleLogs,
                onClear = { controller.clearLogs() },
                modifier = Modifier.padding(top = 14.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun StudyControlCard(
    course: ChaoxingCourse,
    allCourses: List<ChaoxingCourse>,
    studyState: CourseStudyUiState,
    controller: CourseStudyController,
) {
    CxSurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        showBorder = true,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "课程任务进度",
                    style = CxTypography.headline,
                    color = CxPalette.ink,
                )
                Text(
                    text = "此页显示客户端上报进度。请在学习通或回放原网页观看，并在学习通核对学时与完成情况。",
                    style = MaterialTheme.typography.bodySmall,
                    color = CxPalette.inkSecondary,
                )
            }
            CxBadge(
                text = if (studyState.isWatching) "运行中" else "待命",
                tone = if (studyState.isWatching) CxTone.SAGE else CxTone.NEUTRAL,
                showDot = studyState.isWatching,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Concurrency selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "同时运行的任务数",
                style = MaterialTheme.typography.labelLarge,
                color = CxPalette.inkSecondary,
            )
            CxFlowRow(horizontalSpacing = 6.dp, verticalSpacing = 4.dp) {
                CONCURRENCY_OPTIONS.forEach { count ->
                    FilterChip(
                        selected = studyState.maxConcurrentWatches == count,
                        onClick = { controller.setMaxConcurrentWatches(count) },
                        enabled = !studyState.isWatching,
                        label = { Text("$count 路") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CxPalette.accent.copy(alpha = 0.14f),
                            selectedLabelColor = CxPalette.accent,
                        ),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(color = CxPalette.border, thickness = 0.5.dp)

        // Toggle 1: Auto-match other courses (DEFAULT OFF)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "自动匹配其他课程",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = CxPalette.ink,
                )
                Text(
                    text = if (studyState.watchAllCoursesAutomatically) {
                        "将检查并处理全部 ${allCourses.size} 门课程"
                    } else {
                        "仅处理当前课程"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = CxPalette.inkSecondary,
                )
            }
            Switch(
                checked = studyState.watchAllCoursesAutomatically,
                onCheckedChange = { controller.setWatchAllCoursesAutomatically(it) },
                enabled = !studyState.isWatching,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CxPalette.onAccent,
                    checkedTrackColor = CxPalette.accent,
                ),
            )
        }

        // Toggle 2: Auto-complete PDF/documents (DEFAULT ON)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "顺便完成 PDF / 文档任务点",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = CxPalette.ink,
                )
                Text(
                    text = "扫描章节时自动将可完成的 PPT/PDF 文档标为已完成",
                    style = MaterialTheme.typography.bodySmall,
                    color = CxPalette.inkSecondary,
                )
            }
            Switch(
                checked = studyState.autoCompleteDocuments,
                onCheckedChange = { controller.setAutoCompleteDocuments(it) },
                enabled = !studyState.isWatching,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CxPalette.onAccent,
                    checkedTrackColor = CxPalette.accent,
                ),
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Start / Stop action button
        if (studyState.isWatching) {
            Button(
                onClick = { controller.stopWatch() },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CxPalette.cinnabar,
                    contentColor = CxPalette.onAccent,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text("停止任务", fontWeight = FontWeight.SemiBold)
            }
        } else {
            val scopeLabel = if (studyState.watchAllCoursesAutomatically && allCourses.size > 1) {
                "开始全部课程任务 (${studyState.maxConcurrentWatches} 路)"
            } else {
                "开始本课任务 (${studyState.maxConcurrentWatches} 路)"
            }
            CxPrimaryButton(
                onClick = { controller.startWatch(course, allCourses) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(scopeLabel, fontWeight = FontWeight.SemiBold)
            }
        }

        // Live progress / cooldown / summary strip
        if (studyState.isWatching || studyState.totalDiscoveredCount > 0 || studyState.completedDocsCount > 0) {
            Spacer(modifier = Modifier.height(10.dp))
            CxWellBox(modifier = Modifier.fillMaxWidth()) {
                if (studyState.watchScopeLabel.isNotBlank()) {
                    Text(
                        text = studyState.watchScopeLabel,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = CxPalette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (studyState.scanProgress.isNotBlank()) {
                    Text(
                        text = studyState.scanProgress,
                        style = MaterialTheme.typography.bodySmall,
                        color = CxPalette.inkSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (studyState.cooldownRemainingSec > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(CxPalette.amber.copy(alpha = 0.14f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.HourglassTop,
                            contentDescription = null,
                            tint = CxPalette.amber,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "触发限流保护，冷却剩余 ${studyState.cooldownRemainingSec}s 后自动继续",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = CxPalette.amber,
                        )
                    }
                }
                CxFlowRow(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalSpacing = 6.dp,
                    verticalSpacing = 6.dp,
                ) {
                    CxBadge("本地任务结束 ${studyState.completedVideosCount}/${studyState.totalDiscoveredCount}", CxTone.SAGE, monospaced = true)
                    if (studyState.completedDocsCount > 0) {
                        CxBadge("文档 ${studyState.completedDocsCount}", CxTone.ACCENT, monospaced = true)
                    }
                    if (studyState.queueCount > 0) {
                        CxBadge("排队 ${studyState.queueCount}", CxTone.NEUTRAL, monospaced = true)
                    }
                    if (studyState.skippedCount > 0) {
                        CxBadge("跳过 ${studyState.skippedCount}", CxTone.AMBER, monospaced = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveWorkerCard(
    worker: AndroidBatchWatchWorker,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CxSurfaceCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp),
        showBorder = true,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CxIconTile(
                icon = if (worker.isLiveReplay) Icons.Rounded.LiveTv else Icons.Rounded.OndemandVideo,
                tone = if (worker.isLiveReplay) CxTone.AMBER else CxTone.ACCENT,
                size = 34.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = worker.videoName,
                    style = CxTypography.headline,
                    color = CxPalette.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${worker.courseName} · ${worker.chapterTitle}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CxPalette.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                CxFlowRow(horizontalSpacing = 6.dp, verticalSpacing = 4.dp) {
                    if (worker.isLiveReplay) {
                        CxBadge(
                            text = "直播回放 · 30s心跳",
                            tone = CxTone.AMBER,
                            monospaced = true,
                        )
                    } else {
                        CxBadge(
                            text = CourseStudyController.formatRate(worker.speedMultiplier),
                            tone = if (worker.speedMultiplier >= 1.99) CxTone.SAGE else CxTone.NEUTRAL,
                            monospaced = true,
                        )
                    }
                    if (worker.requiresFaceCapture) {
                        CxBadge(
                            text = "视频含人脸抓拍提醒",
                            tone = CxTone.AMBER,
                            icon = Icons.Rounded.WarningAmber,
                        )
                    }
                }
            }
            TextButton(
                onClick = onSkip,
                enabled = !worker.isSkipping,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "跳过",
                    modifier = Modifier.size(16.dp),
                    tint = CxPalette.inkSecondary,
                )
                Text(
                    text = if (worker.isSkipping) "跳过中" else "跳过",
                    style = MaterialTheme.typography.labelMedium,
                    color = CxPalette.inkSecondary,
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LinearProgressIndicator(
            progress = { worker.progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = CxPalette.accent,
            trackColor = CxPalette.surfaceSecondary,
            strokeCap = StrokeCap.Round,
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "任务进度 ${CourseStudyController.formatTime(worker.displaySeconds)} / ${CourseStudyController.formatTime(worker.duration)}" +
                    if (worker.isLiveReplay) {
                        "（最近记录 ${CourseStudyController.formatTime(worker.confirmedSeconds)}）"
                    } else {
                        "（最近上报 ${CourseStudyController.formatTime(worker.confirmedSeconds)}）"
                    },
                style = CxTypography.mono(11.sp),
                color = CxPalette.inkSecondary,
            )
            Text(
                text = if (worker.nextReportCountdownSec > 0) {
                    "${worker.nextReportCountdownSec}s 后上报"
                } else {
                    worker.statusText
                },
                style = CxTypography.mono(11.sp),
                color = CxPalette.accent,
            )
        }
    }
}

@Composable
private fun ChapterItemRow(
    course: ChaoxingCourse,
    chapter: ChaoxingChapterNode,
    isExpanded: Boolean,
    isLoadingContent: Boolean,
    content: ChaoxingChapterContent?,
    completingDocumentIds: Set<String>,
    activeWorkerTaskIds: Set<String>,
    onToggleExpand: () -> Unit,
    onRefreshChapter: () -> Unit,
    onWatchSingleVideo: (ChaoxingVideoTask) -> Unit,
    onWatchSingleLive: (ChaoxingLiveTask) -> Unit,
    onCompleteDocument: (ChaoxingChapterAttachment) -> Unit,
) {
    val context = LocalContext.current
    fun openExternalUrl(rawUrl: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rawUrl)))
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (chapter.sectionTitle.isNotBlank()) {
                    Text(
                        text = chapter.sectionTitle,
                        style = CxTypography.eyebrow,
                        color = CxPalette.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = chapter.title,
                    style = CxTypography.headline,
                    color = CxPalette.ink,
                )
            }

            when (val pending = chapter.pendingJobCount) {
                0 -> CxBadge(text = "已标记完成", tone = CxTone.SAGE, icon = Icons.Rounded.Check)
                null -> CxBadge(text = "待检查", tone = CxTone.NEUTRAL)
                else -> CxBadge(text = "待做 $pending", tone = CxTone.ACCENT, monospaced = true)
            }

            Icon(
                imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (isExpanded) "收起" else "展开",
                tint = CxPalette.inkTertiary,
                modifier = Modifier.size(20.dp),
            )
        }

        if (isExpanded) {
            CxWellBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                contentPadding = PaddingValues(12.dp),
            ) {
                when {
                    isLoadingContent && content == null -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = CxPalette.accent,
                            )
                            Text(
                                text = "正在解析任务点…",
                                style = MaterialTheme.typography.bodySmall,
                                color = CxPalette.inkSecondary,
                            )
                        }
                    }

                    content == null || (content.videos.isEmpty() && content.liveTasks.isEmpty() && content.otherAttachments.isEmpty()) -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "本节未检测到视频、直播回放或文档任务点",
                                style = MaterialTheme.typography.bodySmall,
                                color = CxPalette.inkSecondary,
                            )
                            TextButton(onClick = onRefreshChapter) {
                                Text("重新读取")
                            }
                        }
                    }

                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            content.videos.forEach { video ->
                                VideoTaskRow(
                                    video = video,
                                    isActiveWorker = video.id in activeWorkerTaskIds,
                                    onWatch = { onWatchSingleVideo(video) },
                                )
                            }
                            content.liveTasks.forEach { live ->
                                LiveTaskRow(
                                    live = live,
                                    isActiveWorker = live.id in activeWorkerTaskIds,
                                    onWatch = { onWatchSingleLive(live) },
                                    onOpenBrowser = { live.webPageUrl?.let(::openExternalUrl) },
                                )
                            }
                            content.otherAttachments.forEach { attachment ->
                                AttachmentTaskRow(
                                    attachment = attachment,
                                    isCompleting = attachment.id in completingDocumentIds,
                                    onComplete = { onCompleteDocument(attachment) },
                                    onOpenExternal = { attachment.externalUrl?.let(::openExternalUrl) },
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    onClick = onRefreshChapter,
                                    enabled = !isLoadingContent,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Text(
                                        text = if (isLoadingContent) "刷新中…" else "刷新本节状态",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = CxPalette.border, thickness = 0.5.dp)
    }
}

@Composable
private fun VideoTaskRow(
    video: ChaoxingVideoTask,
    isActiveWorker: Boolean,
    onWatch: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = CxPalette.surface,
        border = BorderStroke(0.6.dp, CxPalette.border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CxIconTile(
                icon = Icons.Rounded.OndemandVideo,
                tone = when {
                    video.isPassed -> CxTone.SAGE
                    isActiveWorker -> CxTone.ACCENT
                    else -> CxTone.NEUTRAL
                },
                size = 30.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = video.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = CxPalette.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                CxFlowRow(horizontalSpacing = 6.dp, verticalSpacing = 2.dp) {
                    Text(
                        text = CourseStudyController.formatTime(video.effectiveClipEnd),
                        style = CxTypography.mono(11.sp),
                        color = CxPalette.inkTertiary,
                    )
                    Text(
                        text = "· ${CourseStudyController.formatRate(video.allowedMaxRate)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CxPalette.inkSecondary,
                    )
                    if (video.requiresFaceCapture) {
                        Text(
                            text = "· 含人脸抓拍",
                            style = MaterialTheme.typography.labelSmall,
                            color = CxPalette.amber,
                        )
                    }
                }
            }

            when {
                video.isPassed -> CxBadge(text = "已标记通过", tone = CxTone.SAGE, icon = Icons.Rounded.Check)
                isActiveWorker -> CxBadge(text = "任务进行中", tone = CxTone.ACCENT, showDot = true)
                !video.isReportableTask -> CxBadge(text = "非任务点", tone = CxTone.NEUTRAL)
                else -> {
                    CxSecondaryButton(
                        onClick = onWatch,
                        compact = true,
                    ) {
                        Text("开始任务", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTaskRow(
    live: ChaoxingLiveTask,
    isActiveWorker: Boolean,
    onWatch: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = CxPalette.surface,
        border = BorderStroke(0.6.dp, CxPalette.border),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CxIconTile(
                    icon = Icons.Rounded.LiveTv,
                    tone = when {
                        live.isPassed -> CxTone.SAGE
                        isActiveWorker -> CxTone.ACCENT
                        live.isJob -> CxTone.AMBER
                        else -> CxTone.NEUTRAL
                    },
                    size = 30.dp,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = live.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = CxPalette.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    CxFlowRow(horizontalSpacing = 6.dp, verticalSpacing = 2.dp) {
                        if (live.duration > 0) {
                            Text(
                                text = CourseStudyController.formatTime(live.duration),
                                style = CxTypography.mono(11.sp),
                                color = CxPalette.inkTertiary,
                            )
                        }
                        Text(
                            text = "· ${live.statusDescription}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CxPalette.inkSecondary,
                        )
                        if (live.percentValue > 0.0 && !live.isPassed) {
                            Text(
                                text = String.format(Locale.ROOT, "· 记录进度 %.1f%%", live.percentValue),
                                style = CxTypography.mono(11.sp),
                                color = CxPalette.accent,
                            )
                        }
                    }
                }

                when {
                    live.isPassed -> CxBadge(text = "已标记通过", tone = CxTone.SAGE, icon = Icons.Rounded.Check)
                    isActiveWorker -> CxBadge(text = "同步中", tone = CxTone.ACCENT, showDot = true)
                    !live.isJob -> CxBadge(text = "非任务点", tone = CxTone.NEUTRAL)
                    !live.isReportableTask -> CxBadge(text = live.statusDescription, tone = CxTone.NEUTRAL)
                    else -> {
                        CxSecondaryButton(
                            onClick = onWatch,
                            compact = true,
                        ) {
                            Text("开始回放任务", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            if (!live.webPageUrl.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "浏览器中观看回放",
                        style = MaterialTheme.typography.labelSmall,
                        color = CxPalette.inkTertiary,
                    )
                    TextButton(
                        onClick = onOpenBrowser,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.OpenInBrowser,
                            contentDescription = "浏览器打开",
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text("原网页", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentTaskRow(
    attachment: ChaoxingChapterAttachment,
    isCompleting: Boolean,
    onComplete: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    var showAi by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (showAi) {
        AiDraftSheet(attachment.externalUrl.orEmpty(), attachment.name, null, onDismiss = { showAi = false })
    }
    val icon = when (attachment.kind) {
        ChaoxingAttachmentKind.DOCUMENT -> Icons.Rounded.Description
        ChaoxingAttachmentKind.QUIZ -> Icons.Rounded.Quiz
        else -> Icons.Rounded.Description
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = CxPalette.surface,
        border = BorderStroke(0.6.dp, CxPalette.border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CxIconTile(
                icon = icon,
                tone = if (attachment.isPassed) CxTone.SAGE else CxTone.NEUTRAL,
                size = 30.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = attachment.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = CxPalette.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = attachment.kind.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = CxPalette.inkSecondary,
                )
            }

            when {
                attachment.kind == ChaoxingAttachmentKind.QUIZ && !attachment.externalUrl.isNullOrBlank() -> {
                    TextButton(onClick = { showAi = true }) { Text("AI 作答") }
                }
                attachment.isPassed -> CxBadge(text = "已标记完成", tone = CxTone.SAGE, icon = Icons.Rounded.Check)
                attachment.canCompleteAsDocument -> {
                    CxSecondaryButton(
                        onClick = onComplete,
                        enabled = !isCompleting,
                        compact = true,
                    ) {
                        if (isCompleting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.8.dp,
                                color = CxPalette.accent,
                            )
                        } else {
                            Text("完成文档", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                !attachment.externalUrl.isNullOrBlank() -> {
                    CxSecondaryButton(
                        onClick = onOpenExternal,
                        compact = true,
                    ) {
                        Text("打开外链", style = MaterialTheme.typography.labelMedium)
                    }
                }
                else -> CxBadge(text = if (attachment.isJob) "手动处理" else "参考资料", tone = CxTone.NEUTRAL)
            }
        }
    }
}

@Composable
private fun StudyLogsCard(
    logs: List<StudyLogEntry>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CxSurfaceCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp),
        showBorder = true,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "任务运行日志 (${logs.size})",
                style = CxTypography.headline,
                color = CxPalette.ink,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onClear,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("清空", style = MaterialTheme.typography.labelMedium)
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起日志" else "展开日志",
                tint = CxPalette.inkSecondary,
                modifier = Modifier.size(20.dp),
            )
        }

        val visibleLogs = if (expanded) logs.take(40) else logs.take(4)
        Spacer(modifier = Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            visibleLogs.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = entry.timeText,
                        style = CxTypography.mono(11.sp),
                        color = CxPalette.inkTertiary,
                    )
                    Text(
                        text = entry.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.isError) CxPalette.cinnabar else CxPalette.inkSecondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
