package ru.gidravpn.hydra.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun HydraTheme(themeMode: ThemeMode = ThemeMode.EMERALD, content: @Composable () -> Unit) {
    val palette = when (themeMode) {
        ThemeMode.EMERALD -> EmeraldPalette
        ThemeMode.STEALTH -> StealthPalette
    }
    CompositionLocalProvider(LocalHydraPalette provides palette) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = palette.accent,
                secondary = palette.accentSecondary,
                background = palette.bg,
                surface = palette.surface,
                onPrimary = palette.bg,
                onBackground = palette.textPrimary,
                onSurface = palette.textPrimary,
                error = palette.danger,
            ),
            typography = HydraTypography,
            content = content
        )
    }
}
