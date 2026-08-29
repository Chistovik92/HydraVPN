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

    val mode: Flow<ThemeMode> = context.themeStore.data.map { prefs ->
        runCatching {
            ThemeMode.valueOf(prefs[KEY_MODE] ?: ThemeMode.EMERALD.name)
        }.getOrDefault(ThemeMode.EMERALD)
    }

    suspend fun setMode(mode: ThemeMode) {
        context.themeStore.edit { it[KEY_MODE] = mode.name }
    }
}
