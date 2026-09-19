package ru.gidravpn.hydra.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.gidravpn.hydra.ui.LauncherIconChoice
import ru.gidravpn.hydra.ui.theme.ThemeMode

class ThemeRepository(private val context: Context) {

    private val KEY_MODE = stringPreferencesKey("theme_mode")
    // Ключ до 0.6.16: булев тумблер «менять ярлык вместе с темой». Оставлен,
    // чтобы у обновившегося включённый тумблер стал выбором FOLLOW_THEME.
    private val KEY_DYNAMIC_ICON = stringPreferencesKey("dynamic_launcher_icon")
    private val KEY_ICON_CHOICE = stringPreferencesKey("launcher_icon_choice")

    val mode: Flow<ThemeMode> = context.themeStore.data.map { prefs ->
        parseMode(prefs[KEY_MODE])
    }

    /** Выбранный ярлык рабочего стола (по умолчанию — базовый Ambient, как и раньше). */
    val launcherIcon: Flow<LauncherIconChoice> = context.themeStore.data.map { prefs ->
        parseIconChoice(prefs[KEY_ICON_CHOICE], prefs[KEY_DYNAMIC_ICON])
    }

    suspend fun setMode(mode: ThemeMode) {
        context.themeStore.edit { it[KEY_MODE] = mode.name }
    }

    suspend fun setLauncherIcon(choice: LauncherIconChoice) {
        context.themeStore.edit {
            it[KEY_ICON_CHOICE] = choice.name
            it.remove(KEY_DYNAMIC_ICON)
        }
    }

    internal fun parseIconChoice(raw: String?, legacyDynamic: String?): LauncherIconChoice = when {
        raw != null -> runCatching { LauncherIconChoice.valueOf(raw) }.getOrDefault(LauncherIconChoice.AMBIENT)
        legacyDynamic == "true" -> LauncherIconChoice.FOLLOW_THEME
        else -> LauncherIconChoice.AMBIENT
    }

    /**
     * До 0.6.1 основная тема называлась EMERALD. Читаем старое значение как
     * AMBIENT, иначе у обновившегося пользователя выбор темы молча сбросится.
     */
    private fun parseMode(raw: String?): ThemeMode = when (raw) {
        null -> ThemeMode.AMBIENT
        "EMERALD" -> ThemeMode.AMBIENT
        else -> runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.AMBIENT)
    }
}
