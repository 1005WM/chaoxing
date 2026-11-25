// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Gray-green surfaces, deep green actions and orange attention states.
 */
object CxPaletteSpecs {
    val canvasLight = Color(0xFFF6F7F4)
    val canvasDark = Color(0xFF16201A)

    val surfaceLight = Color(0xFFFFFFFF)
    val surfaceDark = Color(0xFF1D2A23)

    val surfaceSecondaryLight = Color(0xFFE6EDE8)
    val surfaceSecondaryDark = Color(0xFF26372D)

    val borderLight = Color(0xFFDFE7E0)
    val borderDark = Color(0xFF3A5244)

    val inkLight = Color(0xFF16201A)
    val inkDark = Color(0xFFF6F7F4)

    val inkSecondaryLight = Color(0xFF56695E)
    val inkSecondaryDark = Color(0xFFB8C9BF)

    val inkTertiaryLight = Color(0xFF607268)
    val inkTertiaryDark = Color(0xFFA2B6AA)

    val accentLight = Color(0xFF2A7A5F)
    val accentDark = Color(0xFF8BC9AE)

    val onAccentLight = Color(0xFFFFFFFF)
    val onAccentDark = Color(0xFF16201A)

    val sageLight = Color(0xFF39745D)
    val sageDark = Color(0xFFA9CDB9)

    val warningFill = Color(0xFFCE7340)
    val amberLight = Color(0xFF9A461C)
    val amberDark = Color(0xFFF1AC81)

    val cinnabarLight = Color(0xFFA14825)
    val cinnabarDark = Color(0xFFF6AB89)

    val plumLight = Color(0xFF546E60)
    val plumDark = Color(0xFFACBFB2)
}

@Immutable
data class CxColors(
    val canvas: Color,
    val surface: Color,
    val surfaceSecondary: Color,
    val border: Color,
    val ink: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val sage: Color,
    val amber: Color,
    val cinnabar: Color,
    val plum: Color,
    val isDark: Boolean,
)

val LightCxColors = CxColors(
    canvas = CxPaletteSpecs.canvasLight,
    surface = CxPaletteSpecs.surfaceLight,
    surfaceSecondary = CxPaletteSpecs.surfaceSecondaryLight,
    border = CxPaletteSpecs.borderLight,
    ink = CxPaletteSpecs.inkLight,
    inkSecondary = CxPaletteSpecs.inkSecondaryLight,
    inkTertiary = CxPaletteSpecs.inkTertiaryLight,
    accent = CxPaletteSpecs.accentLight,
    onAccent = CxPaletteSpecs.onAccentLight,
    sage = CxPaletteSpecs.sageLight,
    amber = CxPaletteSpecs.amberLight,
    cinnabar = CxPaletteSpecs.cinnabarLight,
    plum = CxPaletteSpecs.plumLight,
    isDark = false,
)

val DarkCxColors = CxColors(
    canvas = CxPaletteSpecs.canvasDark,
    surface = CxPaletteSpecs.surfaceDark,
    surfaceSecondary = CxPaletteSpecs.surfaceSecondaryDark,
    border = CxPaletteSpecs.borderDark,
    ink = CxPaletteSpecs.inkDark,
    inkSecondary = CxPaletteSpecs.inkSecondaryDark,
    inkTertiary = CxPaletteSpecs.inkTertiaryDark,
    accent = CxPaletteSpecs.accentDark,
    onAccent = CxPaletteSpecs.onAccentDark,
    sage = CxPaletteSpecs.sageDark,
    amber = CxPaletteSpecs.amberDark,
    cinnabar = CxPaletteSpecs.cinnabarDark,
    plum = CxPaletteSpecs.plumDark,
    isDark = true,
)

val LocalCxColors = staticCompositionLocalOf { LightCxColors }

/**
 * Dynamic adaptive color tokens matching the Swift `CxPalette` API surface.
 */
object CxPalette {
    val canvas: Color
        @Composable @ReadOnlyComposable get() = LocalCxColors.current.canvas

    val surface: Color
        @Composable @ReadOnlyComposable get() = LocalCxColors.current.surface

    val surfaceSecondary: Color
        @Composable @ReadOnlyComposable get() = LocalCxColors.current.surfaceSecondary

    val border: Color
        @Composable @ReadOnlyComposable get() = LocalCxColors.current.border

    val ink: Color
        @Composable @ReadOnlyComposable get() = LocalCxColors.current.ink

    val inkSecondary: Color
        @Composable @ReadOnlyComposable get() = LocalCxColors.current.inkSecondary

