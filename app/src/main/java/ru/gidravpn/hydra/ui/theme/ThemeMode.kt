package ru.gidravpn.hydra.ui.theme

/**
 * Темы оформления. Значения сохраняются в DataStore по имени константы —
 * [ThemeRepository] умеет читать и старое имя EMERALD (до 0.6.1), чтобы у
 * пользователя не сбрасывался выбор при обновлении.
 */
enum class ThemeMode(val label: String, val description: String) {
    /** Основная: изумрудный неон на «бездне». */
    AMBIENT("Hydra Ambient", "Основная — изумрудный неон на тёмном титане"),

    /** Монохромная со стелс-контрастом и багровым ядром. */
    STEALTH("Monochrome Stealth", "Монохромная — стелс-контраст и багровое ядро"),
}
