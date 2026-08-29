package ru.gidravpn.hydra.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** Набор цветовых токенов темы — общий для всех экранов. */
data class HydraPalette(
    val bg: Color,
    val surface: Color,
    val surfaceDim: Color,
    val cardBg: Color,
    val inputBg: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentSecondary: Color,
    val betaAccent: Color,
    val success: Color,
    val danger: Color,
)

/** Hydra Emerald — основная тёмная тема (Abyss/Cyber Slate + неоновый изумруд). */
val EmeraldPalette = HydraPalette(
    bg = Color(0xFF0B0F12),
    surface = Color(0xFF121C1F),
    surfaceDim = Color(0xCC121C1F),
    cardBg = Color(0x66121C1F),
    inputBg = Color(0xCC0B0F12),
    border = Color(0x14FFFFFF),
    textPrimary = Color(0xFFE2E8F0),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B),
    accent = Color(0xFF00E599),
    accentSecondary = Color(0xFF00B37A),
    betaAccent = Color(0xFFC084FC),
    success = Color(0xFF00E599),
    danger = Color(0xFFFF4D5E),
)

/** Monochrome Stealth — контрастная ч/б тема, багровый акцент только для тревожных статусов. */
val StealthPalette = HydraPalette(
    bg = Color(0xFF0A0A0A),
    surface = Color(0xFF1A1A1A),
    surfaceDim = Color(0xCC1A1A1A),
    cardBg = Color(0x661A1A1A),
    inputBg = Color(0xCC0A0A0A),
    border = Color(0x14FFFFFF),
    textPrimary = Color(0xFFF5F5F5),
    textSecondary = Color(0xFFA3A3A3),
    textMuted = Color(0xFF6B6B6B),
    accent = Color(0xFFE5E5E5),
    accentSecondary = Color(0xFF9CA3AF),
    betaAccent = Color(0xFFA3A3A3),
    success = Color(0xFFE5E5E5),
    danger = Color(0xFFFF3B4E),
)

val LocalHydraPalette = compositionLocalOf { EmeraldPalette }

// Раскраска пинга в списке серверов — семантический цвет, не завязан на тему.
val Warning = Color(0xFFF5A623)
// Заливка бейджей/колец тревоги (Crimson Core) — насыщенный тон для фона, не для текста.
val CrimsonFill = Color(0xFF8B1D2C)

// Ниже — совместимость с существующими экранами: те же имена, что и раньше,
// но теперь читают текущую палитру, поэтому переключение темы подхватывается
// автоматически везде, где эти токены уже используются.
val Bg: Color @Composable get() = LocalHydraPalette.current.bg
val Surface: Color @Composable get() = LocalHydraPalette.current.surface
val SurfaceDim: Color @Composable get() = LocalHydraPalette.current.surfaceDim
val CardBg: Color @Composable get() = LocalHydraPalette.current.cardBg
val InputBg: Color @Composable get() = LocalHydraPalette.current.inputBg
val Border: Color @Composable get() = LocalHydraPalette.current.border

val TextPrimary: Color @Composable get() = LocalHydraPalette.current.textPrimary
val TextSecondary: Color @Composable get() = LocalHydraPalette.current.textSecondary
val TextMuted: Color @Composable get() = LocalHydraPalette.current.textMuted

val AccentCyan: Color @Composable get() = LocalHydraPalette.current.accent
val AccentIndigo: Color @Composable get() = LocalHydraPalette.current.accentSecondary
val AccentViolet: Color @Composable get() = LocalHydraPalette.current.betaAccent

val Success: Color @Composable get() = LocalHydraPalette.current.success
val Danger: Color @Composable get() = LocalHydraPalette.current.danger
