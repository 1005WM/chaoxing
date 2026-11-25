// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

@file:OptIn(ExperimentalMaterial3Api::class)

package com.caochaoxing.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.ui.components.CxBadge
import com.caochaoxing.android.ui.components.CxHairlineDivider
import com.caochaoxing.android.ui.components.CxIconTile
import com.caochaoxing.android.ui.components.CxPrimaryButton
import com.caochaoxing.android.ui.components.CxSecondaryButton
import com.caochaoxing.android.ui.components.CxSectionHeader
import com.caochaoxing.android.ui.components.CxSurfaceCard
import com.caochaoxing.android.ui.components.CxTone
import com.caochaoxing.android.ui.components.CxWellBox
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.model.ChaoxingClientPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BATCH_DELAY_OPTIONS = listOf(
    0 to "无间隔",
    1200 to "约 1.2 秒",
    2500 to "约 2.5 秒",
    4000 to "约 4 秒",
)

/**
 * Application settings, account FID switching, backup import/export, and client UA configuration
 * mirroring `IOSSettingsView`.
 */
@Composable
fun SettingsScreen(
    appState: AndroidAppState,
    onOpenAccounts: () -> Unit,
    onOpenLocations: () -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by appState.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appScope = rememberAppPersistenceScope()

    var manualFidText by rememberSaveable { mutableStateOf("") }
    var showManualFid by rememberSaveable(uiState.activePhoneNumber) { mutableStateOf(false) }
    var includeAccountsInBackup by rememberSaveable { mutableStateOf(false) }
    var customUserAgentText by rememberSaveable { mutableStateOf(uiState.customUserAgent) }
    var lastSyncedCustomUa by rememberSaveable { mutableStateOf(uiState.customUserAgent) }
    var newProfileName by rememberSaveable { mutableStateOf("") }
    var newProfileUa by rememberSaveable { mutableStateOf("") }
    var showLogoutConfirmation by remember { mutableStateOf(false) }

    var fidDropdownExpanded by remember { mutableStateOf(false) }
    var delayDropdownExpanded by remember { mutableStateOf(false) }
    var clientPresetExpanded by remember { mutableStateOf(false) }
    var customProfileExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.customUserAgent) {
        if (uiState.customUserAgent != lastSyncedCustomUa) {
            lastSyncedCustomUa = uiState.customUserAgent
            customUserAgentText = uiState.customUserAgent
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        appScope.launch {
            try {
                val payload = appState.exportBackupJson(includeAccounts = includeAccountsInBackup)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(payload.toByteArray(Charsets.UTF_8))
                        stream.flush()
                    } ?: error("无法打开目标文件")
                }
                appState.setStatusMessage("备份已导出")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appState.setErrorMessage("保存备份失败：${e.message ?: "未知错误"}")
            }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        appScope.launch {
            try {
                val rawJson = readSafBackupJson(context, uri)
                appState.importBackupJson(rawJson)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appState.setErrorMessage("导入备份失败：${e.message ?: "未知错误"}")
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
                        text = "设置",
                        style = CxTypography.title,
                        color = CxPalette.ink,
                    )
                },
                actions = { AppUpdateButton() },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CxPalette.canvas,
                    titleContentColor = CxPalette.ink,
                ),
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
            item(key = "settings_ai") { AiSettingsCard() }
            uiState.activeAccount?.let { account ->
                item(key = "settings_account_header") {
                    CxSectionHeader(
                        title = "当前账号",
                    )
                }

                item(key = "settings_account_card") {
                    CxSurfaceCard(
                        modifier = Modifier.fillMaxWidth(),
                        showBorder = false,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CxIconTile(icon = Icons.Rounded.AccountCircle, size = 44.dp)
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = account.name.ifBlank { "未设置姓名" },
                                        style = CxTypography.headline,
                                        color = CxPalette.ink,
                                    )
                                    Text(
                                        text = if (account.phoneNumber.length == 11 && account.phoneNumber.all { it.isDigit() }) {
                                            account.phoneNumber.take(3) + "••••" + account.phoneNumber.takeLast(4)
                                        } else account.phoneNumber,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = CxPalette.inkSecondary,
                                    )
                                }
                            }

                            if (account.schools.isNotEmpty()) {
                                CxHairlineDivider()
                                val currentSchool = account.schools.firstOrNull { it.fid == account.configuredFid }
                                val schoolLabel = currentSchool?.let { "${it.name} (${it.fid})" }
                                    ?: "当前单位 (${account.configuredFid})"

                                ExposedDropdownMenuBox(
                                    expanded = fidDropdownExpanded,
                                    onExpandedChange = { fidDropdownExpanded = it },
                                ) {
                                    OutlinedTextField(
                                        value = schoolLabel,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("所属单位 (FID)") },
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = fidDropdownExpanded)
                                        },
                                        colors = cxOutlinedTextFieldColors(),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                            .fillMaxWidth(),
                                    )
                                    ExposedDropdownMenu(
                                        expanded = fidDropdownExpanded,
                                        onDismissRequest = { fidDropdownExpanded = false },
                                        containerColor = CxPalette.surface,
                                    ) {
                                        account.schools.forEach { school ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = "${school.name} (${school.fid})",
                                                        color = CxPalette.ink,
                                                    )
                                                },
                                                onClick = {
                                                    fidDropdownExpanded = false
                                                    appScope.launch {
                                                        appState.updateFid(account, school.fid)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }

                            CxHairlineDivider()

                            TextButton(
                                onClick = { showManualFid = !showManualFid },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("手动设置学校编号", modifier = Modifier.weight(1f))
                                Icon(
                                    imageVector = if (showManualFid) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = if (showManualFid) "收起" else "展开",
                                )
                            }
                            if (showManualFid) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedTextField(
                                        value = manualFidText,
                                        onValueChange = { manualFidText = it },
                                        modifier = Modifier.weight(1f),
                                        placeholder = { Text("手动指定单位 FID 编号") },
                                        singleLine = true,
                                        textStyle = CxTypography.mono(13.sp),
                                        colors = cxOutlinedTextFieldColors(),
                                        shape = RoundedCornerShape(10.dp),
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Number,
                                        ),
                                    )
                                    val parsedFid = manualFidText.trim().toIntOrNull()
                                    CxSecondaryButton(
                                        onClick = {
                                            val targetFid = parsedFid ?: return@CxSecondaryButton
                                            appScope.launch {
                                                appState.updateFid(account, targetFid)
                                                manualFidText = ""
                                            }
                                        },
                                        enabled = parsedFid != null,
                                        compact = true,
                                    ) {
                                        Text("应用")
                                    }
                                }

                            }

                            CxHairlineDivider()

                            SettingsNavRow(
                                icon = Icons.Rounded.ManageAccounts,
                                tone = CxTone.ACCENT,
                                title = "账号管理与分组",
                                badgeText = "${uiState.accounts.size} 个",
                                onClick = onOpenAccounts,
                            )
                        }
                    }
                }
            }

            item(key = "settings_sign_header") {
                CxSectionHeader(
                    title = "签到选项",
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            item(key = "settings_sign_card") {
                CxSurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    showBorder = false,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsSwitchRow(
                            title = "位置微调（约 ±5 米）",
                            checked = uiState.enableLocationJitter,
                            onCheckedChange = { checked ->
                                appScope.launch { appState.setLocationJitterEnabled(checked) }
                            },
                        )

                        CxHairlineDivider()

                        val delayLabel = BATCH_DELAY_OPTIONS
                            .firstOrNull { it.first == uiState.batchSignDelayMs }
                            ?.second
                            ?: "${uiState.batchSignDelayMs} ms"

                        ExposedDropdownMenuBox(
                            expanded = delayDropdownExpanded,
                            onExpandedChange = { delayDropdownExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = delayLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("多账号错峰间隔") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = delayDropdownExpanded)
                                },
                                colors = cxOutlinedTextFieldColors(),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth(),
                            )
                            ExposedDropdownMenu(
                                expanded = delayDropdownExpanded,
                                onDismissRequest = { delayDropdownExpanded = false },
                                containerColor = CxPalette.surface,
                            ) {
                                BATCH_DELAY_OPTIONS.forEach { (delayMs, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label, color = CxPalette.ink) },
                                        onClick = {
                                            delayDropdownExpanded = false
                                            appScope.launch { appState.setBatchSignDelayMs(delayMs) }
                                        },
                                    )
                                }
                            }
                        }

                        CxHairlineDivider()

                        SettingsSwitchRow(
                            title = "上传前轻微处理图片",
                            checked = uiState.enableImageObfuscation,
                            onCheckedChange = { checked ->
                                appScope.launch { appState.setImageObfuscationEnabled(checked) }
                            },
                        )

                        CxHairlineDivider()

                        SettingsSwitchRow(
                            title = "自动识别滑块位置",
                            checked = uiState.autoSolveCaptcha,
                            onCheckedChange = { checked ->
                                appScope.launch { appState.setAutoSolveCaptcha(checked) }
                            },
                        )
                    }
                }
            }

            item(key = "settings_tools_header") {
                CxSectionHeader(
                    title = "历史与快捷入口",
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            item(key = "settings_tools_card") {
                CxSurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    showBorder = false,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsNavRow(
                            icon = Icons.Rounded.Place,
                            tone = CxTone.SAGE,
                            title = "管理地点",
                            badgeText = "${uiState.locationPresets.size} 处",
                            onClick = onOpenLocations,
                        )

                        CxHairlineDivider()

                        SettingsNavRow(
                            icon = Icons.Rounded.History,
                            tone = CxTone.AMBER,
                            title = "签到历史",
                            badgeText = "${uiState.signHistory.size} 条",
                            onClick = onOpenHistory,
                        )

                        CxHairlineDivider()

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "外部 URL Scheme 协议入口",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = CxPalette.inkSecondary,
                            )
                            CxWellBox(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "caochaoxing://sign?activeId=12345",
                                        style = CxTypography.mono(11.5.sp),
                                        color = CxPalette.ink,
                                    )
                                    Text(
                                        text = "caochaoxing://qr?data=SIGNIN%3A...",
                                        style = CxTypography.mono(11.5.sp),
                                        color = CxPalette.ink,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "settings_backup_header") {
                CxSectionHeader(
                    title = "备份与恢复",
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            item(key = "settings_backup_card") {
                CxSurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    showBorder = false,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsSwitchRow(
                            title = "备份时包含账号登录凭据",
                            checked = includeAccountsInBackup,
                            onCheckedChange = { includeAccountsInBackup = it },
                        )

                        Text(
                            text = (if (includeAccountsInBackup) {
                                "完整备份包含可用于登录的凭据和签到历史的详细摘要。"
                            } else {
                                "默认排除账号登录信息，并清空可能含姓名或手机号的历史摘要。"
                            }) + "备份仍包含地点名称、地址、坐标、课程地点绑定，以及签到历史中的课程、活动、时间和统计。" +
                                "文件以明文 JSON 保存，仅供自己保管。请勿将备份作为反馈附件上传。",
                            style = MaterialTheme.typography.bodySmall,
                            color = CxPalette.ink,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CxPrimaryButton(
                                onClick = {
                                    val filename = if (includeAccountsInBackup) {
                                        "caochaoxing_full_backup.json"
                                    } else {
                                        "caochaoxing_presets_backup.json"
                                    }
                                    exportBackupLauncher.launch(filename)
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Upload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("导出备份")
                            }

                            CxSecondaryButton(
                                onClick = {
                                    importBackupLauncher.launch(
                                        arrayOf("application/json", "text/plain", "*/*"),
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("导入备份")
                            }
                        }

                        Text(
                            text = "导入会合并备份中的账号、地点和历史，并恢复备份中的偏好设置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = CxPalette.inkSecondary,
                        )
                    }
                }
            }

            item(key = "settings_client_header") {
                CxSectionHeader(
                    title = "客户端",
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            item(key = "settings_client_card") {
                CxSurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    showBorder = false,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ExposedDropdownMenuBox(
                            expanded = clientPresetExpanded,
                            onExpandedChange = { clientPresetExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = uiState.selectedClientPreset.displayName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("内置客户端标识") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = clientPresetExpanded)
                                },
                                colors = cxOutlinedTextFieldColors(),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth(),
                            )
                            ExposedDropdownMenu(
                                expanded = clientPresetExpanded,
                                onDismissRequest = { clientPresetExpanded = false },
                                containerColor = CxPalette.surface,
                            ) {
                                ChaoxingClientPreset.entries.forEach { preset ->
                                    DropdownMenuItem(
                                        text = { Text(preset.displayName, color = CxPalette.ink) },
                                        onClick = {
                                            clientPresetExpanded = false
                                            appScope.launch {
                                                appState.selectClientPreset(preset, persist = true)
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        if (uiState.customClientProfiles.isNotEmpty()) {
                            val activeCustom = uiState.customClientProfiles
                                .firstOrNull { it.id == uiState.selectedCustomClientId }
                            val customLabel = activeCustom?.name ?: "使用上方内置客户端"

                            ExposedDropdownMenuBox(
                                expanded = customProfileExpanded,
                                onExpandedChange = { customProfileExpanded = it },
                            ) {
                                OutlinedTextField(
                                    value = customLabel,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("已保存校园定制版") },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(
                                            expanded = customProfileExpanded,
                                        )
                                    },
                                    colors = cxOutlinedTextFieldColors(),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                        .fillMaxWidth(),
                                )
                                ExposedDropdownMenu(
                                    expanded = customProfileExpanded,
                                    onDismissRequest = { customProfileExpanded = false },
                                    containerColor = CxPalette.surface,
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("使用上方内置客户端", color = CxPalette.ink) },
                                        onClick = {
                                            customProfileExpanded = false
                                            appScope.launch {
                                                appState.selectCustomClientProfile(null, persist = true)
                                            }
                                        },
                                    )
                                    uiState.customClientProfiles.forEach { profile ->
                                        DropdownMenuItem(
                                            text = { Text(profile.name, color = CxPalette.ink) },
                                            onClick = {
                                                customProfileExpanded = false
                                                appScope.launch {
                                                    appState.selectCustomClientProfile(profile.id, persist = true)
                                                }
                                            },
                                        )
                                    }
                                }
                            }

                            uiState.customClientProfiles.forEach { profile ->
                                CxWellBox(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                text = profile.name,
                                                style = MaterialTheme.typography.labelLarge.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                ),
                                                color = CxPalette.ink,
                                            )
                                            Text(
                                                text = profile.userAgent,
                                                style = CxTypography.mono(10.5.sp),
                                                color = CxPalette.inkTertiary,
                                                maxLines = 2,
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                appScope.launch {
                                                    appState.removeCustomClientProfile(profile)
                                                }
                                            },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Delete,
                                                contentDescription = "删除 ${profile.name}",
                                                tint = CxPalette.cinnabar,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        CxHairlineDivider()

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = customUserAgentText,
                                onValueChange = { customUserAgentText = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("临时自定义 User-Agent（可选）") },
                                placeholder = { Text("留空则使用当前内置客户端 UA") },
                                textStyle = CxTypography.mono(12.sp),
                                minLines = 2,
                                maxLines = 4,
                                colors = cxOutlinedTextFieldColors(),
                                shape = RoundedCornerShape(10.dp),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Ascii,
                                ),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CxSecondaryButton(
                                    onClick = {
                                        val trimmed = customUserAgentText.trim()
                                        appScope.launch {
                                            if (uiState.selectedCustomClientId != null && trimmed.isNotEmpty()) {
                                                appState.selectCustomClientProfile(null, persist = false)
                                            }
                                            appState.setCustomUserAgent(trimmed, persist = true)
                                            appState.setStatusMessage(
                                                if (trimmed.isEmpty()) "已恢复默认 User-Agent" else "已更新自定义 User-Agent",
                                            )
                                        }
                                    },
                                    enabled = customUserAgentText.trim() != uiState.customUserAgent,
                                    compact = true,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("应用自定义 UA")
                                }
                                if (uiState.customUserAgent.isNotEmpty() || customUserAgentText.isNotEmpty()) {
                                    CxSecondaryButton(
                                        onClick = {
                                            customUserAgentText = ""
                                            appScope.launch {
                                                appState.setCustomUserAgent("", persist = true)
                                            }
                                        },
                                        compact = true,
                                    ) {
                                        Text("清除")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "settings_add_client_header") {
                CxSectionHeader(
                    title = "添加校园客户端",
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            item(key = "settings_add_client_card") {
                CxSurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    showBorder = false,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = newProfileName,
                            onValueChange = { newProfileName = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("方案备注名（如：本校定制版）") },
                            singleLine = true,
                            colors = cxOutlinedTextFieldColors(),
                            shape = RoundedCornerShape(10.dp),
                        )
                        OutlinedTextField(
                            value = newProfileUa,
                            onValueChange = { newProfileUa = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("粘贴完整 User-Agent 字符串") },
                            textStyle = CxTypography.mono(12.sp),
                            minLines = 2,
                            maxLines = 4,
                            colors = cxOutlinedTextFieldColors(),
                            shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Ascii,
                            ),
                        )
                        CxSecondaryButton(
                            onClick = {
                                val name = newProfileName
                                val ua = newProfileUa
                                appScope.launch {
                                    appState.addCustomClientProfile(name, ua)
                                    newProfileName = ""
                                    newProfileUa = ""
                                }
                            },
                            enabled = newProfileName.trim().isNotEmpty() && newProfileUa.trim().isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("保存并启用该方案")
                        }
                    }
                }
            }

            item(key = "settings_logout_card") {
                CxSurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    showBorder = false,
                    onClick = { showLogoutConfirmation = true },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ExitToApp,
                            contentDescription = null,
                            tint = CxPalette.cinnabar,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "退出所有账号",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = CxPalette.cinnabar,
                        )
                    }
                }
            }
        }
    }

    if (showLogoutConfirmation) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmation = false },
            containerColor = CxPalette.surface,
            titleContentColor = CxPalette.ink,
            textContentColor = CxPalette.inkSecondary,
            title = { Text("退出所有账号？") },
            text = {
                Text("将删除本机登录会话并停止后续操作，保留偏好设置和签到历史。已发出的请求无法撤回。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirmation = false
                        appScope.launch { appState.logoutAll() }
                    },
                ) {
                    Text(
                        text = "退出所有账号",
                        color = CxPalette.cinnabar,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmation = false }) {
                    Text("取消", color = CxPalette.inkSecondary)
                }
            },
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = CxPalette.ink,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CxPalette.onAccent,
                checkedTrackColor = CxPalette.accent,
                uncheckedThumbColor = CxPalette.inkSecondary,
                uncheckedTrackColor = CxPalette.surfaceSecondary,
                uncheckedBorderColor = CxPalette.border,
            ),
        )
    }
}

@Composable
private fun SettingsNavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tone: CxTone,
    title: String,
    badgeText: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CxIconTile(icon = icon, tone = tone, size = 32.dp)
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = CxPalette.ink,
            modifier = Modifier.weight(1f),
        )
        Text(text = badgeText, style = MaterialTheme.typography.bodyMedium, color = CxPalette.inkSecondary)
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = CxPalette.inkTertiary,
        )
    }
}
