// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

@file:OptIn(ExperimentalMaterial3Api::class)

package com.caochaoxing.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caochaoxing.android.CaochaoxingApp
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.state.AssessmentOverviewState
import com.caochaoxing.android.ui.components.CxBadge
import com.caochaoxing.android.ui.components.CxIconTile
import com.caochaoxing.android.ui.components.CxSectionHeader
import com.caochaoxing.android.ui.components.CxTone
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.model.ChaoxingAssessmentItem
import com.caochaoxing.core.model.ChaoxingAssessmentKind
import com.caochaoxing.core.model.ChaoxingCourse
import kotlinx.coroutines.launch

/**
 * 全部课程的未完成作业 / 考试清单：按科目分组，点条目回到该课程。
 */
@Composable
fun AssessmentTodoScreen(
    appState: AndroidAppState,
    onBack: () -> Unit,
    onCourseClick: (ChaoxingCourse) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val controller = remember(context) {
        (context.applicationContext as? CaochaoxingApp)?.assessmentOverviewController
    }
    val overview = controller?.uiState?.collectAsStateWithLifecycle()?.value
        ?: AssessmentOverviewState()
    val uiState by appState.uiState.collectAsStateWithLifecycle()
    val appScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

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
        topBar = {
            TopAppBar(
                title = {
                    Text("未完成作业", style = CxTypography.title, color = CxPalette.ink)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CxPalette.canvas,
                    titleContentColor = CxPalette.ink,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }, enabled = !isRefreshing) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = CxPalette.accent)
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { refresh() },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            TodoListContent(
                state = overview,
                onCourseClick = { item ->
                    uiState.courses
                        .firstOrNull { it.courseId == item.courseId && it.classId == item.classId }
                        ?.let(onCourseClick)
                },
            )
        }
    }
}

@Composable
private fun TodoListContent(
    state: AssessmentOverviewState,
    onCourseClick: (ChaoxingAssessmentItem) -> Unit,
) {
    val pending = state.pendingItems
    val grouped = remember(pending) {
        pending
            .groupBy { it.courseName.ifBlank { "未知课程" } }
            .toSortedMap()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        item(key = "todo_summary") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .background(CxPalette.surface, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (pending.isEmpty()) "没有未完成的作业" else "共 ${pending.size} 项待处理",
                    style = CxTypography.headline,
                    color = CxPalette.ink,
                )
                Text(
                    text = "作业 ${state.pendingWorkCount} 项 · 考试 ${state.pendingExamCount} 项 · 涉及 ${state.pendingCourseCount} 门课程",
                    style = MaterialTheme.typography.bodySmall,
                    color = CxPalette.inkSecondary,
                )
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
                    text = if (state.hasLoadedAny) {
                        "这个账号下的作业和考试都完成了。"
                    } else {
                        "还没有读取到课程作业，下拉或点右上角刷新。"
                    },
                    modifier = Modifier.padding(vertical = 24.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = CxPalette.inkSecondary,
                )
            }
        } else {
        grouped.forEach { (courseName, items) ->
            item(key = "todo_header_$courseName") {
                CxSectionHeader(
                    title = courseName,
                    trailing = "${items.size} 项待处理",
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
            items(
                items = items.sortedWith(
                    compareBy<ChaoxingAssessmentItem> { it.deadlineAtMs ?: Long.MAX_VALUE }
                        .thenBy { it.title },
                ),
                key = { "${it.kind.name}_${it.title}_${it.courseId}_${it.classId}" },
            ) { item ->
                TodoRow(item = item, onClick = { onCourseClick(item) })
            }
        }

            if (state.errors.isNotEmpty()) {
                item(key = "todo_errors") {
                    Text(
                        text = "${state.errors.size} 门课程读取失败，可回到课程列表下拉重试",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = CxPalette.inkTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun TodoRow(item: ChaoxingAssessmentItem, onClick: () -> Unit) {
    val tone = when {
        item.status.isPending -> CxTone.AMBER
        else -> CxTone.NEUTRAL
    }
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CxIconTile(
                icon = if (item.kind == ChaoxingAssessmentKind.EXAM) {
                    Icons.Rounded.Quiz
                } else {
                    Icons.AutoMirrored.Rounded.Assignment
                },
                tone = tone,
                size = 34.dp,
                contentDescription = item.kind.displayName,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = item.title,
                    style = CxTypography.headline,
                    color = CxPalette.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val now = System.currentTimeMillis()
                val at = item.deadlineAtMs
                val deadlineText = when {
                    at != null -> "截止 ${formatAssessmentDeadline(at)} · ${relativeAssessmentDeadline(at, now)}"
                    !item.deadlineText.isNullOrBlank() -> "截止 ${item.deadlineText}（页面未提供时分）"
                    else -> "页面未提供截止时间"
                }
                Text(
                    text = deadlineText,
                    style = CxTypography.mono(11.sp),
                    color = if (item.isOverdueAt(now)) CxPalette.cinnabar else CxPalette.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CxBadge(text = item.displayStatus, tone = tone)
        }
        HorizontalDivider(color = CxPalette.border, thickness = 0.5.dp)
    }
}
