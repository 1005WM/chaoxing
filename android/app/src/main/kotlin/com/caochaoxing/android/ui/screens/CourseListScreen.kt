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
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
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
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.state.CourseStudyController
import com.caochaoxing.android.ui.components.CxBadge
import com.caochaoxing.android.ui.components.CxFlowRow
import com.caochaoxing.android.ui.components.CxIconTile
import com.caochaoxing.android.ui.components.CxWordmark
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
fun CourseListScreen(
    appState: AndroidAppState,
    onCourseClick: (ChaoxingCourse) -> Unit,
    onActivityClick: (ChaoxingSignActivity) -> Unit,
    onManageAccounts: () -> Unit,
    modifier: Modifier = Modifier,
    courseStudyController: CourseStudyController? = null,
) {
    val uiState by appState.uiState.collectAsStateWithLifecycle()
    val studyState = courseStudyController?.uiState?.collectAsStateWithLifecycle()?.value
    val appScope = rememberAppPersistenceScope()
    var searchText by rememberSaveable { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }

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

    fun triggerRefresh() {
        if (isRefreshing) return
        appScope.launch {
            isRefreshing = true
            try {
                appState.refreshCourses()
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
                title = { CxWordmark() },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CxPalette.canvas,
                    titleContentColor = CxPalette.ink,
                ),
                actions = {
                    uiState.activeAccount?.let { account ->
                        IconButton(onClick = onManageAccounts) {
                            Icon(
                                imageVector = Icons.Rounded.AccountCircle,
                                contentDescription = "管理账号：${account.name.ifBlank { account.phoneNumber }}",
                                tint = CxPalette.inkSecondary,
                            )
                        }
                    }
                    IconButton(
                        onClick = { triggerRefresh() },
                        enabled = !isRefreshing && !uiState.isLoading,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "刷新课程",
                            tint = CxPalette.accent,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { triggerRefresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            ) {
                item(key = "course_title") {
                    Column(
                        modifier = Modifier.padding(bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("我的课程", style = CxTypography.brandDisplay, color = CxPalette.ink)
                        if (uiState.courses.isNotEmpty()) {
                            val favorites = uiState.courses.count { it.courseId in uiState.starredCourseIds }
                            val count = "共 ${uiState.courses.size} 门课程"
                            Text(
                                if (favorites == 0) count else "$count · $favorites 门常用",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CxPalette.inkSecondary,
                            )
                        }
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
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜索课程或教师") },
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
                    item(key = "radar_card") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(CxPalette.surfaceSecondary)
                                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("待签活动", style = CxTypography.headline, color = CxPalette.ink)
                                Text(
                                    text = if (uiState.starredCourseIds.isEmpty()) "在全部课程中查找" else "仅查找常用课程",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CxPalette.inkSecondary,
                                )
                            }
                            TextButton(
                                onClick = { appScope.launch { appState.scanPendingActivitiesRadar() } },
                                enabled = !uiState.isScanningRadar,
                            ) {
                                Text(if (uiState.isScanningRadar) "查找中…" else "查找活动")
                            }
                        }
                    }

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
                    items(
                        items = filteredCourses,
                        key = { it.id },
                    ) { course ->
                        val isStarred = course.courseId in uiState.starredCourseIds
                        val boundLocation = uiState.boundLocation(course.courseId)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCourseClick(course) }
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CxIconTile(
                                icon = Icons.AutoMirrored.Rounded.MenuBook,
                                tone = if (isStarred) CxTone.ACCENT else CxTone.NEUTRAL,
                                size = 38.dp,
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = course.courseName,
                                    style = CxTypography.headline,
                                    color = CxPalette.ink,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                CxFlowRow(horizontalSpacing = 8.dp, verticalSpacing = 4.dp) {
                                    Text(
                                        text = course.teacherName.ifBlank { "未提供教师信息" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = CxPalette.inkSecondary,
                                    )
                                    if (boundLocation != null) {
                                        CxBadge(text = boundLocation.name, tone = CxTone.SAGE, icon = Icons.Rounded.Place)
                                    }
                                }
                            }
                            IconToggleButton(
                                checked = isStarred,
                                onCheckedChange = { appScope.launch { appState.toggleStarCourse(course.courseId) } },
                            ) {
                                Icon(
                                    imageVector = if (isStarred) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                    contentDescription = "常用课程：${course.courseName}",
                                    tint = if (isStarred) CxPalette.accent else CxPalette.inkTertiary,
                                )
                            }
                        }
                        if (course.id != filteredCourses.last().id) {
                            HorizontalDivider(color = CxPalette.border, thickness = Dp.Hairline)
                        }
                    }
                }
            }
        }
    }
}
