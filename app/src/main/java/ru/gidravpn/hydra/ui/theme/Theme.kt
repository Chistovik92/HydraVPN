package ru.gidravpn.hydra.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext

@Composable
fun HydraTheme(themeMode: ThemeMode = ThemeMode.AMBIENT, content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Material You: цвета берём из системной палитры (обои). Проверка версии —
    // dynamicDarkColorScheme() существует только с Android 12.
    val dynamic: ColorScheme? =
        if (themeMode == ThemeMode.MATERIAL_YOU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            dynamicDarkColorScheme(context) else null

    val palette = when {
        dynamic != null -> paletteFromScheme(dynamic)
        themeMode == ThemeMode.STEALTH -> StealthPalette
        themeMode == ThemeMode.AMOLED -> AmoledPalette
        else -> AmbientPalette
    }
    CompositionLocalProvider(LocalHydraPalette provides palette) {
        MaterialTheme(
            colorScheme = dynamic ?: darkColorScheme(
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

/** Палитра экранов Hydra из динамической цветовой схемы Material You. */
internal fun paletteFromScheme(s: ColorScheme): HydraPalette {
    val card = s.surfaceVariant.copy(alpha = 0.4f)
    return HydraPalette(
        bg = s.background,
        surface = s.surface,
        surfaceDim = s.surface.copy(alpha = 0.8f),
        cardBg = card,
        inputBg = s.background.copy(alpha = 0.8f),
        border = s.outlineVariant,
        textPrimary = s.onBackground,
        textSecondary = s.onSurfaceVariant,
        textMuted = s.onSurfaceVariant.copy(alpha = 0.7f).compositeOver(s.background),
        accent = s.primary,
        accentSecondary = s.tertiary,
        betaAccent = s.secondary,
        // Сигнальные цвета не зависят от обоев: «успех» должен оставаться зелёным.
        success = Color(0xFF00E599),
        danger = s.error,
    )
}
