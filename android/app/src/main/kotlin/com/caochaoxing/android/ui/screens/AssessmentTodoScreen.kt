// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

@file:OptIn(ExperimentalMaterial3Api::class)

package com.caochaoxing.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccessAlarm
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caochaoxing.android.CaochaoxingApp
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.state.AssessmentOverviewController
import com.caochaoxing.android.state.AssessmentOverviewState
import com.caochaoxing.android.state.completionKey
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxPaletteSpecs
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.model.ChaoxingAssessmentItem
import com.caochaoxing.core.model.ChaoxingAssessmentKind
import com.caochaoxing.core.model.ChaoxingCourse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val todoClock = DateTimeFormatter.ofPattern("HH:mm")
private val todoMonthDay = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val todoFullDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 按课程分组的待办。点击课程名称进入该课程的作业分栏。
 */
@Composable
fun AssessmentTodoScreen(
    appState: AndroidAppState,
    onBack: () -> Unit,
    onCourseClick: (ChaoxingCourse) -> Unit,
    onOpenWeb: (ChaoxingAssessmentItem) -> Unit,
    modifier: Modifier = Modifier,
    assessmentOverviewController: AssessmentOverviewController? = null,
) {
    val context = LocalContext.current
    val controller = remember(context, assessmentOverviewController) {
        assessmentOverviewController ?: (context.applicationContext as? CaochaoxingApp)?.assessmentOverviewController
    }
    val overview = controller?.uiState?.collectAsStateWithLifecycle()?.value
        ?: AssessmentOverviewState()
    val uiState by appState.uiState.collectAsStateWithLifecycle()
    val appScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(uiState.activePhoneNumber) {
        snackbar.currentSnackbarData?.dismiss()
    }

    LaunchedEffect(uiState.courses) {
        if (uiState.courses.isNotEmpty()) {
            controller?.load(uiState.courses)
        }
    }

    fun refresh() {
        if (isRefreshing) return
        isRefreshing = true
        appScope.launch {
            try {
                controller?.load(uiState.courses, force = true)
            } finally {
                isRefreshing = false
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = CxPalette.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Box(
                modifier = Modifier.fillMaxWidth().background(CxPalette.canvas).statusBarsPadding()
                    .heightIn(min = 65.dp).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = CxPalette.ink)
                }
                Text(
                    text = "作业待办",
                    style = CxTypography.screenTitle,
                    color = CxPalette.ink,
                    modifier = Modifier.padding(horizontal = 48.dp).semantics {
                        customActions = listOf(CustomAccessibilityAction("刷新待办") { refresh(); true })
                    },
                )
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { refresh() },
            modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)
                .navigationBarsPadding(),
        ) {
            TodoListContent(
                state = overview,
                courses = uiState.courses,
                onCourseClick = { item ->
                    uiState.courses
                        .firstOrNull { it.courseId == item.courseId && it.classId == item.classId }
                        ?.let(onCourseClick)
                },
                onOpenWeb = onOpenWeb,
                onMarkCompleted = { item ->
                    val phone = uiState.activePhoneNumber
                    if (phone != null && controller?.setWorkCompleted(item, true, phone) == true) {
                        snackbar.currentSnackbarData?.dismiss()
                        appScope.launch {
                            if (snackbar.showSnackbar("已在本机标记完成", actionLabel = "撤销") == SnackbarResult.ActionPerformed) {
                                controller.setWorkCompleted(item, false, phone)
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
internal fun TodoListContent(
    state: AssessmentOverviewState,
    onCourseClick: (ChaoxingAssessmentItem) -> Unit,
    onOpenWeb: (ChaoxingAssessmentItem) -> Unit,
    onMarkCompleted: (ChaoxingAssessmentItem) -> Unit,
    courses: List<ChaoxingCourse> = emptyList(),
) {
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000 - value % 60_000)
            value = System.currentTimeMillis()
        }
    }
    val now = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())
    val pending = remember(state) { state.pendingItems }
    val overdueCount = remember(state, nowMs) { state.overdueWorkCount(nowMs) }
    val dueToday = pending.firstOrNull { item ->
        val at = item.deadlineAtMs
        item.kind == ChaoxingAssessmentKind.WORK && at != null && at > nowMs &&
            Instant.ofEpochMilli(at).atZone(now.zone).toLocalDate() == now.toLocalDate()
    }
    val grouped = remember(pending, courses) {
        val order = courses.mapIndexed { index, course -> (course.courseId to course.classId) to index }.toMap()
        pending
            .groupBy { it.courseId to it.classId }
            .entries
            .sortedWith(compareBy({ order[it.key] ?: Int.MAX_VALUE }, { it.value.first().courseName }))
            .map { it.value }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 5.dp, bottom = 18.dp),
    ) {
        item(key = "todo_summary") {
            Text(
                text = "${pending.size} 项待处理 / $overdueCount 项逾期",
                modifier = Modifier.padding(start = 2.dp, bottom = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = CxPalette.inkSecondary,
            )
        }
        if (dueToday != null) {
            item(key = "todo_due_today") {
                TodayDeadlineCard(dueToday, now, onClick = { onOpenWeb(dueToday) })
            }
        }

        val initialLoading = state.loadingCourseKeys.isNotEmpty() && pending.isEmpty() && !state.hasLoadedAny
        if (initialLoading) {
            item(key = "todo_loading") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        "  正在读取 ${state.loadingCourseKeys.size} 门课程…",
                        style = MaterialTheme.typography.bodySmall,
                        color = CxPalette.inkSecondary,
                    )
                }
            }
        } else if (pending.isEmpty()) {
            item(key = "todo_empty") {
                Text(
                    text = when {
                        state.errors.isNotEmpty() -> "已读取的课程没有待办。"
                        state.hasLoadedAny -> "当前没有待处理的作业和考试。"
                        else -> "还没有读取到课程作业，可以下拉刷新。"
                    },
                    modifier = Modifier.padding(vertical = 24.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = CxPalette.inkSecondary,
                )
            }
        } else {
            grouped.forEach { items ->
                val courseItem = items.first()
                item(key = "todo_header_${courseItem.courseId}_${courseItem.classId}") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                            .background(CxPalette.surfaceSecondary)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "打开课程作业",
                                onClick = { onCourseClick(courseItem) },
                            )
                            .heightIn(min = 42.dp).padding(horizontal = 14.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(courseSubjectIcon(courseItem.courseName), null, tint = CxPalette.accent, modifier = Modifier.size(18.dp))
                        Text(
                            courseItem.courseName.ifBlank { "未知课程" },
                            style = CxTypography.headline.copy(fontWeight = FontWeight.Bold),
                            color = CxPalette.ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                itemsIndexed(
                    items = items.sortedWith(
                        compareBy<ChaoxingAssessmentItem> { it.isOverdueAt(nowMs) }
                            .thenBy { it.deadlineAtMs ?: Long.MAX_VALUE }
                            .thenBy { it.title },
                    ),
                    key = { _, item -> item.completionKey },
                ) { index, item ->
                    val last = index == items.lastIndex
                    val rowModifier = if (last) Modifier.clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)) else Modifier
                    Column(rowModifier.background(CxPalette.surface)) {
                        PendingAssessmentRow(
                            item = item,
                            now = now,
                            onClick = { onOpenWeb(item) },
                            onMarkCompleted = { onMarkCompleted(item) },
                        )
                        if (!last) HorizontalDivider(color = CxPalette.border, thickness = 0.5.dp)
                    }
                }
            }
        }

        if (state.errors.isNotEmpty()) {
            item(key = "todo_errors") {
                Text(
                    text = "${state.errors.size} 门课程读取失败，可下拉重试",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = CxPalette.inkSecondary,
                )
            }
        }
    }
}

