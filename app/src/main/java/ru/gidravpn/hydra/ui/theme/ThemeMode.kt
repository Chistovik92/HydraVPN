package ru.gidravpn.hydra.ui.theme

import androidx.annotation.StringRes
import ru.gidravpn.hydra.R

/**
 * Темы оформления. Значения сохраняются в DataStore по имени константы —
 * [ThemeRepository] умеет читать и старое имя EMERALD (до 0.6.1), чтобы у
 * пользователя не сбрасывался выбор при обновлении.
 */
enum class ThemeMode(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    /** Основная: изумрудный неон на «бездне». */
    AMBIENT(R.string.theme_ambient, R.string.theme_ambient_desc),

    /** Монохромная со стелс-контрастом и багровым ядром. */
    STEALTH(R.string.theme_stealth, R.string.theme_stealth_desc),

    /** Чисто чёрный фон (экономит батарею на OLED) с изумрудным акцентом. */
    AMOLED(R.string.theme_amoled, R.string.theme_amoled_desc),

    /** Цвета из обоев системы (Android 12+). На старых версиях ведёт себя как AMBIENT. */
    MATERIAL_YOU(R.string.theme_material_you, R.string.theme_material_you_desc);

    /** Доступна ли тема на этом устройстве. */
    val isSupported: Boolean
        get() = this != MATERIAL_YOU || android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
}
