// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.ui.screens.signers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.ui.components.CxBadge
import com.caochaoxing.android.ui.components.CxPrimaryButton
import com.caochaoxing.android.ui.components.CxSecondaryButton
import com.caochaoxing.android.ui.components.CxTone
import com.caochaoxing.android.ui.components.CxWellBox
import com.caochaoxing.android.ui.components.LocationAttachmentPicker
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.model.ChaoxingLocationPreset
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.signer.ChaoxingSignExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.hypot

private val GESTURE_GRID_SIZE = 246.dp
private val GESTURE_NODE_RADIUS = 23.dp
private val GESTURE_HALO_RADIUS = 28.dp
private val GESTURE_HIT_RADIUS = 33.dp

private fun gestureNodeCenter(node: Int, widthPx: Float, heightPx: Float): Offset {
    val index = node - 1
    val row = index / 3
    val col = index % 3
    val stepX = widthPx / 3f
    val stepY = heightPx / 3f
    return Offset(
        x = stepX * (col + 0.5f),
        y = stepY * (row + 0.5f),
    )
}

private fun hitGestureNode(
    point: Offset,
    widthPx: Float,
    heightPx: Float,
    hitRadiusPx: Float,
): Int? {
    for (node in 1..9) {
        val center = gestureNodeCenter(node, widthPx, heightPx)
        if (hypot(point.x - center.x, point.y - center.y) <= hitRadiusPx) {
            return node
        }
    }
    return null
}

/**
 * Interactive 3×3 Compose Canvas gesture pattern sign-in panel matching `IOSGestureSignView`.
 */
