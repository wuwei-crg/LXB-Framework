package com.example.lxb_ignition.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = AppSurface,
    primaryContainer = BrandBlueSoft,
    onPrimaryContainer = BrandSlate,
    secondary = BrandSlateSoft,
    onSecondary = AppSurface,
    secondaryContainer = AppSurfaceMuted,
    onSecondaryContainer = BrandSlate,
    tertiary = Color(0xFF935E12),
    onTertiary = AppSurface,
    tertiaryContainer = Color(0xFFFFF1DB),
    onTertiaryContainer = BrandSlate,
    background = AppBackground,
    onBackground = BrandSlate,
    surface = AppSurface,
    onSurface = BrandSlate,
    surfaceVariant = AppSurfaceMuted,
    onSurfaceVariant = BrandSlateSoft,
    outline = AppOutline,
    outlineVariant = AppOutlineStrong,
    error = AppError,
    onError = AppSurface,
    errorContainer = AppErrorSoft,
    onErrorContainer = Color(0xFF7F1D1D)
)

@Composable
fun LXBIgnitionTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
