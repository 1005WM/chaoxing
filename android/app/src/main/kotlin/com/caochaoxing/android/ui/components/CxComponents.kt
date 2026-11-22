// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caochaoxing.android.ui.theme.CxColors
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography

@Composable
fun CxWordmark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.MenuBook,
            contentDescription = null,
            tint = CxPalette.accent,
            modifier = Modifier.size(22.dp),
        )
        Text(
            "Caochaoxing",
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            color = CxPalette.ink,
        )
    }
}

/**
 * Semantic mineral tone for badges, banners, icon tiles, and status pills.
 */
enum class CxTone {
    ACCENT,
    SAGE,
    AMBER,
    CINNABAR,
    PLUM,
    NEUTRAL;

    @Composable
    @ReadOnlyComposable
    fun foreground(): Color = when (this) {
        ACCENT -> CxPalette.accent
        SAGE -> CxPalette.sage
        AMBER -> CxPalette.amber
        CINNABAR -> CxPalette.cinnabar
        PLUM -> CxPalette.plum
        NEUTRAL -> CxPalette.inkSecondary
    }

    @Composable
    @ReadOnlyComposable
    fun softFill(): Color = when (this) {
        NEUTRAL -> CxPalette.surfaceSecondary
        else -> foreground().copy(alpha = 0.13f)
    }

    @Composable
    @ReadOnlyComposable
    fun hairline(): Color = when (this) {
        NEUTRAL -> CxPalette.border
        else -> foreground().copy(alpha = 0.28f)
    }

    fun foreground(colors: CxColors): Color = when (this) {
        ACCENT -> colors.accent
        SAGE -> colors.sage
        AMBER -> colors.amber
        CINNABAR -> colors.cinnabar
        PLUM -> colors.plum
        NEUTRAL -> colors.inkSecondary
    }

    fun softFill(colors: CxColors): Color = when (this) {
        NEUTRAL -> colors.surfaceSecondary
        else -> foreground(colors).copy(alpha = 0.13f)
    }

    fun hairline(colors: CxColors): Color = when (this) {
        NEUTRAL -> colors.border
        else -> foreground(colors).copy(alpha = 0.28f)
    }
}

/**
 * Compact status label with text as well as semantic tint and optional icon or dot.
 */
@Composable
fun CxBadge(
    text: String,
    tone: CxTone = CxTone.NEUTRAL,
    icon: ImageVector? = null,
    showDot: Boolean = false,
    monospaced: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val foregroundColor = tone.foreground()
    val fillColor = tone.softFill()

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(fillColor)
            .padding(horizontal = 8.dp, vertical = 3.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.5.dp),
    ) {
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(5.5.dp)
                    .clip(CircleShape)
                    .background(foregroundColor),
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = foregroundColor,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = text,
            color = foregroundColor,
            style = if (monospaced) {
                CxTypography.mono(size = 11.sp, weight = FontWeight.SemiBold)
            } else {
                MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
            },
        )
    }
}

/**
 * Quiet scalable section label with optional monospaced trailing metadata.
 */
@Composable
fun CxSectionHeader(
    title: String,
    trailing: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = CxPalette.inkSecondary,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier
                .weight(1f)
                .alignByBaseline(),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                color = CxPalette.inkTertiary,
                style = CxTypography.mono(size = 11.sp),
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

/**
 * Small semantic icon tile with soft fill and hairline border.
 */
@Composable
fun CxIconTile(
    icon: ImageVector,
    tone: CxTone = CxTone.ACCENT,
    size: Dp = 34.dp,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(tone.softFill()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tone.foreground(),
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/**
 * Primary grouped card surface matching iOS `cxCard`.
 */
@Composable
fun CxSurfaceCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    cornerRadius: Dp = 12.dp,
    showBorder: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val border = if (showBorder) BorderStroke(0.8.dp, CxPalette.border) else null

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = CxPalette.surface,
            contentColor = CxPalette.ink,
            border = border,
        ) {
            Column(
                modifier = Modifier.padding(contentPadding),
                content = content,
            )
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = CxPalette.surface,
            contentColor = CxPalette.ink,
            border = border,
        ) {
            Column(
                modifier = Modifier.padding(contentPadding),
                content = content,
            )
        }
    }
}

/**
 * Recessed secondary surface for nested details matching iOS `cxWell`.
 */
@Composable
fun CxWellBox(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(10.dp),
    cornerRadius: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = CxPalette.surfaceSecondary,
        contentColor = CxPalette.ink,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content,
        )
    }
}

/**
 * Multi-row wrapping layout for tags and filter chips using Material/Foundation `FlowRow`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CxFlowRow(
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 8.dp,
    verticalSpacing: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        content = { content() },
    )
}

/**
 * Hairline horizontal divider using semantic palette border color.
 */
@Composable
fun CxHairlineDivider(
    modifier: Modifier = Modifier,
) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 0.8.dp,
        color = CxPalette.border,
    )
}

/**
 * Primary editorial action button with a minimum 48dp touch target.
 */
@Composable
fun CxPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CxPalette.accent,
            contentColor = CxPalette.onAccent,
            disabledContainerColor = CxPalette.surfaceSecondary,
            disabledContentColor = CxPalette.inkTertiary,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp),
        content = content,
    )
}

/**
 * Secondary editorial action button with a minimum 48dp touch target.
 */
@Composable
fun CxSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CxPalette.surfaceSecondary,
            contentColor = CxPalette.accent,
            disabledContainerColor = CxPalette.surfaceSecondary,
            disabledContentColor = CxPalette.inkTertiary,
        ),
        elevation = null,
        contentPadding = PaddingValues(
            horizontal = if (compact) 10.dp else 14.dp,
            vertical = 8.dp,
        ),
        content = content,
    )
}