@Composable
fun GestureSignPanel(
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

    var selectedNodes by remember(activity.id) { mutableStateOf<List<Int>>(emptyList()) }
    var currentPoint by remember(activity.id) { mutableStateOf<Offset?>(null) }
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

    val codeString = remember(selectedNodes) {
        selectedNodes.joinToString(separator = "")
    }
    val formattedSequence = remember(selectedNodes) {
        if (selectedNodes.isEmpty()) "—" else selectedNodes.joinToString(separator = " → ")
    }

    fun verifyGestureCode(submittedCode: String) {
        val code = submittedCode.trim()
        val client = appState.activeClient
        if (code.isEmpty() || client == null) return

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
                if (verificationRequestId == requestId &&
                    selectedNodes.joinToString(separator = "").trim() == code
                ) {
                    verificationResult = result
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (appState.isCurrent(token) && verificationRequestId == requestId) {
                    appState.setErrorMessage(e.message ?: "校验手势失败")
                }
            } finally {
                if (verificationRequestId == requestId) {
                    isChecking = false
                }
            }
        }
    }

    val currentEnabled by rememberUpdatedState(enabled && !isSigning)
    val currentVerifyGesture by rememberUpdatedState(newValue = { code: String -> verifyGestureCode(code) })

    val accentColor = CxPalette.accent
    val onAccentColor = CxPalette.onAccent
    val surfaceColor = CxPalette.surface
    val borderColor = CxPalette.border
    val inkSecondaryColor = CxPalette.inkSecondary

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "按教师公布的手势轨迹滑动连接节点（编号 1–9）",
            style = MaterialTheme.typography.bodySmall,
            color = CxPalette.inkSecondary,
        )

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(GESTURE_GRID_SIZE),
                shape = RoundedCornerShape(16.dp),
                color = CxPalette.surfaceSecondary,
                border = BorderStroke(1.dp, CxPalette.border),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .pointerInput(activity.id) {
                            val hitRadiusPx = GESTURE_HIT_RADIUS.toPx()
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (!currentEnabled) return@awaitEachGesture
                                down.consume()

                                val widthPx = size.width.toFloat()
                                val heightPx = size.height.toFloat()

                                verificationJob?.cancel()
                                verificationRequestId += 1L
                                isChecking = false
                                verificationResult = null

                                val picked = mutableListOf<Int>()
                                hitGestureNode(down.position, widthPx, heightPx, hitRadiusPx)?.let { hit ->
                                    picked.add(hit)
                                }
                                selectedNodes = picked.toList()
                                currentPoint = down.position

                                var prevPos = down.position
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: event.changes.firstOrNull()
                                    if (change == null || !change.pressed) {
                                        change?.consume()
                                        break
                                    }
                                    change.consume()
                                    val nextPos = change.position
                                    currentPoint = nextPos

                                    val segDist = hypot(nextPos.x - prevPos.x, nextPos.y - prevPos.y)
                                    val steps = maxOf(1, (segDist / (hitRadiusPx * 0.4f)).toInt())
                                    var updated = false
                                    for (s in 1..steps) {
                                        val t = s.toFloat() / steps.toFloat()
                                        val sample = Offset(
                                            x = prevPos.x + (nextPos.x - prevPos.x) * t,
                                            y = prevPos.y + (nextPos.y - prevPos.y) * t,
                                        )
                                        val hit = hitGestureNode(sample, widthPx, heightPx, hitRadiusPx)
                                        if (hit != null && hit !in picked) {
                                            picked.add(hit)
                                            updated = true
                                        }
                                    }
                                    if (updated) {
                                        selectedNodes = picked.toList()
                                    }
                                    prevPos = nextPos
                                }

                                currentPoint = null
                                val finalized = picked.joinToString(separator = "")
                                if (finalized.isNotEmpty()) {
                                    currentVerifyGesture(finalized)
                                }
                            }
                        },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val widthPx = size.width
                        val heightPx = size.height
                        val nodeRadiusPx = GESTURE_NODE_RADIUS.toPx()
                        val haloRadiusPx = GESTURE_HALO_RADIUS.toPx()

                        if (selectedNodes.isNotEmpty()) {
                            val path = Path()
                            selectedNodes.forEachIndexed { index, node ->
                                val center = gestureNodeCenter(node, widthPx, heightPx)
                                if (index == 0) {
                                    path.moveTo(center.x, center.y)
                                } else {
                                    path.lineTo(center.x, center.y)
                                }
                            }
                            currentPoint?.let { live ->
                                path.lineTo(live.x, live.y)
                            }
                            drawPath(
                                path = path,
                                color = accentColor.copy(alpha = 0.85f),
                                style = Stroke(
                                    width = 4.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                ),
                            )
                        }

                        for (node in 1..9) {
                            val center = gestureNodeCenter(node, widthPx, heightPx)
                            val isSelected = node in selectedNodes
                            if (isSelected) {
                                drawCircle(
                                    color = accentColor.copy(alpha = 0.16f),
                                    radius = haloRadiusPx,
                                    center = center,
                                )
                                drawCircle(
                                    color = accentColor,
                                    radius = nodeRadiusPx,
                                    center = center,
                                )
                            } else {
                                drawCircle(
                                    color = surfaceColor,
                                    radius = nodeRadiusPx,
                                    center = center,
                                )
                                drawCircle(
                                    color = borderColor,
                                    radius = nodeRadiusPx,
                                    center = center,
                                    style = Stroke(width = 1.2.dp.toPx()),
                                )
                            }
                        }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        for (row in 0 until 3) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            ) {
                                for (col in 0 until 3) {
                                    val node = row * 3 + col + 1
                                    val isSelected = node in selectedNodes
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = node.toString(),
                                            style = CxTypography.mono(
                                                size = 13.sp,
                                                weight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            ),
                                            color = if (isSelected) onAccentColor else inkSecondaryColor,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        CxWellBox(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "已选择：$formattedSequence",
                    style = CxTypography.mono(12.sp),
                    color = CxPalette.ink,
                    modifier = Modifier.weight(1f),
                )
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = CxPalette.accent,
                    )
                } else {
                    verificationResult?.let { valid ->
                        CxBadge(
                            text = if (valid) "手势正确" else "手势不符",
                            tone = if (valid) CxTone.SAGE else CxTone.CINNABAR,
                            icon = if (valid) Icons.Rounded.Check else Icons.Rounded.Close,
                        )
                    }
                }
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CxSecondaryButton(
                onClick = {
                    verificationJob?.cancel()
                    verificationRequestId += 1L
                    isChecking = false
                    selectedNodes = emptyList()
                    currentPoint = null
                    verificationResult = null
                },
                enabled = enabled && !isSigning,
            ) {
                Text("清空")
            }

            CxPrimaryButton(
                onClick = {
                    if (isPositionRequired && activeLocationPreset == null) {
                        appState.setErrorMessage("该活动要求位置，请先选择有效预设")
                        return@CxPrimaryButton
                    }
                    onSubmit(codeString, activeLocationPreset)
                },
                enabled = enabled && !isSigning && codeString.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Gesture,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "提交手势签到",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}
