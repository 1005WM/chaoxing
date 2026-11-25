// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

@file:OptIn(ExperimentalMaterial3Api::class)

package com.caochaoxing.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caochaoxing.android.CaochaoxingApp
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.state.AssessmentOverviewController
import com.caochaoxing.android.state.CourseStudyController
import com.caochaoxing.android.ui.components.CxBadge
import com.caochaoxing.android.ui.components.CxFlowRow
import com.caochaoxing.android.ui.components.CxIconTile
import com.caochaoxing.android.ui.components.CxSectionHeader
import com.caochaoxing.android.ui.components.CxSurfaceCard
import com.caochaoxing.android.ui.components.CxTone
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.model.ChaoxingSignType
import kotlinx.coroutines.launch

internal fun chaoxingSignTypeIcon(type: ChaoxingSignType): ImageVector = when (type) {
    ChaoxingSignType.PHOTO_OR_CLICK -> Icons.Rounded.PhotoCamera
    ChaoxingSignType.QR_CODE -> Icons.Rounded.QrCodeScanner
    ChaoxingSignType.GESTURE -> Icons.Rounded.Gesture
    ChaoxingSignType.LOCATION -> Icons.Rounded.LocationOn
    ChaoxingSignType.PASSWORD -> Icons.Rounded.Password
    ChaoxingSignType.UNKNOWN -> Icons.Rounded.TouchApp
}

