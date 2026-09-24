package ru.gidravpn.hydra.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Приватность и обновления (Фаза 8). Лежит в том же vpn_settings, что и Kill Switch, —
 * попадает в резервную копию и сбрасывается вместе с остальными настройками.
 */
class PrivacyRepository(private val context: Context) {

    private val KEY_APP_LOCK = booleanPreferencesKey("app_lock")
    private val KEY_HIDE_SECRETS = booleanPreferencesKey("hide_secrets")
    private val KEY_UPDATE_CHECK = booleanPreferencesKey("update_check")
    private val KEY_UPDATE_LAST = longPreferencesKey("update_last_check")

    /** Отпечаток/лицо/PIN устройства при открытии приложения. По умолчанию выключено. */
    val appLock: Flow<Boolean> = context.vpnSettingsStore.data.map { it[KEY_APP_LOCK] == true }
    suspend fun setAppLock(enabled: Boolean) { context.vpnSettingsStore.edit { it[KEY_APP_LOCK] = enabled } }

    /** Маска UUID/паролей/токенов в ссылках на экране. По умолчанию включено. */
    val hideSecrets: Flow<Boolean> = context.vpnSettingsStore.data.map { it[KEY_HIDE_SECRETS] != false }
    suspend fun setHideSecrets(enabled: Boolean) { context.vpnSettingsStore.edit { it[KEY_HIDE_SECRETS] = enabled } }

    /** Раз в сутки спрашивать GitHub о новом релизе. По умолчанию включено. */
    val updateCheck: Flow<Boolean> = context.vpnSettingsStore.data.map { it[KEY_UPDATE_CHECK] != false }
    suspend fun setUpdateCheck(enabled: Boolean) { context.vpnSettingsStore.edit { it[KEY_UPDATE_CHECK] = enabled } }

    val lastUpdateCheck: Flow<Long> = context.vpnSettingsStore.data.map { it[KEY_UPDATE_LAST] ?: 0L }
    suspend fun markUpdateChecked(at: Long) { context.vpnSettingsStore.edit { it[KEY_UPDATE_LAST] = at } }
}
