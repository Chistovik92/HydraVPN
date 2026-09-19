package ru.gidravpn.hydra.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.gidravpn.hydra.data.model.HotspotSettings
import java.security.SecureRandom

/**
 * Настройки хотспот-прокси. Лежат в `vpn_settings` (тот же DataStore, что и
 * Kill Switch), поэтому попадают в резервную копию без правок BackupCodec.
 */
class HotspotRepository(private val context: Context) {

    private val KEY_ENABLED = booleanPreferencesKey("hotspot_enabled")
    private val KEY_PORT = intPreferencesKey("hotspot_port")
    private val KEY_USER = stringPreferencesKey("hotspot_user")
    private val KEY_PASSWORD = stringPreferencesKey("hotspot_password")

    val settings: Flow<HotspotSettings> = context.vpnSettingsStore.data.map { p ->
        HotspotSettings(
            enabled = p[KEY_ENABLED] == true,
            port = p[KEY_PORT] ?: HotspotSettings.DEFAULT_PORT,
            username = p[KEY_USER] ?: HotspotSettings.DEFAULT_USER,
            password = p[KEY_PASSWORD].orEmpty(),
        )
    }

    /** Пароль создаётся при первом включении: пустой пароль на открытом порту недопустим. */
    suspend fun setEnabled(enabled: Boolean) {
        context.vpnSettingsStore.edit { p ->
            if (enabled && p[KEY_PASSWORD].isNullOrEmpty()) p[KEY_PASSWORD] = generatePassword()
            p[KEY_ENABLED] = enabled
        }
    }

    suspend fun setPort(port: Int) {
        context.vpnSettingsStore.edit { it[KEY_PORT] = port }
    }

    suspend fun setCredentials(username: String, password: String) {
        context.vpnSettingsStore.edit {
            it[KEY_USER] = username.trim()
            it[KEY_PASSWORD] = password
        }
    }

    suspend fun regeneratePassword() {
        context.vpnSettingsStore.edit { it[KEY_PASSWORD] = generatePassword() }
    }

    companion object {
        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** 12 символов без похожих (0/O, 1/l/I) — пароль вводят руками на другом устройстве. */
        fun generatePassword(): String {
            val rnd = SecureRandom()
            return buildString { repeat(12) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) } }
        }
    }
}
