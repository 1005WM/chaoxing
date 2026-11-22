// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.ui.screens.signers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.ui.components.CxBadge
import com.caochaoxing.android.ui.components.CxPrimaryButton
import com.caochaoxing.android.ui.components.CxSecondaryButton
import com.caochaoxing.android.ui.components.CxTone
import com.caochaoxing.android.ui.components.LocationAttachmentPicker
import com.caochaoxing.android.ui.screens.cxOutlinedTextFieldColors
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.model.ChaoxingLocationPreset
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.signer.ChaoxingSignExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Classroom password/code check-in panel with optional pre-verification via
 * [ChaoxingSignExecutor.checkSignCode] and location attachment matching `IOSPasswordSignView`.
 */
@Composable
fun PasswordSignPanel(
    appState: AndroidAppState,
    activity: ChaoxingSignActivity,
    isPositionRequired: Boolean,
    isSigning: Boolean,
    enabled: Boolean = true,
    onManageLocations: () -> Unit,
    onSubmit: (String, ChaoxingLocationPreset?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by appState.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var signCode by remember(activity.id) { mutableStateOf("") }
    var verificationResult by remember(activity.id) { mutableStateOf<Boolean?>(null) }
    var isChecking by remember(activity.id) { mutableStateOf(false) }
    var verificationJob by remember(activity.id) { mutableStateOf<Job?>(null) }
    var verificationRequestId by remember(activity.id) { mutableLongStateOf(0L) }

    var includeLocation by remember(activity.id) { mutableStateOf(isPositionRequired) }
    var selectedPresetId by remember(activity.id) { mutableStateOf<String?>(null) }

    DisposableEffect(activity.id) {
        onDispose {
            verificationJob?.cancel()
            verificationJob = null
        }
    }

    val boundPreset = remember(activity.courseId, uiState.courseLocationBindings) {
        uiState.boundLocation(activity.courseId)
    }

    LaunchedEffect(uiState.locationPresets, boundPreset) {
        if (selectedPresetId == null) {
            selectedPresetId = boundPreset?.id ?: uiState.locationPresets.firstOrNull()?.id
        }
    }

    val activeLocationPreset = remember(
        includeLocation,
        isPositionRequired,
        boundPreset,
        uiState.locationPresets,
        selectedPresetId,
    ) {
        if (!includeLocation && !isPositionRequired) {
            null
        } else {
            uiState.locationPresets.firstOrNull { it.id == selectedPresetId }
                ?: boundPreset?.takeIf { it.id == selectedPresetId }
                ?: boundPreset
                ?: uiState.locationPresets.firstOrNull()
        }
    }

    fun verifyCode() {
        val code = signCode.trim()
        val client = appState.activeClient
        if (code.isEmpty() || client == null || !enabled) return

        verificationJob?.cancel()
        val requestId = ++verificationRequestId
        val token = appState.sessionToken
        isChecking = true

        verificationJob = scope.launch {
            try {
                val result = ChaoxingSignExecutor.checkSignCode(
                    client = client,
                    activeId = activity.id,
                    signCode = code,
                )
                appState.checkSession(token)
                if (verificationRequestId == requestId && signCode.trim() == code) {
                    verificationResult = result
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (appState.isCurrent(token) && verificationRequestId == requestId) {
                    appState.setErrorMessage(e.message ?: "预校验签到码失败")
                }
            } finally {
                if (verificationRequestId == requestId) {
                    isChecking = false
                }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = signCode,
                onValueChange = { next ->
                    signCode = next
                    verificationJob?.cancel()
                    verificationRequestId += 1L
                    isChecking = false
                    verificationResult = null
                },
                modifier = Modifier.weight(1f),
                label = { Text("签到码") },
                placeholder = { Text("输入 4–8 位课堂签到码") },
                enabled = enabled,
                singleLine = true,
                textStyle = CxTypography.mono(15.sp, weight = FontWeight.SemiBold),
                colors = cxOutlinedTextFieldColors(),
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { verifyCode() },
                ),
            )

            CxSecondaryButton(
                onClick = { verifyCode() },
                enabled = enabled && !isChecking && signCode.trim().isNotEmpty(),
            ) {
                Text("预校验")
            }
        }

        if (isChecking) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = CxPalette.accent,
                )
                Text(
                    text = "正在核验签到码...",
                    style = MaterialTheme.typography.bodySmall,
                    color = CxPalette.inkSecondary,
                )
            }
        } else {
            verificationResult?.let { valid ->
                CxBadge(
                    text = if (valid) "签到码核验通过" else "签到码有误",
                    tone = if (valid) CxTone.SAGE else CxTone.CINNABAR,
                    icon = if (valid) Icons.Rounded.Check else Icons.Rounded.Close,
                )
            }
        }

        LocationAttachmentPicker(
            courseId = activity.courseId,
            boundPreset = boundPreset,
            presets = uiState.locationPresets,
            isPositionRequired = isPositionRequired,
            includeLocation = includeLocation,
            onIncludeLocationChange = { includeLocation = it },
            selectedPresetId = selectedPresetId,
            onSelectedPresetIdChange = { selectedPresetId = it },
            onManageLocations = onManageLocations,
        )

        CxPrimaryButton(
            onClick = {
                val trimmed = signCode.trim()
                if (trimmed.isEmpty()) return@CxPrimaryButton
                if (isPositionRequired && activeLocationPreset == null) {
                    appState.setErrorMessage("该活动要求位置，请先选择有效预设")
                    return@CxPrimaryButton
                }
                onSubmit(trimmed, activeLocationPreset)
            },
            enabled = enabled && !isSigning && signCode.trim().isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Rounded.Password,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "提交签到码签到",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}
