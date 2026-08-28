package ru.gidravpn.hydra.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import ru.gidravpn.hydra.data.model.SplitTunnel
import ru.gidravpn.hydra.data.model.SplitTunnelMode

/** DataStore для настроек приложения (split tunneling и пр.). */
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Хранение настроек раздельного туннелирования (DataStore, JSON-сериализация).
 */
class SplitTunnelRepository(private val context: Context) {

    private val KEY_MODE = stringPreferencesKey("split_mode")
    private val KEY_PACKAGES = stringPreferencesKey("split_packages")

    val settings: Flow<SplitTunnel> = context.settingsStore.data.map { prefs ->
        SplitTunnel(
            mode = runCatching {
                SplitTunnelMode.valueOf(prefs[KEY_MODE] ?: SplitTunnelMode.OFF.name)
            }.getOrDefault(SplitTunnelMode.OFF),
            packages = runCatching {
                JSONArray(prefs[KEY_PACKAGES] ?: "[]").let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }.toSet()
                }
            }.getOrDefault(emptySet()),
        )
    }

    suspend fun setMode(mode: SplitTunnelMode) {
        context.settingsStore.edit { it[KEY_MODE] = mode.name }
    }

    suspend fun setPackages(packages: Set<String>) {
        context.settingsStore.edit {
            it[KEY_PACKAGES] = JSONArray().apply { packages.forEach { p -> put(p) } }.toString()
        }
    }

    suspend fun toggleApp(pkg: String) {
        context.settingsStore.edit { prefs ->
            val current = runCatching {
                JSONArray(prefs[KEY_PACKAGES] ?: "[]").let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }.toMutableSet()
                }
            }.getOrDefault(mutableSetOf())
            if (!current.add(pkg)) current.remove(pkg)
            prefs[KEY_PACKAGES] = JSONArray().apply { current.forEach { p -> put(p) } }.toString()
        }
    }
}
