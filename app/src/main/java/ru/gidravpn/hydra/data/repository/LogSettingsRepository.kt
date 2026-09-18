package ru.gidravpn.hydra.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.gidravpn.hydra.data.log.LogPersistMode
import ru.gidravpn.hydra.data.log.LogRetention

/** Настройки сохранения логов (Фаза 6d) — в общем файле "settings". */
class LogSettingsRepository(private val context: Context) {
    private val KEY_MODE = stringPreferencesKey("log_persist_mode")
    private val KEY_RETENTION = stringPreferencesKey("log_retention")

    val mode: Flow<LogPersistMode> = context.settingsStore.data.map { LogPersistMode.fromId(it[KEY_MODE]) }
    suspend fun setMode(mode: LogPersistMode) {
        context.settingsStore.edit { it[KEY_MODE] = mode.name }
    }

    val retention: Flow<LogRetention> = context.settingsStore.data.map { LogRetention.fromId(it[KEY_RETENTION]) }
    suspend fun setRetention(r: LogRetention) {
        context.settingsStore.edit { it[KEY_RETENTION] = r.name }
    }
}
