package ru.gidravpn.hydra.desktop

/**
 * Темы и иконки — те же, что в Android-приложении (0.7.9): палитры сняты с
 * `app/.../ui/theme/Color.kt`, иконки отрисованы из тех же векторов adaptive-icon
 * (`app/src/main/res/drawable/ic_launcher_*`). Material You на ПК нет — системной
 * палитры обоев здесь не существует, как и на Android до 12-й версии.
 */
enum class AppTheme(val title: String, val description: String) {
    AMBIENT("Hydra Ambient", "Изумрудный неон на тёмном фоне — основная"),
    STEALTH("Monochrome Stealth", "Монохром с багровым ядром"),
    AMOLED("AMOLED", "Чисто чёрный фон, изумрудный акцент");

    companion object {
        fun of(name: String?): AppTheme = entries.firstOrNull { it.name == name } ?: AMBIENT
    }
}

/** Иконка окна, панели задач и трея — как «Иконка приложения» на Android. */
enum class AppIcon(val title: String) {
    FOLLOW_THEME("Как у темы"),
    AMBIENT("Изумрудная (Ambient)"),
    STEALTH("Багровая (Stealth)"),
    OCEAN("Океан"),
    AMBER("Янтарь");

    /** Ресурс PNG; «как у темы»: Stealth → багровая, остальные → изумрудная (так же на Android). */
    fun resource(theme: AppTheme): String {
        val v = if (this == FOLLOW_THEME) (if (theme == AppTheme.STEALTH) STEALTH else AMBIENT) else this
        return "/icons/${v.name.lowercase()}.png"
    }

    companion object {
        fun of(name: String?): AppIcon = entries.firstOrNull { it.name == name } ?: FOLLOW_THEME
    }
}

/** Цвета темы в виде 0xAARRGGBB — общие для Compose-клиента и Hydra Classic (Swing). */
data class ThemeColors(
    val bg: Long, val surface: Long, val surfaceVariant: Long,
    val text: Long, val textSecondary: Long,
    val accent: Long, val onAccent: Long, val accentSecondary: Long,
    val danger: Long, val warn: Long,
)

fun AppTheme.colors(): ThemeColors = when (this) {
    AppTheme.AMBIENT -> ThemeColors(
        bg = 0xFF070A0D, surface = 0xFF0E181B, surfaceVariant = 0xFF152226,
        text = 0xFFF1F5F9, textSecondary = 0xFF94A3B8,
        accent = 0xFF00E599, onAccent = 0xFF00281A, accentSecondary = 0xFF38BDF8,
        danger = 0xFFEF4444, warn = 0xFFEAB308,
    )
    AppTheme.STEALTH -> ThemeColors(
        bg = 0xFF06080A, surface = 0xFF12161A, surfaceVariant = 0xFF1B2026,
        text = 0xFFF8FAFC, textSecondary = 0xFF94A3B8,
        accent = 0xFFFF3B56, onAccent = 0xFF2A0006, accentSecondary = 0xFF38BDF8,
        danger = 0xFFEF4444, warn = 0xFFEAB308,
    )
    AppTheme.AMOLED -> ThemeColors(
        bg = 0xFF000000, surface = 0xFF000000, surfaceVariant = 0xFF0A0F11,
        text = 0xFFF1F5F9, textSecondary = 0xFF94A3B8,
        accent = 0xFF00E599, onAccent = 0xFF00281A, accentSecondary = 0xFF38BDF8,
        danger = 0xFFEF4444, warn = 0xFFEAB308,
    )
}
