// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

@file:OptIn(ExperimentalMaterial3Api::class)

package com.caochaoxing.android.ui.screens

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.ui.components.CxBadge
import com.caochaoxing.android.ui.components.CxFlowRow
import com.caochaoxing.android.ui.components.CxIconTile
import com.caochaoxing.android.ui.components.CxSectionHeader
import com.caochaoxing.android.ui.components.CxSurfaceCard
import com.caochaoxing.android.ui.components.CxTone
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.model.ChaoxingGroupSignActivity
import com.caochaoxing.core.model.ChaoxingIMGroup
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.service.ChaoxingActivityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Easemob IM group list screen mirroring `IOSGroupListView`.
 */
@Composable
fun GroupListScreen(
    appState: AndroidAppState,
    onGroupClick: (ChaoxingIMGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by appState.uiState.collectAsStateWithLifecycle()
    val appScope = rememberAppPersistenceScope()
    var hasRequestedInitialLoad by rememberSaveable(uiState.activePhoneNumber) {
        mutableStateOf(false)
    }
    var isRefreshing by remember(uiState.activePhoneNumber) { mutableStateOf(false) }

    LaunchedEffect(uiState.activePhoneNumber) {
        if (!hasRequestedInitialLoad) {
            hasRequestedInitialLoad = true
            if (uiState.groups.isEmpty()) {
                appState.refreshGroups()
            }
        }
    }

    fun triggerRefresh() {
        if (isRefreshing) return
        appScope.launch {
            isRefreshing = true
            try {
                appState.refreshGroups()
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
                    Text(
                        text = "群聊",
                        style = CxTypography.title,
                        color = CxPalette.ink,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CxPalette.canvas,
                    titleContentColor = CxPalette.ink,
                ),
                actions = {
                    IconButton(
                        onClick = { triggerRefresh() },
                        enabled = !isRefreshing && !uiState.isLoading,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "刷新群聊",
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
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "groups_header") {
                    CxSectionHeader(
                        title = "我的群聊",
                        trailing = "${uiState.groups.size} 个",
                    )
                }

                if (uiState.groups.isEmpty()) {
                    item(key = "groups_empty") {
                        CxSurfaceCard(
                            modifier = Modifier.fillMaxWidth(),
                            showBorder = false,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (uiState.isLoading || isRefreshing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        strokeWidth = 2.5.dp,
                                        color = CxPalette.accent,
                                    )
                                    Text(
                                        text = "正在同步群聊…",
                                        style = CxTypography.headline,
                                        color = CxPalette.ink,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Forum,
                                        contentDescription = null,
                                        tint = CxPalette.inkTertiary,
                                        modifier = Modifier.size(30.dp),
                                    )
                                    Text(
                                        text = "暂无群聊会话",
                                        style = CxTypography.headline,
                                        color = CxPalette.ink,
                                    )
                                }
                                Text(
                                    text = "下拉刷新可拉取学习通群聊中的签到通知",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CxPalette.inkSecondary,
                                )
                            }
                        }
                    }
                } else {
                    items(
                        items = uiState.groups,
                        key = { it.id },
                    ) { group ->
                        CxSurfaceCard(
                            modifier = Modifier.fillMaxWidth(),
                            showBorder = false,
                            onClick = { onGroupClick(group) },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CxIconTile(
                                    icon = Icons.Rounded.Groups,
                                    tone = CxTone.ACCENT,
                                    size = 36.dp,
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        text = group.name,
                                        style = CxTypography.headline,
                                        color = CxPalette.ink,
                                    )
                                    Text(
                                        text = "群号 ${group.id}",
                                        style = CxTypography.mono(11.sp),
                                        color = CxPalette.inkTertiary,
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = CxPalette.inkTertiary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Group check-in message parser and activity launcher mirroring `IOSGroupDetailView`.
 */
@Composable
fun GroupDetailScreen(
    appState: AndroidAppState,
    groupId: String,
    onBack: () -> Unit,
    onActivityClick: (ChaoxingSignActivity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by appState.uiState.collectAsStateWithLifecycle()
    val appScope = rememberAppPersistenceScope()
    val group = remember(uiState.groups, groupId) {
        uiState.groups.firstOrNull { it.id == groupId }
    }
    val activities = uiState.groupActivities[groupId].orEmpty()
    val loaded = groupId in uiState.groupActivities
    val failed = groupId in uiState.failedGroupActivityIds
    val isLoading = groupId in uiState.loadingGroupIds
    var isRefreshing by remember { mutableStateOf(false) }
    var openingActiveId by remember { mutableStateOf<Long?>(null) }
    var openJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            openJob?.cancel()
            openJob = null
        }
    }

    LaunchedEffect(group?.id, uiState.activePhoneNumber) {
        val target = group ?: return@LaunchedEffect
        appState.loadGroupActivities(target)
    }

    fun triggerRefresh() {
        val target = group ?: return
        if (isRefreshing) return
        appScope.launch {
            isRefreshing = true
            try {
                appState.loadGroupActivities(target)
            } finally {
                isRefreshing = false
            }
        }
    }

    fun openActivity(item: ChaoxingGroupSignActivity) {
        if (item.isUnsupportedLegacySign) return
        val client = appState.activeClient ?: return
        val token = appState.sessionToken
        openJob?.cancel()
        openingActiveId = item.activeId
        openJob = appState.launchSessionTask {
            try {
                val detail = ChaoxingActivityService.fetchSignDetail(
                    client = client,
                    activeId = item.activeId,
                    fallbackClassId = item.classId,
                    fallbackCourseId = item.courseId,
                )
                appState.checkSession(token)
                val signActivity = ChaoxingSignActivity(
                    id = item.activeId,
                    startTimeMs = item.messageTimestampMs,
                    endTimeMs = null,
                    userStatus = 0,
                    otherId = detail.signType.rawValue,
                    isLook = true,
                    type = 2,
                    activeType = 2,
                    nameOne = item.title.ifBlank { detail.title },
                    status = 1,
                    nameFour = item.startTimeTitle,
                    courseId = detail.signOutInfo.courseId,
                    classId = detail.signOutInfo.classId,
                    courseName = item.courseName,
                    extContent = "{}",
                )
                onActivityClick(signActivity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (appState.isCurrent(token)) {
                    appState.setErrorMessage(e.message ?: "无法读取群聊签到活动详情")
                }
            } finally {
                if (openingActiveId == item.activeId) {
                    openingActiveId = null
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = CxPalette.canvas,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = group?.name ?: "群聊活动",
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
                    if (group != null) {
                        IconButton(
                            onClick = { triggerRefresh() },
                            enabled = !isRefreshing && !isLoading,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "刷新群聊活动",
                                tint = CxPalette.accent,
                            )
                        }
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
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "group_activities_header") {
                    CxSectionHeader(
                        title = "签到活动",
                        trailing = if (loaded && !failed) "${activities.size} 项" else null,
                    )
                }

                if (group == null) {
                    item { ActivityListNotice("无法找到此群聊", "返回列表", onBack) }
                } else if (failed) {
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
                                Text("近期群消息中没有签到活动", style = CxTypography.headline, color = CxPalette.ink)
                                Text("下拉可刷新", style = MaterialTheme.typography.bodySmall, color = CxPalette.inkSecondary)
                            }
                        }
                    }
                }
                if (group != null && activities.isNotEmpty()) {
                    itemsIndexed(
                        items = activities,
                        key = { index, item -> "${item.activeId}_$index" },
                    ) { _, item ->
                        val isLegacy = item.isUnsupportedLegacySign
                        val isOpening = openingActiveId == item.activeId

                        CxSurfaceCard(
                            modifier = Modifier.fillMaxWidth(),
                            showBorder = false,
                            onClick = if (isLegacy) null else {
                                { openActivity(item) }
                            },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CxIconTile(
                                    icon = chaoxingSignTypeIcon(item.signType),
                                    tone = if (isLegacy) CxTone.NEUTRAL else CxTone.ACCENT,
                                    size = 36.dp,
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = item.title.ifBlank { item.activeTypeName },
                                        style = CxTypography.headline,
                                        color = if (isLegacy) CxPalette.inkTertiary else CxPalette.ink,
                                    )
                                    CxFlowRow(horizontalSpacing = 6.dp, verticalSpacing = 2.dp) {
                                        Text(
                                            text = item.courseName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = CxPalette.inkSecondary,
                                        )
                                        if (item.startTimeTitle.isNotBlank()) {
                                            Text(
                                                text = "· ${item.startTimeTitle}",
                                                style = CxTypography.mono(11.sp),
                                                color = CxPalette.inkTertiary,
                                            )
                                        }
                                    }
                                }

                                when {
                                    isLegacy -> {
                                        CxBadge(text = "旧版协议", tone = CxTone.NEUTRAL)
                                    }
                                    isOpening -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = CxPalette.accent,
                                        )
                                    }
                                    else -> {
                                        CxBadge(
                                            text = "进入",
                                            tone = CxTone.ACCENT,
                                            icon = Icons.AutoMirrored.Rounded.OpenInNew,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