@Composable
private fun TodayDeadlineCard(item: ChaoxingAssessmentItem, now: ZonedDateTime, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(CxPalette.surfaceSecondary, shape).border(1.dp, CxPalette.border, shape)
            .heightIn(min = 96.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.AccessAlarm, null, tint = CxPaletteSpecs.warningFill, modifier = Modifier.size(23.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("今日截止", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = CxPalette.amber)
            Text(item.title, style = CxTypography.headline.copy(fontWeight = FontWeight.Bold), color = CxPalette.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${todoDeadline(item, now)} 截止", style = MaterialTheme.typography.bodySmall, color = CxPalette.inkSecondary)
        }
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(9.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text("查看作业", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PendingAssessmentRow(
    item: ChaoxingAssessmentItem,
    now: ZonedDateTime,
    onClick: () -> Unit,
    onMarkCompleted: () -> Unit,
) {
    if (item.kind != ChaoxingAssessmentKind.WORK) {
        TodoRow(item, now, onClick)
        return
    }
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            // 撤销后会恢复同一个列表键，移除前清空其保存的滑动状态。
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
            onMarkCompleted()
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.semantics {
            customActions = listOf(CustomAccessibilityAction("标记完成") { onMarkCompleted(); true })
        },
        backgroundContent = {
            Row(
                modifier = Modifier.fillMaxSize()
                    .background(if (dismissState.dismissDirection == SwipeToDismissBoxValue.Settled) CxPalette.surface else CxPalette.accent)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = CxPalette.onAccent)
                Text("标记完成", style = CxTypography.headline, color = CxPalette.onAccent)
            }
        },
    ) {
        TodoRow(item, now, onClick)
    }
}

@Composable
private fun TodoRow(item: ChaoxingAssessmentItem, now: ZonedDateTime, onClick: () -> Unit) {
    val overdue = item.isOverdueAt(now.toInstant().toEpochMilli())
    Row(
        modifier = Modifier.fillMaxWidth().background(CxPalette.surface).clickable(onClick = onClick)
            .heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = CxPalette.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.kind == ChaoxingAssessmentKind.EXAM) {
                Text("考试", style = MaterialTheme.typography.bodySmall, color = CxPalette.inkSecondary)
            }
        }
        if (overdue) {
            Text(
                "已逾期",
                modifier = Modifier.background(CxPaletteSpecs.warningFill, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.bodySmall,
                color = CxPaletteSpecs.inkLight,
            )
        } else {
            Text(
                todoDeadline(item, now),
                modifier = Modifier.widthIn(max = 112.dp),
                style = MaterialTheme.typography.bodySmall,
                color = CxPalette.inkSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
            tint = CxPalette.inkTertiary, modifier = Modifier.size(16.dp),
        )
    }
}

private fun todoDeadline(item: ChaoxingAssessmentItem, now: ZonedDateTime): String {
    val at = item.deadlineAtMs ?: return item.deadlineText?.takeIf { it.isNotBlank() } ?: "未提供截止时间"
    val date = Instant.ofEpochMilli(at).atZone(now.zone)
    return when {
        date.toLocalDate() == now.toLocalDate() -> date.format(todoClock)
        date.year == now.year -> date.format(todoMonthDay)
        else -> date.format(todoFullDate)
    }
}
