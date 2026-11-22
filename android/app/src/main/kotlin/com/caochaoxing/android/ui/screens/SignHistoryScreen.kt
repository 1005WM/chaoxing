// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

@file:OptIn(ExperimentalMaterial3Api::class)

package com.caochaoxing.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.state.resultDescription
import com.caochaoxing.android.ui.components.CxSectionHeader
import com.caochaoxing.android.ui.components.CxSurfaceCard
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.model.ChaoxingSignType
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HISTORY_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

/**
 * Local sign-in execution history archive mirroring `IOSSignHistoryView`.
 */
@Composable
fun SignHistoryScreen(
    appState: AndroidAppState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by appState.uiState.collectAsStateWithLifecycle()
    val appScope = rememberAppPersistenceScope()
    var showClearConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = CxPalette.canvas,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "签到历史",
                        style = CxTypography.title,
                        color = CxPalette.ink,
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
                    if (uiState.signHistory.isNotEmpty()) {
                        TextButton(onClick = { showClearConfirmation = true }) {
                            Text(
                                text = "清空",
                                color = CxPalette.cinnabar,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.signHistory.isEmpty()) {
                item(key = "history_empty") {
                    CxSurfaceCard(
                        modifier = Modifier.fillMaxWidth(),
                        showBorder = true,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.History,
                                contentDescription = null,
                                tint = CxPalette.inkTertiary,
                                modifier = Modifier.size(32.dp),
                            )
                            Text(
                                text = "尚无本地执行记录",
                                style = CxTypography.headline,
                                color = CxPalette.ink,
                            )
                            Text(
                                text = "完成任意签到操作后，结果摘要将归档于此",
                                style = MaterialTheme.typography.bodySmall,
                                color = CxPalette.inkSecondary,
                            )
                        }
                    }
                }
            } else {
                item(key = "history_header") {
                    CxSectionHeader(
                        title = "历史归档",
                        trailing = "${uiState.signHistory.size} 条",
                    )
                }

                items(
                    items = uiState.signHistory,
                    key = { it.id },
                ) { entry ->
                    val signType = ChaoxingSignType.fromOtherId(entry.signTypeRaw)
                    val formattedTime = remember(entry.timestamp) {
                        entry.timestampInstant()
                            ?.atZone(ZoneId.systemDefault())
                            ?.let { HISTORY_TIME_FORMATTER.format(it) }
                            ?: entry.timestamp
                    }

                    CxSurfaceCard(
                        modifier = Modifier.fillMaxWidth(),
                        showBorder = true,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = entry.activityName,
                                style = CxTypography.headline,
                                color = CxPalette.ink,
                            )
                            Text(
                                text = entry.resultDescription,
                                style = MaterialTheme.typography.bodyMedium,
                                color = CxPalette.ink,
                            )

                            Text(
                                text = "${entry.courseName} · ${signType.displayName} · AID ${entry.activeId}",
                                style = CxTypography.mono(11.5.sp),
                                color = CxPalette.inkSecondary,
                            )

                            if (entry.summary.isNotBlank()) {
                                Text(
                                    text = entry.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CxPalette.inkSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            Text(
                                text = formattedTime,
                                style = CxTypography.mono(10.5.sp),
                                color = CxPalette.inkTertiary,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            containerColor = CxPalette.surface,
            titleContentColor = CxPalette.ink,
            textContentColor = CxPalette.inkSecondary,
            title = { Text("清空签到历史？") },
            text = { Text("只会清除本机记录，不影响服务器上的签到结果。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        appScope.launch { appState.clearSignHistory() }
                    },
                ) {
                    Text(
                        text = "清空历史",
                        color = CxPalette.cinnabar,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text("取消", color = CxPalette.inkSecondary)
                }
            },
        )
    }
}
