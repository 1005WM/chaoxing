// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

@file:OptIn(ExperimentalMaterial3Api::class)

package com.caochaoxing.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.ui.components.CxBadge
import com.caochaoxing.android.ui.components.CxFlowRow
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
import com.caochaoxing.core.model.ChaoxingAccountSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Multi-account manager supporting adding proxy sign-in accounts, switching the active perspective,
 * regenerating device codes, removing secondary accounts, and managing group tags.
 */
@Composable
fun OtherUsersScreen(
    appState: AndroidAppState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by appState.uiState.collectAsStateWithLifecycle()
    val appScope = rememberAppPersistenceScope()

    var phoneNumber by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isAdding by remember { mutableStateOf(false) }

    var batchTagText by rememberSaveable { mutableStateOf("") }
    var editingTagsAccount by remember { mutableStateOf<ChaoxingAccountSession?>(null) }
    var pendingRemoveAccount by remember { mutableStateOf<ChaoxingAccountSession?>(null) }

    fun submitAddAccount() {
        val trimmedPhone = phoneNumber.trim()
        if (isAdding || trimmedPhone.isEmpty() || password.isEmpty()) return
        isAdding = true
        val submittedPassword = password
        appScope.launch {
            try {
                appState.addOtherAccount(
                    phoneNumber = trimmedPhone,
                    password = submittedPassword,
                )
                phoneNumber = ""
                password = ""
                passwordVisible = false
                appState.setStatusMessage("已添加账号")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appState.setErrorMessage(e.message ?: "添加账号失败")
            } finally {
                isAdding = false
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
                        text = "账号管理",
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
            item(key = "add_account_header") {
                CxSectionHeader(title = "添加账号")
            }

            item(key = "add_account_card") {
                CxSurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    showBorder = true,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = phoneNumber,
                            onValueChange = { phoneNumber = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("手机号或学号") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.PersonAdd,
                                    contentDescription = null,
                                )
                            },
                            singleLine = true,
                            enabled = !isAdding,
                            colors = cxOutlinedTextFieldColors(),
                            shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Ascii,
                            ),
                        )

                        // The text field also keeps internal editing state in the saveable registry.
                        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("登录密码") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Key,
                                        contentDescription = null,
                                    )
                                },
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            imageVector = if (passwordVisible) {
                                                Icons.Rounded.VisibilityOff
                                            } else {
                                                Icons.Rounded.Visibility
                                            },
                                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                        )
                                    }
                                },
                                visualTransformation = if (passwordVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                singleLine = true,
                                enabled = !isAdding,
                                colors = cxOutlinedTextFieldColors(),
                                shape = RoundedCornerShape(10.dp),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Password,
                                ),
                            )
                        }

                        CxPrimaryButton(
                            onClick = { submitAddAccount() },
                            enabled = !isAdding && phoneNumber.trim().isNotEmpty() && password.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (isAdding) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = CxPalette.onAccent,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isAdding) "正在添加…" else "添加账号",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        }
                    }
                }
            }

            if (uiState.accounts.isNotEmpty()) {
                item(key = "tags_header") {
                    CxSectionHeader(
                        title = "账号分组",
                        trailing = "${uiState.allAccountTags.size} 个",
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                item(key = "tags_card") {
                    CxSurfaceCard(
                        modifier = Modifier.fillMaxWidth(),
                        showBorder = true,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = batchTagText,
                                    onValueChange = { batchTagText = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("分组标签名（如：302寝室 / 实验A组）") },
                                    singleLine = true,
                                    colors = cxOutlinedTextFieldColors(),
                                    shape = RoundedCornerShape(10.dp),
                                )
                                CxSecondaryButton(
                                    onClick = {
                                        val phones = uiState.accounts.map { it.phoneNumber }.toSet()
                                        val tag = batchTagText
                                        batchTagText = ""
                                        appScope.launch { appState.addTag(phones, tag) }
                                    },
                                    enabled = batchTagText.trim().isNotEmpty(),
                                    compact = true,
                                ) {
                                    Text("全员打标")
                                }
                            }

                            if (uiState.allAccountTags.isNotEmpty()) {
                                CxFlowRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                                    uiState.allAccountTags.forEach { tag ->
                                        val count = uiState.accounts.count { tag in it.tags }
                                        CxBadge(
                                            text = "$tag · ${count}人",
                                            tone = CxTone.AMBER,
                                            icon = Icons.Rounded.LocalOffer,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "accounts_header") {
                CxSectionHeader(
                    title = "已登录账号",
                    trailing = "${uiState.accounts.size} 个",
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            items(
                items = uiState.accounts,
                key = { it.phoneNumber },
            ) { account ->
                val isActive = account.phoneNumber == uiState.activePhoneNumber
                val tileTone = when {
                    isActive -> CxTone.SAGE
                    account.isPrimary -> CxTone.ACCENT
                    else -> CxTone.NEUTRAL
                }

                CxSurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    showBorder = true,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CxIconTile(
                                icon = if (account.isPrimary) {
                                    Icons.Rounded.Verified
                                } else {
                                    Icons.Rounded.Person
                                },
                                tone = tileTone,
                                size = 38.dp,
                            )

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                CxFlowRow(horizontalSpacing = 6.dp, verticalSpacing = 4.dp) {
                                    Text(
                                        text = account.name.ifBlank { account.phoneNumber },
                                        style = CxTypography.headline,
                                        color = CxPalette.ink,
                                    )
                                    if (account.isPrimary) {
                                        CxBadge(text = "主账号", tone = CxTone.ACCENT)
                                    }
                                    if (isActive) {
                                        CxBadge(text = "当前账号", tone = CxTone.SAGE, showDot = true)
                                    }
                                }
                                Text(
                                    text = account.phoneNumber,
                                    style = CxTypography.mono(11.5.sp),
                                    color = CxPalette.inkSecondary,
                                )
                                if (account.tags.isNotEmpty()) {
                                    CxFlowRow(horizontalSpacing = 5.dp, verticalSpacing = 4.dp) {
                                        account.tags.forEach { tag ->
                                            CxBadge(text = tag, tone = CxTone.AMBER)
                                        }
                                    }
                                }
                            }
                        }

                        CxHairlineDivider()

                        CxFlowRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                            CxSecondaryButton(
                                onClick = { editingTagsAccount = account },
                                compact = true,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LocalOffer,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("分组")
                            }

                            if (!isActive) {
                                CxSecondaryButton(
                                    onClick = {
                                        appScope.launch { appState.switchActiveAccount(account) }
                                    },
                                    compact = true,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.SwapHoriz,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("切换视角")
                                }
                            }

                            CxSecondaryButton(
                                onClick = {
                                    appScope.launch { appState.regenerateDeviceCode(account.phoneNumber) }
                                },
                                compact = true,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Devices,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("换设备码")
                            }

                            if (!account.isPrimary) {
                                CxSecondaryButton(
                                    onClick = { pendingRemoveAccount = account },
                                    compact = true,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = null,
                                        tint = CxPalette.cinnabar,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("移除", color = CxPalette.cinnabar)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editingTagsAccount?.let { target ->
        AccountTagEditorDialog(
            account = target,
            allTags = uiState.allAccountTags,
            onDismiss = { editingTagsAccount = null },
            onSave = { nextTags ->
                editingTagsAccount = null
                appScope.launch {
                    appState.setTags(target.phoneNumber, nextTags)
                }
            },
        )
    }

    pendingRemoveAccount?.let { target ->
        val display = target.name.ifBlank { target.phoneNumber }
        AlertDialog(
            onDismissRequest = { pendingRemoveAccount = null },
            containerColor = CxPalette.surface,
            titleContentColor = CxPalette.ink,
            textContentColor = CxPalette.inkSecondary,
            title = { Text("移除账号「$display」？") },
            text = { Text("移除后将清除该账号在本机的登录会话与设备码。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoveAccount = null
                        appScope.launch { appState.removeAccount(target) }
                    },
                ) {
                    Text(
                        text = "移除",
                        color = CxPalette.cinnabar,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveAccount = null }) {
                    Text("取消", color = CxPalette.inkSecondary)
                }
            },
        )
    }
}

@Composable
private fun AccountTagEditorDialog(
    account: ChaoxingAccountSession,
    allTags: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val tags = remember(account.phoneNumber) {
        mutableStateListOf<String>().apply { addAll(account.tags) }
    }
    var newTag by remember { mutableStateOf("") }
    val accountTitle = account.name.ifBlank { account.phoneNumber }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CxPalette.surface,
        titleContentColor = CxPalette.ink,
        textContentColor = CxPalette.ink,
        title = {
            Text(
                text = "$accountTitle · 分组",
                style = CxTypography.headline,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "当前所属分组",
                    style = MaterialTheme.typography.labelLarge,
                    color = CxPalette.inkSecondary,
                )

                if (tags.isEmpty()) {
                    CxWellBox(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "尚未分配任何分组标签",
                            style = MaterialTheme.typography.bodySmall,
                            color = CxPalette.inkSecondary,
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.forEach { tag ->
                            CxWellBox(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CxBadge(
                                        text = tag,
                                        tone = CxTone.AMBER,
                                        icon = Icons.Rounded.LocalOffer,
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    TextButton(onClick = { tags.remove(tag) }) {
                                        Text(
                                            text = "移除",
                                            color = CxPalette.cinnabar,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入分组名") },
                        singleLine = true,
                        colors = cxOutlinedTextFieldColors(),
                        shape = RoundedCornerShape(10.dp),
                    )
                    CxSecondaryButton(
                        onClick = {
                            val trimmed = newTag.trim()
                            if (trimmed.isNotEmpty() && trimmed !in tags) {
                                tags.add(trimmed)
                            }
                            newTag = ""
                        },
                        enabled = newTag.trim().isNotEmpty(),
                        compact = true,
                    ) {
                        Text("添加")
                    }
                }

                val suggestions = allTags.filter { it !in tags }
                if (suggestions.isNotEmpty()) {
                    Text(
                        text = "快速加入已有分组",
                        style = MaterialTheme.typography.labelMedium,
                        color = CxPalette.inkSecondary,
                    )
                    CxFlowRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                        suggestions.forEach { existing ->
                            CxSecondaryButton(
                                onClick = {
                                    if (existing !in tags) {
                                        tags.add(existing)
                                    }
                                },
                                compact = true,
                            ) {
                                Text("+ $existing")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(tags.toList()) }) {
                Text(
                    text = "保存",
                    color = CxPalette.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = CxPalette.inkSecondary)
            }
        },
    )
}
