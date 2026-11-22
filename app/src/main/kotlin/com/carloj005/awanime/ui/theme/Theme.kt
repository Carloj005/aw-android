package com.carloj005.awanime.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AwDarkColorScheme = darkColorScheme(
    primary = AccentViolet,
    onPrimary = TextPrimary,
    primaryContainer = AccentVioletDeep,
    onPrimaryContainer = TextPrimary,
    secondary = AccentCyan,
    onSecondary = MidnightBg,
    secondaryContainer = SurfaceHighlight,
    onSecondaryContainer = TextPrimary,
    tertiary = BadgeItaCoral,
    background = MidnightBg,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = DividerDark,
    error = ErrorRed,
    onError = TextPrimary,
)

@Composable
fun AWAnimeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AwDarkColorScheme,
        typography = Typography,
        content = content,
    )
}
