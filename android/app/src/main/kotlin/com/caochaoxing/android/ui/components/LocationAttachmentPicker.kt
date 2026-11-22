// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.EditLocationAlt
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.core.model.ChaoxingLocationPreset

/**
 * Stateless location attachment selector for QR code, gesture, and password sign panels,
 * preserving the exact initialization and reconciliation semantics of `IOSLocationAttachmentPickerView`.
 */
@Composable
fun LocationAttachmentPicker(
    courseId: Long,
    boundPreset: ChaoxingLocationPreset?,
    presets: List<ChaoxingLocationPreset>,
    isPositionRequired: Boolean,
    includeLocation: Boolean,
    onIncludeLocationChange: (Boolean) -> Unit,
    selectedPresetId: String?,
    onSelectedPresetIdChange: (String?) -> Unit,
    onManageLocations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val availablePresets = remember(boundPreset, presets) {
        buildList {
            if (boundPreset != null) {
                add(boundPreset)
            }
            presets.filterTo(this) { it.id != boundPreset?.id }
        }
    }
    val presetIds = remember(availablePresets) {
        availablePresets.map { it.id }
    }

    val latestIncludeLocation by rememberUpdatedState(includeLocation)
    val latestSelectedPresetId by rememberUpdatedState(selectedPresetId)
    val latestOnIncludeLocationChange by rememberUpdatedState(onIncludeLocationChange)
    val latestOnSelectedPresetIdChange by rememberUpdatedState(onSelectedPresetIdChange)
    val latestAvailablePresets by rememberUpdatedState(availablePresets)
    val latestBoundPreset by rememberUpdatedState(boundPreset)

    var lastCourseId by remember { mutableStateOf<Long?>(null) }
    var lastIsPositionRequired by remember { mutableStateOf<Boolean?>(null) }
    var lastBoundPresetId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(courseId, isPositionRequired, boundPreset?.id, presetIds) {
        val currentBoundId = latestBoundPreset?.id
        val isFirstAppearance = lastCourseId == null
        val courseChanged = !isFirstAppearance && lastCourseId != courseId
        val requiredBecameTrue = isPositionRequired && lastIsPositionRequired != true
        val boundAppearedOrChanged =
            !isFirstAppearance && currentBoundId != null && currentBoundId != lastBoundPresetId

        lastCourseId = courseId
        lastIsPositionRequired = isPositionRequired
        lastBoundPresetId = currentBoundId

        if (courseChanged) {
            latestOnSelectedPresetIdChange(latestAvailablePresets.firstOrNull()?.id)
            latestOnIncludeLocationChange(isPositionRequired || latestBoundPreset != null)
            return@LaunchedEffect
        }

        if (isFirstAppearance) {
            if ((isPositionRequired || latestBoundPreset != null) && !latestIncludeLocation) {
                latestOnIncludeLocationChange(true)
            }
        } else if (requiredBecameTrue || boundAppearedOrChanged) {
            if (!latestIncludeLocation) {
                latestOnIncludeLocationChange(true)
            }
        }

        if (latestAvailablePresets.none { it.id == latestSelectedPresetId }) {
            val fallbackId = latestAvailablePresets.firstOrNull()?.id
            if (latestSelectedPresetId != fallbackId) {
                latestOnSelectedPresetIdChange(fallbackId)
            }
        }
    }

    var menuExpanded by remember { mutableStateOf(false) }
    val selectedPreset = remember(availablePresets, selectedPresetId) {
        availablePresets.firstOrNull { it.id == selectedPresetId } ?: availablePresets.firstOrNull()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val toggleLabel = if (isPositionRequired) "此活动需要附带位置" else "附带位置"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .toggleable(
                    value = includeLocation,
                    enabled = !isPositionRequired,
                    role = Role.Switch,
                    onValueChange = onIncludeLocationChange,
                )
                .padding(horizontal = 2.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = toggleLabel,
                color = if (isPositionRequired) CxPalette.inkSecondary else CxPalette.ink,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = includeLocation,
                onCheckedChange = null,
                enabled = !isPositionRequired,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CxPalette.onAccent,
                    checkedTrackColor = CxPalette.accent,
                    uncheckedThumbColor = CxPalette.inkSecondary,
                    uncheckedTrackColor = CxPalette.surfaceSecondary,
                    uncheckedBorderColor = CxPalette.border,
                    disabledCheckedThumbColor = CxPalette.onAccent.copy(alpha = 0.85f),
                    disabledCheckedTrackColor = CxPalette.accent.copy(alpha = 0.55f),
                ),
            )
        }

        if (includeLocation && availablePresets.isNotEmpty() && selectedPreset != null) {
            val selectedLabel = formatPresetDisplayName(
                preset = selectedPreset,
                boundPresetId = boundPreset?.id,
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics {
                            role = Role.DropdownList
                        },
                    shape = RoundedCornerShape(10.dp),
                    color = CxPalette.surfaceSecondary,
                    contentColor = CxPalette.ink,
                    border = BorderStroke(0.8.dp, CxPalette.border),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Place,
                            contentDescription = null,
                            tint = CxPalette.accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "地点",
                                color = CxPalette.inkSecondary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                text = selectedLabel,
                                color = CxPalette.ink,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (selectedPreset.address.isNotBlank()) {
                                Text(
                                    text = selectedPreset.address,
                                    color = CxPalette.inkTertiary,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            tint = CxPalette.inkSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = CxPalette.surface,
                ) {
                    for (preset in availablePresets) {
                        val isSelected = preset.id == selectedPreset.id
                        val itemTitle = formatPresetDisplayName(
                            preset = preset,
                            boundPresetId = boundPreset?.id,
                        )
                        DropdownMenuItem(
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = itemTitle,
                                        color = CxPalette.ink,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (isSelected) {
                                                FontWeight.SemiBold
                                            } else {
                                                FontWeight.Normal
                                            },
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (preset.address.isNotBlank()) {
                                        Text(
                                            text = preset.address,
                                            color = CxPalette.inkSecondary,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            },
                            trailingIcon = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "已选择",
                                        tint = CxPalette.accent,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = {
                                menuExpanded = false
                                onSelectedPresetIdChange(preset.id)
                            },
                        )
                    }
                }
            }
        } else if (includeLocation) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.LocationOn,
                    contentDescription = null,
                    tint = CxPalette.inkSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "请先添加一个地点，再提交。",
                    color = CxPalette.inkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        CxSecondaryButton(
            onClick = onManageLocations,
            compact = true,
        ) {
            Icon(
                imageVector = Icons.Rounded.EditLocationAlt,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "管理地点",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            )
        }
    }
}

private fun formatPresetDisplayName(
    preset: ChaoxingLocationPreset,
    boundPresetId: String?,
): String = if (preset.id == boundPresetId) {
    "${preset.name}（课程默认）"
} else {
    preset.name
}
