// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

@file:OptIn(ExperimentalMaterial3Api::class)

package com.caochaoxing.android.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caochaoxing.android.CaochaoxingApp
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.ui.components.CxPrimaryButton
import com.caochaoxing.android.ui.components.CxWordmark
import com.caochaoxing.android.ui.components.CxWellBox
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.model.ChaoxingClientPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

private const val MAX_SAF_BACKUP_BYTES = 8 * 1024 * 1024

@Composable
internal fun rememberAppPersistenceScope(): CoroutineScope {
    val context = LocalContext.current
    val fallbackScope = rememberCoroutineScope()
    return remember(context, fallbackScope) {
        (context.applicationContext as? CaochaoxingApp)?.applicationScope ?: fallbackScope
    }
}

internal suspend fun readSafBackupJson(context: Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            val out = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_SAF_BACKUP_BYTES) {
                    error("备份文件超过 8 MB，请检查文件内容")
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } ?: error("无法读取所选备份文件")

        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            error("备份文件不是有效的 UTF-8 文本")
        }
    }

@Composable
internal fun cxOutlinedTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = CxPalette.ink,
    unfocusedTextColor = CxPalette.ink,
    disabledTextColor = CxPalette.inkSecondary,
    focusedContainerColor = CxPalette.surface,
    unfocusedContainerColor = CxPalette.surface,
    disabledContainerColor = CxPalette.surfaceSecondary,
    focusedBorderColor = CxPalette.accent,
    unfocusedBorderColor = CxPalette.border,
    disabledBorderColor = CxPalette.border,
    focusedLabelColor = CxPalette.accent,
    unfocusedLabelColor = CxPalette.inkSecondary,
    focusedPlaceholderColor = CxPalette.inkTertiary,
    unfocusedPlaceholderColor = CxPalette.inkTertiary,
    cursorColor = CxPalette.accent,
    focusedLeadingIconColor = CxPalette.accent,
    unfocusedLeadingIconColor = CxPalette.inkTertiary,
    focusedTrailingIconColor = CxPalette.inkSecondary,
    unfocusedTrailingIconColor = CxPalette.inkTertiary,
)

/**
 * Primary account login screen mirroring `IOSLoginView`, including optional client UA preset selection.
 */
