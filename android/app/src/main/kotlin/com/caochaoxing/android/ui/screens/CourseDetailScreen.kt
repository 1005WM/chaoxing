// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

@file:OptIn(ExperimentalMaterial3Api::class)

package com.caochaoxing.android.ui.screens

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.caochaoxing.android.state.AssessmentController
import com.caochaoxing.android.state.CourseStudyController
import com.caochaoxing.android.ui.components.CxBadge
import com.caochaoxing.android.ui.components.CxFlowRow
import com.caochaoxing.android.ui.components.CxIconTile
import com.caochaoxing.android.ui.components.CxSectionHeader
import com.caochaoxing.android.ui.components.CxTone
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.model.ChaoxingSignActivity
import kotlinx.coroutines.launch

const val COURSE_SUBTAB_SIGN = 0
const val COURSE_SUBTAB_STUDY = 1
const val COURSE_SUBTAB_ASSESSMENT = 2

/**
 * Course detail screen cleanly separating sign-in activities and legal course video/document study.
 */
@Composable
fun CourseDetailScreen(
    appState: AndroidAppState,
    courseKey: String,
    onBack: () -> Unit,
    onActivityClick: (ChaoxingSignActivity) -> Unit,
    modifier: Modifier = Modifier,
    courseStudyController: CourseStudyController? = null,
    initialSubTab: Int = COURSE_SUBTAB_SIGN,
) {
    val uiState by appState.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appScope = rememberAppPersistenceScope()
    val resolvedStudyController = remember(courseStudyController, context, appState, appScope) {
        courseStudyController
            ?: (context.applicationContext as? CaochaoxingApp)?.courseStudyController
            ?: CourseStudyController(appState = appState, scope = appScope)
    }
    val studyState by resolvedStudyController.uiState.collectAsStateWithLifecycle()
    val resolvedAssessmentController = remember(appState, context, appScope) {
        (context.applicationContext as? CaochaoxingApp)?.assessmentController
            ?: AssessmentController(appState = appState, scope = appScope)
    }
    val assessmentState by resolvedAssessmentController.uiState.collectAsStateWithLifecycle()

    val course = remember(uiState.courses, courseKey) {
        uiState.courses.firstOrNull { it.id == courseKey }
    }
    val activities = uiState.courseActivities[courseKey].orEmpty()
    val orderedActivities = remember(activities) {
        activities.sortedByDescending { it.isAvailable && !it.isAlreadySignedByStatus }
    }
    val pendingCount = activities.count { it.isAvailable && !it.isAlreadySignedByStatus }
    val loaded = courseKey in uiState.courseActivities
    val failed = courseKey in uiState.failedCourseActivityIds
    val isLoading = courseKey in uiState.loadingCourseIds
    val isLoadingChapters = courseKey in studyState.loadingChapterCourseIds
    var isRefreshing by remember { mutableStateOf(false) }
    var selectedSubTab by rememberSaveable(courseKey) {
        mutableIntStateOf(initialSubTab.coerceIn(COURSE_SUBTAB_SIGN, COURSE_SUBTAB_ASSESSMENT))
    }
    var expandedChapterKeys by remember(courseKey, uiState.activePhoneNumber) {
        mutableStateOf(emptySet<String>())
    }
    var showStudyLogs by rememberSaveable(courseKey) { mutableStateOf(false) }

    LaunchedEffect(course?.id, uiState.activePhoneNumber) {
        val target = course ?: return@LaunchedEffect
        appState.loadCourseActivities(target)
    }

    LaunchedEffect(selectedSubTab, course?.id, uiState.activePhoneNumber) {
        val target = course ?: return@LaunchedEffect
        if (selectedSubTab == COURSE_SUBTAB_STUDY) {
            resolvedStudyController.loadChapters(target)
        }
        if (selectedSubTab == COURSE_SUBTAB_ASSESSMENT) {
            resolvedAssessmentController.load(target)
        }
    }

    fun triggerRefresh() {
        val target = course ?: return
        if (isRefreshing) return
        if (selectedSubTab == COURSE_SUBTAB_STUDY) {
            resolvedStudyController.loadChapters(target, forceRefresh = true)
            return
        }
        if (selectedSubTab == COURSE_SUBTAB_ASSESSMENT) {
            resolvedAssessmentController.load(target, forceRefresh = true)
            return
        }
        appScope.launch {
            isRefreshing = true
            try {
                appState.loadCourseActivities(target)
            } finally {
                isRefreshing = false
            }
        }
    }

    val isStarred = course != null && course.courseId in uiState.starredCourseIds

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = CxPalette.canvas,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "课程详情",
                        style = CxTypography.title,
                        color = CxPalette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CxPalette.canvas,
                    titleContentColor = CxPalette.ink,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    if (course != null) {
                        IconButton(
                            onClick = { appScope.launch { appState.toggleStarCourse(course.courseId) } },
                        ) {
                            Icon(
                                imageVector = if (isStarred) {
                                    Icons.Rounded.Bookmark
                                } else {
                                    Icons.Rounded.BookmarkBorder
                                },
                                contentDescription = if (isStarred) "取消常用" else "标为常用",
                                tint = if (isStarred) CxPalette.amber else CxPalette.inkSecondary,
                            )
                        }
                        IconButton(
                            onClick = { triggerRefresh() },
                            enabled = !isRefreshing && !when (selectedSubTab) {
                                COURSE_SUBTAB_SIGN -> isLoading
                                COURSE_SUBTAB_STUDY -> isLoadingChapters
                                else -> assessmentState.isLoading
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = when (selectedSubTab) {
                                    COURSE_SUBTAB_SIGN -> "刷新活动"
                                    COURSE_SUBTAB_STUDY -> "刷新章节"
                                    else -> "刷新作业与考试"
                                },
                                tint = CxPalette.accent,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing ||
                (selectedSubTab == COURSE_SUBTAB_STUDY && isLoadingChapters) ||
                (selectedSubTab == COURSE_SUBTAB_ASSESSMENT && assessmentState.isLoading),
            onRefresh = { triggerRefresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (course != null) {
                    val boundLocation = uiState.boundLocation(course.courseId)
                    item(key = "course_summary") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(course.courseName, style = CxTypography.brandDisplay, color = CxPalette.ink)
                            CxFlowRow(horizontalSpacing = 10.dp, verticalSpacing = 4.dp) {
                                val metadata = listOfNotNull(
                                    course.teacherName.takeIf { it.isNotBlank() },
                                    course.schools?.takeIf { it.isNotBlank() },
                                    "班级 ${course.classId}",
                                )
                                Text(
                                    metadata.joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CxPalette.inkSecondary,
                                )
                                if (boundLocation != null) {
                                    CxBadge(text = boundLocation.name, tone = CxTone.SAGE, icon = Icons.Rounded.Place)
                                }
                            }
                        }
                    }

                    item(key = "course_mode_tabs") {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                        ) {
                            SegmentedButton(
                                selected = selectedSubTab == COURSE_SUBTAB_SIGN,
                                onClick = { selectedSubTab = COURSE_SUBTAB_SIGN },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = CxPalette.accent.copy(alpha = 0.14f),
                                    activeContentColor = CxPalette.accent,
                                    inactiveContainerColor = CxPalette.surface,
                                    inactiveContentColor = CxPalette.inkSecondary,
                                    activeBorderColor = CxPalette.accent.copy(alpha = 0.4f),
                                    inactiveBorderColor = CxPalette.border,
                                ),
                                icon = {
                                    Icon(
                                        imageVector = Icons.Rounded.HowToReg,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                label = {
                                    Text(
                                        text = if (pendingCount > 0) "签到 ($pendingCount)" else "签到",
                                        maxLines = 1,
                                    )
                                },
                            )
                            SegmentedButton(
                                selected = selectedSubTab == COURSE_SUBTAB_STUDY,
                                onClick = { selectedSubTab = COURSE_SUBTAB_STUDY },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = CxPalette.accent.copy(alpha = 0.14f),
                                    activeContentColor = CxPalette.accent,
                                    inactiveContainerColor = CxPalette.surface,
                                    inactiveContentColor = CxPalette.inkSecondary,
                                    activeBorderColor = CxPalette.accent.copy(alpha = 0.4f),
                                    inactiveBorderColor = CxPalette.border,
                                ),
                                icon = {
                                    Icon(
                                        imageVector = Icons.Rounded.OndemandVideo,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                label = {
                                    Text(
                                        text = if (studyState.isWatching) {
                                            "看课 (${studyState.activeWorkers.size}路)"
                                        } else {
                                            "看课"
                                        },
                                        maxLines = 1,
                                    )
                                },
                            )
                            SegmentedButton(
                                selected = selectedSubTab == COURSE_SUBTAB_ASSESSMENT,
                                onClick = { selectedSubTab = COURSE_SUBTAB_ASSESSMENT },
                                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = CxPalette.accent.copy(alpha = 0.14f),
                                    activeContentColor = CxPalette.accent,
                                    inactiveContainerColor = CxPalette.surface,
                                    inactiveContentColor = CxPalette.inkSecondary,
                                    activeBorderColor = CxPalette.accent.copy(alpha = 0.4f),
                                    inactiveBorderColor = CxPalette.border,
                                ),
                                icon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.Assignment,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                label = {
                                    val pending = assessmentState.summary?.pendingCount ?: 0
                                    Text(
                                        text = if (pending > 0) "作业/考试 ($pending)" else "作业/考试",
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                }

                if (course == null) {
                    item { ActivityListNotice("无法找到此课程", "返回列表", onBack) }
                } else if (selectedSubTab == COURSE_SUBTAB_STUDY) {
                    courseStudyItems(
                        course = course,
                        allCourses = uiState.courses,
                        studyState = studyState,
                        controller = resolvedStudyController,
                        expandedChapterKeys = expandedChapterKeys,
                        onToggleChapter = { chapter ->
                            val chKey = CourseStudyController.chapterKey(chapter)
                            if (chKey in expandedChapterKeys) {
                                expandedChapterKeys = expandedChapterKeys - chKey
                            } else {
                                expandedChapterKeys = expandedChapterKeys + chKey
                                resolvedStudyController.loadChapterContent(course, chapter)
                            }
                        },
                        showLogs = showStudyLogs,
                        onToggleLogs = { showStudyLogs = !showStudyLogs },
                    )
                } else if (selectedSubTab == COURSE_SUBTAB_ASSESSMENT) {
                    courseAssessmentItems(
                        course = course,
                        state = assessmentState,
                        onRetry = { resolvedAssessmentController.load(course, forceRefresh = true) },
                    )
                } else {
                    item(key = "activities_header") {
                        CxSectionHeader(
                            title = "签到活动",
                            trailing = when {
                                !loaded || failed -> null
                                pendingCount > 0 -> "${activities.size} 项 · ${pendingCount} 项待签到"
                                else -> "${activities.size} 项"
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    if (failed) {
                        item {
                            ActivityListNotice(
                                title = if (activities.isEmpty()) "活动加载失败" else "刷新失败，正在显示上次的活动",
                                action = "重试",
                                onClick = ::triggerRefresh,
                            )
                        }
                    } else if (activities.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (!loaded || isLoading || isRefreshing) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    Text("正在加载活动…", color = CxPalette.inkSecondary)
                                } else {
                                    Text("这门课暂无签到活动", style = CxTypography.headline, color = CxPalette.ink)
                                    Text("下拉可刷新", style = MaterialTheme.typography.bodySmall, color = CxPalette.inkSecondary)
                                }
                            }
                        }
                    }
                    if (activities.isNotEmpty()) {
                        itemsIndexed(
                            items = orderedActivities,
                            key = { index, activity -> "${activity.id}_$index" },
                        ) { _, activity ->
                            val itemTone = when {
                                activity.isAlreadySignedByStatus -> CxTone.SAGE
                                activity.isAvailable -> CxTone.ACCENT
                                else -> CxTone.NEUTRAL
                            }

                            Column(modifier = Modifier.fillMaxWidth().clickable { onActivityClick(activity) }) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CxIconTile(
                                        icon = chaoxingSignTypeIcon(activity.signType),
                                        tone = itemTone,
                                        size = 36.dp,
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = activity.nameOne.ifBlank { "签到活动" },
                                            style = CxTypography.headline,
                                            color = CxPalette.ink,
                                        )
                                        CxFlowRow(horizontalSpacing = 6.dp, verticalSpacing = 2.dp) {
                                            Text(
                                                text = activity.signType.displayName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = CxPalette.inkSecondary,
                                            )
                                            if (activity.nameFour.isNotBlank()) {
                                                Text(
                                                    text = "· ${activity.nameFour}",
                                                    style = CxTypography.mono(11.sp),
                                                    color = CxPalette.inkTertiary,
                                                )
                                            }
                                        }
                                    }
                                    ActivityStatusBadge(activity)
                                }
                                HorizontalDivider(color = CxPalette.border, thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityStatusBadge(activity: ChaoxingSignActivity) {
    when {
        activity.isAlreadySignedByStatus -> {
            CxBadge(
                text = "已签到",
                tone = CxTone.SAGE,
                icon = Icons.Rounded.Check,
            )
        }
        activity.isAvailable -> {
            CxBadge(
                text = "进行中",
                tone = CxTone.ACCENT,
                showDot = true,
            )
        }
        else -> {
            CxBadge(
                text = "已结束",
                tone = CxTone.NEUTRAL,
            )
        }
    }
}

@Composable
internal fun ActivityListNotice(title: String, action: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = CxTypography.headline, color = CxPalette.ink)
        androidx.compose.material3.TextButton(onClick = onClick) { Text(action) }
    }
}