    val inkTertiary: Color
        @Composable @ReadOnlyComposable get() = LocalCxColors.current.inkTertiary

    val accent: Color
        @Composable @ReadOnlyComposable get() = LocalCxColors.current.accent

    val onAccent: Color
        @Composable @ReadOnlyComposable get() = LocalCxColors.current.onAccent

    val sage: Color
        @Composable @ReadOnlyComposable get() = LocalCxColors.current.sage

    val amber: Color
        @Composable @ReadOnlyComposable get() = LocalCxColors.current.amber

    val cinnabar: Color
        @Composable @ReadOnlyComposable get() = LocalCxColors.current.cinnabar

    val plum: Color
        @Composable @ReadOnlyComposable get() = LocalCxColors.current.plum

    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalCxColors.current.isDark
}

object CxTheme {
    val colors: CxColors
        @Composable @ReadOnlyComposable get() = LocalCxColors.current
}

object CxTypography {
    val screenTitle: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    )

    val brandDisplay: TextStyle = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 34.sp,
        lineHeight = 42.sp,
    )

    val title: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    )

    val headline: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )

    val eyebrow: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

    fun mono(
        size: TextUnit = 12.sp,
        weight: FontWeight = FontWeight.Medium,
    ): TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = weight,
        fontSize = size,
    )
}

fun cxLightColorScheme(colors: CxColors = LightCxColors): ColorScheme = lightColorScheme(
    primary = colors.accent,
    onPrimary = colors.onAccent,
    primaryContainer = lerp(colors.surface, colors.accent, 0.13f),
    onPrimaryContainer = colors.accent,
    secondary = colors.inkSecondary,
    onSecondary = colors.onAccent,
    secondaryContainer = lerp(colors.surface, colors.accent, 0.13f),
    onSecondaryContainer = colors.accent,
    tertiary = colors.sage,
    onTertiary = colors.onAccent,
    tertiaryContainer = lerp(colors.surface, colors.sage, 0.13f),
    onTertiaryContainer = colors.sage,
    background = colors.canvas,
    onBackground = colors.ink,
    surface = colors.surface,
    onSurface = colors.ink,
    surfaceVariant = colors.surfaceSecondary,
    onSurfaceVariant = colors.inkSecondary,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = colors.surface,
    surfaceContainerLow = colors.surface,
    surfaceContainer = colors.surface,
    surfaceContainerHigh = colors.surfaceSecondary,
    surfaceContainerHighest = colors.surfaceSecondary,
    outline = colors.border,
    outlineVariant = colors.border,
    error = colors.cinnabar,
    onError = colors.onAccent,
    errorContainer = lerp(colors.surface, colors.cinnabar, 0.13f),
    onErrorContainer = colors.cinnabar,
)

fun cxDarkColorScheme(colors: CxColors = DarkCxColors): ColorScheme = darkColorScheme(
    primary = colors.accent,
    onPrimary = colors.onAccent,
    primaryContainer = lerp(colors.surface, colors.accent, 0.13f),
    onPrimaryContainer = colors.accent,
    secondary = colors.inkSecondary,
    onSecondary = colors.onAccent,
    secondaryContainer = lerp(colors.surface, colors.accent, 0.13f),
    onSecondaryContainer = colors.accent,
    tertiary = colors.sage,
    onTertiary = colors.onAccent,
    tertiaryContainer = lerp(colors.surface, colors.sage, 0.13f),
    onTertiaryContainer = colors.sage,
    background = colors.canvas,
    onBackground = colors.ink,
    surface = colors.surface,
    onSurface = colors.ink,
    surfaceVariant = colors.surfaceSecondary,
    onSurfaceVariant = colors.inkSecondary,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = colors.surface,
    surfaceContainerLow = colors.surface,
    surfaceContainer = colors.surface,
    surfaceContainerHigh = colors.surfaceSecondary,
    surfaceContainerHighest = colors.surfaceSecondary,
    outline = colors.border,
    outlineVariant = colors.border,
    error = colors.cinnabar,
    onError = colors.onAccent,
    errorContainer = lerp(colors.surface, colors.cinnabar, 0.13f),
    onErrorContainer = colors.cinnabar,
)

@Composable
fun CaochaoxingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val cxColors = if (darkTheme) DarkCxColors else LightCxColors
    val colorScheme = if (darkTheme) cxDarkColorScheme(cxColors) else cxLightColorScheme(cxColors)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalCxColors provides cxColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
