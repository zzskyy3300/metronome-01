package com.example.metronome.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * 应用主题：强制深色（暗光练习场景，节拍闪烁更醒目）。
 * 忽略系统浅色主题，始终使用 darkColorScheme。
 */
@Composable
fun MetronomeTheme(
    content: @Composable () -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val ignored = isSystemInDarkTheme()
    val colors = darkColorScheme(
        primary = Primary,
        onPrimary = androidx.compose.ui.graphics.Color.Black,
        background = BackgroundDark,
        onBackground = OnBackground,
        surface = SurfaceDark,
        onSurface = OnSurface,
        surfaceVariant = SurfaceVariantDark,
        onSurfaceVariant = OnBackground,
    )
    MaterialTheme(
        colorScheme = colors,
        typography = MetronomeTypography,
        content = content,
    )
}
