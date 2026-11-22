// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.core.model.ChaoxingAccountSession

/**
 * Stateless multi-account group and individual selector matching `IOSAccountGroupSelectorView`.
 */
@Composable
fun AccountGroupSelector(
    accounts: List<ChaoxingAccountSession>,
    activePhone: String?,
    selectedPhones: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allAccountPhones = remember(accounts) {
        accounts.map { it.phoneNumber }.toSet()
    }
    val allTags = remember(accounts) {
        accounts.asSequence()
            .flatMap { it.tags.asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSortedSet()
            .toList()
    }
    val selectedCount = remember(accounts, selectedPhones) {
        accounts.count { it.phoneNumber in selectedPhones }
    }
    val hasActiveAccount = remember(accounts, activePhone) {
        !activePhone.isNullOrBlank() && accounts.any { it.phoneNumber == activePhone }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CxFlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalSpacing = 8.dp,
            verticalSpacing = 8.dp,
        ) {
            AccountFilterChip(
                title = "全部 (${accounts.size})",
                icon = Icons.Rounded.CheckCircle,
                isSelected = accounts.isNotEmpty() && selectedCount == accounts.size,
                isTag = false,
                onClick = { onSelectionChange(allAccountPhones) },
            )

            if (hasActiveAccount && activePhone != null) {
                val primarySelection = setOf(activePhone)
                AccountFilterChip(
                    title = "仅主视角",
                    icon = Icons.Rounded.Person,
                    isSelected = selectedPhones == primarySelection,
                    isTag = false,
                    onClick = { onSelectionChange(primarySelection) },
                )
            }

            for (tag in allTags) {
                key(tag) {
                    val groupMembers = remember(accounts, tag) {
                        accounts.filter { tag in it.tags }.map { it.phoneNumber }.toSet()
                    }
                    val isExactGroup = groupMembers.isNotEmpty() && selectedPhones == groupMembers
                    AccountFilterChip(
                        title = "$tag (${groupMembers.size})",
                        icon = Icons.Rounded.LocalOffer,
                        isSelected = isExactGroup,
                        isTag = true,
                        onClick = { onSelectionChange(groupMembers) },
                    )
                }
            }
        }

        CxHairlineDivider()

        if (accounts.isEmpty()) {
            Text(
                text = "暂无已登录账号",
                color = CxPalette.inkSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (account in accounts) {
                    key(account.phoneNumber) {
                        val isSelected = account.phoneNumber in selectedPhones
                        AccountSelectableRow(
                            account = account,
                            isActive = account.phoneNumber == activePhone,
                            isSelected = isSelected,
                            onToggle = {
                                val nextSelection = if (isSelected) {
                                    selectedPhones - account.phoneNumber
                                } else {
                                    selectedPhones + account.phoneNumber
                                }
                                onSelectionChange(nextSelection)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountFilterChip(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    isTag: Boolean,
    onClick: () -> Unit,
) {
    val foregroundColor = when {
        isSelected -> CxPalette.onAccent
        isTag -> CxPalette.amber
        else -> CxPalette.ink
    }
    val backgroundColor = when {
        isSelected -> CxPalette.accent
        isTag -> CxPalette.amber.copy(alpha = 0.12f)
        else -> CxPalette.surfaceSecondary
    }
    val borderColor = when {
        isSelected -> Color.Transparent
        isTag -> CxPalette.amber.copy(alpha = 0.30f)
        else -> CxPalette.border
    }
    val stateText = if (isSelected) "已选择" else "未选择"

    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .border(width = 0.8.dp, color = borderColor, shape = CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = title
                stateDescription = stateText
                selected = isSelected
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = foregroundColor,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = title,
            color = foregroundColor,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun AccountSelectableRow(
    account: ChaoxingAccountSession,
    isActive: Boolean,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    val accessibilityLabel = if (account.name.isBlank()) {
        account.phoneNumber
    } else {
        "${account.name}，${account.phoneNumber}"
    }
    val stateText = if (isSelected) "已选择" else "未选择"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityLabel
                stateDescription = stateText
                selected = isSelected
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (isSelected) {
                Icons.Rounded.CheckBox
            } else {
                Icons.Rounded.CheckBoxOutlineBlank
            },
            contentDescription = null,
            tint = if (isSelected) CxPalette.accent else CxPalette.inkTertiary,
            modifier = Modifier.size(22.dp),
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = account.name.ifBlank { account.phoneNumber },
                    color = CxPalette.ink,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                if (isActive) {
                    CxBadge(text = "当前账号", tone = CxTone.ACCENT)
                }
            }
            if (account.name.isNotBlank()) {
                Text(
                    text = account.phoneNumber,
                    color = CxPalette.inkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (account.tags.isNotEmpty()) {
                Text(
                    text = account.tags.joinToString(separator = " · "),
                    color = CxPalette.amber,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
