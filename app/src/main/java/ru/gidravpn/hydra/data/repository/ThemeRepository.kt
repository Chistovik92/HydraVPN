package ru.gidravpn.hydra.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.gidravpn.hydra.ui.theme.ThemeMode

/** Отдельный DataStore-файл — не пересекается с "settings" из SplitTunnelRepository. */
private val Context.themeStore: DataStore<Preferences> by preferencesDataStore(name = "theme_settings")

class ThemeRepository(private val context: Context) {

    private val KEY_MODE = stringPreferencesKey("theme_mode")
    private val KEY_DYNAMIC_ICON = stringPreferencesKey("dynamic_launcher_icon")

    val mode: Flow<ThemeMode> = context.themeStore.data.map { prefs ->
        parseMode(prefs[KEY_MODE])
    }

    /** Менять ли иконку на рабочем столе вместе с темой (по умолчанию — нет). */
    val dynamicLauncherIcon: Flow<Boolean> = context.themeStore.data.map { prefs ->
        prefs[KEY_DYNAMIC_ICON] == "true"
    }

    suspend fun setMode(mode: ThemeMode) {
        context.themeStore.edit { it[KEY_MODE] = mode.name }
    }

    suspend fun setDynamicLauncherIcon(enabled: Boolean) {
        context.themeStore.edit { it[KEY_DYNAMIC_ICON] = enabled.toString() }
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
