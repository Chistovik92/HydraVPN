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

/**
 * Hydra Ambient — основная тема. Значения сняты с макета
 * `hydra_vpn_home_screen_fixed.html` (bg-abyss / bg-card / neon-emerald).
 */
val AmbientPalette = HydraPalette(
    bg = Color(0xFF070A0D),
    surface = Color(0xFF0E181B),
    surfaceDim = Color(0xCC0E181B),
    cardBg = Color(0x660E181B),
    inputBg = Color(0xCC070A0D),
    border = Color(0x3800E599),
    textPrimary = Color(0xFFF1F5F9),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B),
    accent = Color(0xFF00E599),
    accentSecondary = Color(0xFF38BDF8),
    betaAccent = Color(0xFFC084FC),
    success = Color(0xFF00E599),
    danger = Color(0xFFEF4444),
)

/**
 * Monochrome Stealth — монохромная тема с багровым ядром. Значения сняты с
 * макета `hydra_vpn_monochrome_all_tabs.html` (crimson-core / crimson-bright).
 * Изумрудный здесь остаётся только как сигнал «всё хорошо» (успех, быстрый пинг).
 */
val StealthPalette = HydraPalette(
    bg = Color(0xFF06080A),
    surface = Color(0xFF12161A),
    surfaceDim = Color(0xCC12161A),
    cardBg = Color(0x6612161A),
    inputBg = Color(0xCC06080A),
    border = Color(0x40C5162E),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B),
    accent = Color(0xFFFF3B56),
    accentSecondary = Color(0xFF38BDF8),
    betaAccent = Color(0xFFFF3B56),
    success = Color(0xFF00E599),
    danger = Color(0xFFEF4444),
)

val LocalHydraPalette = compositionLocalOf { AmbientPalette }

// Пороговые цвета пинга — одинаковы в обеих темах (как в макетах).
val PingFast = Color(0xFF00E599)
val PingMed = Color(0xFFEAB308)
val PingSlow = Color(0xFFF97316)

/** Багровое ядро Marvel-Hydra — заливки тревожных плашек и колец. */
val CrimsonCore = Color(0xFFC5162E)

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
