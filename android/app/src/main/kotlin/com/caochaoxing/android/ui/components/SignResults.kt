// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caochaoxing.android.state.BatchSignDisposition
import com.caochaoxing.android.state.BatchSignResult
import com.caochaoxing.android.state.signCounts
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography

@Composable
internal fun SignResultsHeader(results: List<BatchSignResult>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CxSectionHeader(title = "执行结果", trailing = "已返回 ${results.size} 个账号")
        Text(results.signCounts().description, style = MaterialTheme.typography.bodySmall, color = CxPalette.inkSecondary)
    }
}

@Composable
internal fun SignResultRow(result: BatchSignResult, trailing: @Composable () -> Unit = {}) {
    val (icon, tone) = when (result.disposition) {
        BatchSignDisposition.ACCEPTED -> Icons.Rounded.Verified to CxTone.SAGE
        BatchSignDisposition.ALREADY_SIGNED -> Icons.Rounded.History to CxTone.NEUTRAL
        BatchSignDisposition.FAILED_OR_UNCONFIRMED -> Icons.Rounded.Close to CxTone.CINNABAR
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CxIconTile(icon = icon, tone = tone, size = 30.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                result.accountName,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = CxPalette.ink,
            )
            if (result.phoneNumber != result.accountName) {
                Text(result.phoneNumber, style = CxTypography.mono(11.sp), color = CxPalette.inkTertiary)
            }
            val failed = result.disposition == BatchSignDisposition.FAILED_OR_UNCONFIRMED
            Text(
                text = if (failed && result.message.isNotBlank()) "${result.disposition.label} · ${result.message}" else result.disposition.label,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) CxPalette.cinnabar else CxPalette.inkSecondary,
            )
        }
        trailing()
    }
}
