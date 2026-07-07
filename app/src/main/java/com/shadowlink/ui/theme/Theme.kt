package com.shadowlink.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ShadowLinkColors = darkColorScheme(
    primary = AccentCyan,
    secondary = AccentIndigo,
    background = Bg,
    surface = Surface,
    onPrimary = Bg,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = Danger,
)

@Composable
fun ShadowLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ShadowLinkColors,
        typography = ShadowLinkTypography,
        content = content
    )
}
