package ru.gidravpn.hydra.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Отдельный DataStore-файл — не пересекается с "settings"/"theme_settings". */
private val Context.engineStore: DataStore<Preferences> by preferencesDataStore(name = "engine_settings")

/**
 * VLESS/VMess/Trojan/Shadowsocks можно обслуживать и sing-box (по умолчанию,
 * [ru.gidravpn.hydra.data.model.Protocol.engine]), и Xray-core — если он
 * собран (`BuildConfig.XRAY_AVAILABLE`, см. `app/build.gradle.kts`).
 * Этот тумблер даёт выбрать Xray там, где важна именно его реализация XTLS.
 */
class EngineRepository(private val context: Context) {

    private val KEY_PREFER_XRAY = booleanPreferencesKey("prefer_xray")

    val preferXray: Flow<Boolean> = context.engineStore.data.map { it[KEY_PREFER_XRAY] == true }

    suspend fun setPreferXray(enabled: Boolean) {
        context.engineStore.edit { it[KEY_PREFER_XRAY] = enabled }
    }
}