@Composable
fun LoginScreen(
    appState: AndroidAppState,
    modifier: Modifier = Modifier,
) {
    val uiState by appState.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appScope = rememberAppPersistenceScope()
    val focusManager = LocalFocusManager.current

    var phoneNumber by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoggingIn by remember { mutableStateOf(false) }
    var isImportingBackup by remember { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    var presetMenuExpanded by remember { mutableStateOf(false) }
    var customProfileMenuExpanded by remember { mutableStateOf(false) }

    val isBusy = isLoggingIn || isImportingBackup
    val canLogin = !isBusy && phoneNumber.trim().isNotEmpty() && password.isNotEmpty()

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null || isImportingBackup) return@rememberLauncherForActivityResult
        localError = null
        isImportingBackup = true
        appScope.launch {
            try {
                val rawJson = readSafBackupJson(context, uri)
                appState.importBackupJson(rawJson)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = "导入备份失败：${e.message ?: "未知错误"}"
                localError = message
                appState.setErrorMessage(message)
            } finally {
                isImportingBackup = false
            }
        }
    }

    fun submitLogin() {
        if (!canLogin) return
        focusManager.clearFocus()
        localError = null
        isLoggingIn = true
        val trimmedPhone = phoneNumber.trim()
        val submittedPassword = password
        appScope.launch {
            try {
                appState.loginPrimary(
                    phoneNumber = trimmedPhone,
                    password = submittedPassword,
                )
                password = ""
                passwordVisible = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                localError = e.message?.takeIf { it.isNotBlank() } ?: "登录失败，请检查账号或密码"
            } finally {
                isLoggingIn = false
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = CxPalette.canvas,
        contentColor = CxPalette.ink,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                CxWordmark()
                Column(
                    modifier = Modifier.padding(top = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("登录学习通", style = CxTypography.brandDisplay, color = CxPalette.ink)
                    Text(
                        "使用学习通账号查看课程与活动。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CxPalette.inkSecondary,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            label = { Text("账号") },
                            value = phoneNumber,
                            onValueChange = {
                                phoneNumber = it
                                localError = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("手机号或学号") },
                            singleLine = true,
                            enabled = !isBusy,
                            shape = RoundedCornerShape(10.dp),
                            colors = cxOutlinedTextFieldColors(),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Next,
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                            ),
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // The text field also keeps internal editing state in the saveable registry.
                        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                            OutlinedTextField(
                                label = { Text("密码") },
                                value = password,
                                onValueChange = {
                                    password = it
                                    localError = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("学习通登录密码") },
                                singleLine = true,
                                enabled = !isBusy,
                                shape = RoundedCornerShape(10.dp),
                                colors = cxOutlinedTextFieldColors(),
                                visualTransformation = if (passwordVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
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
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Go,
                                ),
                                keyboardActions = KeyboardActions(
                                    onGo = { submitLogin() },
                                ),
                            )
                        }
                    }

                    val errorText = localError
                    if (!errorText.isNullOrBlank()) {
                        CxWellBox(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = CxPalette.cinnabar,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = errorText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CxPalette.cinnabar,
                                )
                            }
                        }
                    }

                    CxPrimaryButton(
                        onClick = { submitLogin() },
                        enabled = canLogin,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isLoggingIn) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = CxPalette.onAccent,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (isLoggingIn) "正在登录…" else "登录",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .semantics { stateDescription = if (showAdvanced) "已展开" else "已收起" }
                            .clickable(role = Role.Button) { showAdvanced = !showAdvanced }
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "客户端设置",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = CxPalette.accent,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (showAdvanced) {
                                Icons.Rounded.ExpandLess
                            } else {
                                Icons.Rounded.ExpandMore
                            },
                            contentDescription = if (showAdvanced) "收起客户端设置" else "展开客户端设置",
                            tint = CxPalette.accent,
                        )
                    }

                    AnimatedVisibility(visible = showAdvanced) {
                        CxWellBox(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                ExposedDropdownMenuBox(
                                    expanded = presetMenuExpanded,
                                    onExpandedChange = { presetMenuExpanded = it },
                                ) {
                                    OutlinedTextField(
                                        value = uiState.selectedClientPreset.displayName,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("内置客户端") },
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetMenuExpanded)
                                        },
                                        colors = cxOutlinedTextFieldColors(),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                            .fillMaxWidth(),
                                    )
                                    ExposedDropdownMenu(
                                        expanded = presetMenuExpanded,
                                        onDismissRequest = { presetMenuExpanded = false },
                                        containerColor = CxPalette.surface,
                                    ) {
                                        ChaoxingClientPreset.entries.forEach { preset ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = preset.displayName,
                                                        color = CxPalette.ink,
                                                    )
                                                },
                                                onClick = {
                                                    presetMenuExpanded = false
                                                    appScope.launch {
                                                        appState.selectClientPreset(preset, persist = true)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }

                                if (uiState.customClientProfiles.isNotEmpty()) {
                                    val selectedCustomName = uiState.customClientProfiles
                                        .firstOrNull { it.id == uiState.selectedCustomClientId }
                                        ?.name
                                        ?: "使用内置客户端"

                                    ExposedDropdownMenuBox(
                                        expanded = customProfileMenuExpanded,
                                        onExpandedChange = { customProfileMenuExpanded = it },
                                    ) {
                                        OutlinedTextField(
                                            value = selectedCustomName,
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text("校园客户端") },
                                            trailingIcon = {
                                                ExposedDropdownMenuDefaults.TrailingIcon(
                                                    expanded = customProfileMenuExpanded,
                                                )
                                            },
                                            colors = cxOutlinedTextFieldColors(),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                                .fillMaxWidth(),
                                        )
                                        ExposedDropdownMenu(
                                            expanded = customProfileMenuExpanded,
                                            onDismissRequest = { customProfileMenuExpanded = false },
                                            containerColor = CxPalette.surface,
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("使用内置客户端", color = CxPalette.ink) },
                                                onClick = {
                                                    customProfileMenuExpanded = false
                                                    appScope.launch {
                                                        appState.selectCustomClientProfile(null, persist = true)
                                                    }
                                                },
                                            )
                                            uiState.customClientProfiles.forEach { profile ->
                                                DropdownMenuItem(
                                                    text = { Text(profile.name, color = CxPalette.ink) },
                                                    onClick = {
                                                        customProfileMenuExpanded = false
                                                        appScope.launch {
                                                            appState.selectCustomClientProfile(profile.id, persist = true)
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }

                                Text(
                                    text = "一般无需修改。学校使用定制客户端时可在此选择。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CxPalette.inkSecondary,
                                )
                            }
                        }
                    }
                }

                TextButton(
                    onClick = {
                        importBackupLauncher.launch(
                            arrayOf("application/json", "text/plain", "*/*"),
                        )
                    },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isImportingBackup) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = CxPalette.accent,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isImportingBackup) "正在导入备份…" else "从备份恢复")
                }

                Text(
                    text = "登录信息保存在此设备。完整备份包含账号凭据，请妥善保管。",
                    style = MaterialTheme.typography.bodySmall,
                    color = CxPalette.inkSecondary,
                )
            }
        }
    }
}
