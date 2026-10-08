package ru.gidravpn.hydra.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Скругления Material 3 (Material You): 8 / 12 / 16 / 20 / 28 dp. */
val HydraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun HydraTheme(themeMode: ThemeMode = ThemeMode.AMBIENT, content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Material You: цвета берём из системной палитры (обои). Проверка версии —
    // dynamicDarkColorScheme() существует только с Android 12.
    val dynamic: ColorScheme? =
        if (themeMode == ThemeMode.MATERIAL_YOU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            dynamicDarkColorScheme(context) else null

    val base = when {
        dynamic != null -> paletteFromScheme(dynamic)
        themeMode == ThemeMode.STEALTH -> StealthPalette
        themeMode == ThemeMode.AMOLED -> AmoledPalette
        else -> AmbientPalette
    }
    val scheme = remember(themeMode, dynamic) { dynamic ?: tonalScheme(base) }
    // 0.7.9: карточки всех тем — тональные, как в Material You (поверхность с оттенком акцента),
    // а не полупрозрачное стекло с яркой рамкой.
    val palette = remember(base, scheme) { base.tonal(scheme) }
    CompositionLocalProvider(LocalHydraPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = HydraTypography,
            shapes = HydraShapes,
            content = content
        )
    }
}

/**
 * Полная тональная схема Material 3 из фирменной палитры (0.7.9).
 *
 * Раньше схема задавала только primary/secondary/background/surface — остальные роли
 * (контейнеры, surfaceContainer*, outline, tertiary, inverse…) M3 брал из своей базовой
 * сиреневой палитры: переключатели, чипы, диалоги, нижняя навигация и поля ввода выглядели
 * чужеродно. Теперь каждая роль выводится из акцента темы так же, как Material You выводит
 * их из цвета обоев: смешением с фоном в разных пропорциях.
 */
internal fun tonalScheme(p: HydraPalette): ColorScheme {
    fun mix(a: Color, b: Color, t: Float) = lerp(a, b, t)
    val bg = p.bg
    val s = p.surface
    val a = p.accent
    return darkColorScheme(
        primary = a,
        onPrimary = mix(a, Color.Black, 0.82f),
        primaryContainer = mix(bg, a, 0.30f),
        onPrimaryContainer = mix(a, Color.White, 0.72f),
        inversePrimary = mix(a, Color.Black, 0.45f),
        secondary = p.accentSecondary,
        onSecondary = mix(p.accentSecondary, Color.Black, 0.82f),
        secondaryContainer = mix(s, a, 0.20f),
        onSecondaryContainer = mix(a, Color.White, 0.82f),
        tertiary = p.betaAccent,
        onTertiary = mix(p.betaAccent, Color.Black, 0.82f),
        tertiaryContainer = mix(bg, p.betaAccent, 0.28f),
        onTertiaryContainer = mix(p.betaAccent, Color.White, 0.75f),
        background = bg,
        onBackground = p.textPrimary,
        surface = bg,
        onSurface = p.textPrimary,
        surfaceVariant = mix(s, a, 0.10f),
        onSurfaceVariant = p.textSecondary,
        surfaceTint = a,
        inverseSurface = p.textPrimary,
        inverseOnSurface = bg,
        error = p.danger,
        onError = mix(p.danger, Color.Black, 0.82f),
        errorContainer = mix(bg, p.danger, 0.30f),
        onErrorContainer = mix(p.danger, Color.White, 0.72f),
        outline = mix(p.textMuted, a, 0.25f),
        outlineVariant = mix(s, a, 0.22f),
        scrim = Color.Black,
        surfaceBright = mix(s, a, 0.14f),
        surfaceDim = bg,
        surfaceContainerLowest = mix(bg, Color.Black, 0.5f),
        surfaceContainerLow = mix(bg, a, 0.035f),
        surfaceContainer = mix(s, a, 0.05f),
        surfaceContainerHigh = mix(s, a, 0.08f),
        surfaceContainerHighest = mix(s, a, 0.12f),
    )
}

/** Карточки, поля и рамки — из тональных ролей схемы (одинаково для фирменных тем и Material You). */
internal fun HydraPalette.tonal(s: ColorScheme) = copy(
    surface = s.surfaceContainer,
    surfaceDim = s.surfaceContainer.copy(alpha = 0.92f),
    cardBg = s.surfaceContainerLow.copy(alpha = 0.94f),
    inputBg = s.surfaceContainerHighest,
    border = s.outlineVariant.copy(alpha = 0.55f),
)

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