/**
 * Course list tab featuring starred-course pinning, live search, and the pending-activity radar.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun CourseListScreen(
    appState: AndroidAppState,
    onCourseClick: (ChaoxingCourse) -> Unit,
    onActivityClick: (ChaoxingSignActivity) -> Unit,
    onManageAccounts: () -> Unit,
    onOpenTodo: () -> Unit,
    modifier: Modifier = Modifier,
    courseStudyController: CourseStudyController? = null,
    assessmentOverviewController: AssessmentOverviewController? = null,
) {
    val uiState by appState.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val overviewController = remember(context, assessmentOverviewController) {
        assessmentOverviewController ?: (context.applicationContext as? CaochaoxingApp)?.assessmentOverviewController
    }
    val overviewState = overviewController?.uiState?.collectAsStateWithLifecycle()?.value
    val studyState = courseStudyController?.uiState?.collectAsStateWithLifecycle()?.value
    val appScope = rememberAppPersistenceScope()
    var searchText by rememberSaveable { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }
    var showCourseTools by remember { mutableStateOf(false) }

    val filteredCourses = remember(uiState.courses, uiState.starredCourseIds, searchText) {
        val query = searchText.trim()
        val matching = if (query.isEmpty()) {
            uiState.courses
        } else {
            uiState.courses.filter { course ->
                course.courseName.contains(query, ignoreCase = true) ||
                    course.teacherName.contains(query, ignoreCase = true)
            }
        }
        val starred = matching.filter { it.courseId in uiState.starredCourseIds }
        val unstarred = matching.filterNot { it.courseId in uiState.starredCourseIds }
        starred + unstarred
    }

    LaunchedEffect(uiState.courses, uiState.activePhoneNumber) {
        if (uiState.courses.isNotEmpty()) {
            overviewController?.load(uiState.courses)
        }
    }

    fun triggerRefresh() {
        if (isRefreshing) return
        appScope.launch {
            isRefreshing = true
            try {
                appState.refreshCourses()
                if (uiState.courses.isNotEmpty()) {
                    overviewController?.load(uiState.courses, force = true)
                }
            } finally {
                isRefreshing = false
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = CxPalette.canvas,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().background(CxPalette.canvas).statusBarsPadding()
                    .padding(start = 22.dp, end = 12.dp).heightIn(min = 76.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("我的课程", style = CxTypography.screenTitle, color = CxPalette.ink)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 主页也提供检查更新入口，避免只能在设置页右上角找到。
                    Box(
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).clickable(
                            role = Role.Button,
                            onClickLabel = "检查更新",
                        ) {
                            (context.applicationContext as? CaochaoxingApp)?.updateManager?.openDialog()
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SystemUpdate,
                            contentDescription = "检查更新",
                            tint = CxPalette.accent,
                        )
                    }
                    Box {
                        Box(
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).combinedClickable(
                                role = Role.Button,
                                onClickLabel = "刷新课程",
                                onClick = { if (!uiState.isLoading) triggerRefresh() },
                                onLongClickLabel = "打开课程工具",
                                onLongClick = { showCourseTools = true },
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "刷新课程",
                                tint = CxPalette.accent,
                            )
                        }
                    DropdownMenu(expanded = showCourseTools, onDismissRequest = { showCourseTools = false }) {
                        DropdownMenuItem(
                            text = { Text(if (uiState.isScanningRadar) "正在查找待签活动" else "查找待签活动") },
                            enabled = !uiState.isScanningRadar && uiState.courses.isNotEmpty(),
                            onClick = {
                                showCourseTools = false
                                searchText = ""
                                appScope.launch { appState.scanPendingActivitiesRadar() }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("管理账号") },
                            onClick = {
                                showCourseTools = false
                                onManageAccounts()
                            },
                        )
                    }
                }
            }
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { triggerRefresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 16.dp),
            ) {
                if (uiState.courses.isNotEmpty()) {
                    item(key = "pending_homework") {
                        val loadedOverview = overviewState?.takeIf { it.hasLoadedAny }
                        // 与「作业待办」页保持一致：统计未完成作业 + 未完成考试，不能只算作业。
                        PendingHomeworkEntry(
                            count = loadedOverview?.totalPending,
                            overdueCount = loadedOverview?.overdueWorkCount(System.currentTimeMillis()),
                            onOpen = onOpenTodo,
                            modifier = Modifier.padding(bottom = 14.dp),
                        )
                    }
                }

                if (studyState != null && studyState.isWatching) {
                    item(key = "active_study_banner") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(CxPalette.sage.copy(alpha = 0.14f))
                                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "正在同步看课 · ${studyState.activeWorkers.size} 路进行中",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = CxPalette.sage,
                                )
                                Text(
                                    text = "${studyState.watchScopeLabel} · 视频 ${studyState.completedVideosCount}/${studyState.totalDiscoveredCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CxPalette.inkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { courseStudyController.stopWatch() }) {
                                Text(
                                    text = "停止",
                                    color = CxPalette.cinnabar,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                item(key = "course_search") {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).height(48.dp),
                        placeholder = { Text("搜索课程或教师") },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = cxOutlinedTextFieldColors(),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                            )
                        },
                        trailingIcon = if (searchText.isNotEmpty()) {
                            {
                                IconButton(onClick = { searchText = "" }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Clear,
                                        contentDescription = "清除搜索",
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    )
                }

                if (searchText.isBlank() && uiState.courses.isNotEmpty()) {
                    items(
                        items = uiState.pendingRadarItems,
                        key = { "radar_${it.course.id}_${it.activity.id}" },
                    ) { item ->
                        val activity = item.activity
                        CxSurfaceCard(
                            modifier = Modifier.fillMaxWidth(),
                            showBorder = false,
                            onClick = { onActivityClick(activity) },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CxIconTile(
                                    icon = chaoxingSignTypeIcon(activity.signType),
                                    tone = CxTone.AMBER,
                                    size = 34.dp,
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        text = if (activity.courseName.isEmpty()) {
                                            activity.nameOne
                                        } else {
                                            "${activity.courseName} · ${activity.nameOne}"
                                        },
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        color = CxPalette.ink,
                                    )
                                    Text(
                                        text = activity.signType.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CxPalette.inkSecondary,
                                    )
                                }
                                CxBadge(
                                    text = "待签到",
                                    tone = CxTone.AMBER,
                                    showDot = true,
                                )
                            }
                        }
                    }
                }

                if (searchText.isNotBlank()) {
                    item(key = "courses_header") {
                        CxSectionHeader(
                            title = "搜索结果",
                            trailing = "${filteredCourses.size} 门",
                            modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
                        )
                    }
                }

                if (filteredCourses.isEmpty()) {
                    item(key = "courses_empty") {
                        CxSurfaceCard(
                            modifier = Modifier.fillMaxWidth(),
                            showBorder = false,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                                    contentDescription = null,
                                    tint = CxPalette.inkTertiary,
                                    modifier = Modifier.size(32.dp),
                                )
                                Text(
                                    text = if (searchText.isBlank()) "暂无课程数据" else "未找到匹配课程",
                                    style = CxTypography.headline,
                                    color = CxPalette.ink,
                                )
                                Text(
                                    text = if (searchText.isBlank()) {
                                        "下拉刷新以从学习通同步我的课程"
                                    } else {
                                        "请尝试更换课程名或教师姓名关键词"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CxPalette.inkSecondary,
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(
                        items = filteredCourses,
                        key = { _, course -> course.id },
                    ) { index, course ->
                        val boundLocation = uiState.boundLocation(course.courseId)
                        val last = index == filteredCourses.lastIndex
                        val shape = RoundedCornerShape(
                            topStart = if (index == 0) 14.dp else 0.dp,
                            topEnd = if (index == 0) 14.dp else 0.dp,
                            bottomStart = if (last) 14.dp else 0.dp,
                            bottomEnd = if (last) 14.dp else 0.dp,
                        )

                        Column(Modifier.clip(shape).background(CxPalette.surface)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onCourseClick(course) }
                                    .heightIn(min = 69.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    modifier = Modifier.size(40.dp)
                                        .background(CxPalette.surfaceSecondary, RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(courseSubjectIcon(course.courseName), null, tint = CxPalette.accent)
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = course.courseName,
                                        style = CxTypography.headline.copy(fontWeight = FontWeight.Bold),
                                        color = CxPalette.ink,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    CxFlowRow(horizontalSpacing = 8.dp, verticalSpacing = 4.dp) {
                                        Text(
                                            text = course.teacherName.takeIf { it.isNotBlank() }
                                                ?.let { "任课教师：$it" } ?: "未提供教师信息",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = CxPalette.inkSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (boundLocation != null) {
                                            CxBadge(text = boundLocation.name, tone = CxTone.SAGE, icon = Icons.Rounded.Place)
                                        }
                                    }
                                }
                                val pendingWorks = overviewState?.pendingWorks(course.id) ?: 0
                                val pendingExams = overviewState?.pendingExams(course.id) ?: 0
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    if (overviewState != null && course.id in overviewState.loadingCourseKeys) {
                                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                    } else {
                                        // 作业与考试统一用醒目徽章，避免作业角标是灰色小字而像「消失」。
                                        if (pendingWorks > 0) {
                                            CxBadge(text = "$pendingWorks 项作业", tone = CxTone.ACCENT)
                                        }
                                        if (pendingExams > 0) {
                                            CxBadge(text = "$pendingExams 项考试", tone = CxTone.CINNABAR)
                                        }
                                    }
                                }
                                Icon(
                                    Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                                    tint = CxPalette.inkTertiary, modifier = Modifier.size(16.dp),
                                )
                            }
                            if (!last) HorizontalDivider(color = CxPalette.border, thickness = Dp.Hairline)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingHomeworkEntry(
    count: Int?,
    overdueCount: Int?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = CxPalette.surfaceSecondary,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 73.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(38.dp).background(CxPalette.accent, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Description, null, tint = CxPalette.onAccent, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    "作业待办", style = CxTypography.headline.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = CxPalette.ink,
                )
                if (count != null) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = count.toString(),
                            style = CxTypography.title.copy(fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
                            color = CxPalette.accent,
                        )
                        Text(
                            text = "项待处理",
                            style = MaterialTheme.typography.bodySmall,
                            color = CxPalette.inkSecondary,
                            modifier = Modifier.padding(bottom = 5.dp),
                        )
                    }
                } else {
                    Text(
                        text = "查看未完成作业",
                        style = MaterialTheme.typography.bodySmall,
                        color = CxPalette.inkSecondary,
                    )
                }
                if (overdueCount != null && overdueCount > 0) {
                    Text(
                        text = "$overdueCount 项逾期",
                        style = MaterialTheme.typography.bodySmall,
                        color = CxPalette.cinnabar,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                tint = CxPalette.inkTertiary, modifier = Modifier.size(16.dp),
            )
        }
    }
}

internal fun courseSubjectIcon(name: String): ImageVector = when {
    "数学" in name || "代数" in name || "概率" in name || "统计" in name -> Icons.Outlined.Calculate
    "程序" in name || "编程" in name || "计算机" in name -> Icons.Outlined.Code
    "英语" in name || "外语" in name || "翻译" in name -> Icons.Outlined.Translate
    "物理" in name || "化学" in name || "实验" in name -> Icons.Outlined.Science
    else -> Icons.AutoMirrored.Outlined.MenuBook
}
