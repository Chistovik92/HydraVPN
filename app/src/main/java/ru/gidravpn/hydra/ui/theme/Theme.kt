package ru.gidravpn.hydra.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val HydraColors = darkColorScheme(
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
fun HydraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HydraColors,
        typography = HydraTypography,
        content = content
    )
}
