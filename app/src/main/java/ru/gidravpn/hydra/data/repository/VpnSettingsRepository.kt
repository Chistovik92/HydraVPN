package ru.gidravpn.hydra.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Настройки Фазы 6b («Безопасность соединения») и последний выбранный сервер —
 * нужен и UI (persist выбора между запусками), и компонентам без Activity
 * (BootReceiver, HydraQsTileService), которым неоткуда взять MainViewModel.
 */
class VpnSettingsRepository(private val context: Context) {

    private val KEY_KILL_SWITCH = booleanPreferencesKey("kill_switch")
    private val KEY_AUTOCONNECT_APP = booleanPreferencesKey("autoconnect_app_start")
    private val KEY_AUTOCONNECT_BOOT = booleanPreferencesKey("autoconnect_boot")
private val KEY_LAST_SERVER_ID = longPreferencesKey("last_server_id")
    private val KEY_AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")

    /**
     * При обрыве/ошибке соединения держать tun-интерфейс поднятым, но без
     * ретрансляции трафика (см. HydraVpnService.doConnect) вместо полного
     * снятия туннеля — блокирует утечку в открытую сеть вместо тихого
     * отката на прямое соединение. НЕ защищает от системного onRevoke()
     * (другое VPN-приложение, отзыв в настройках) — там ОС сама рвёт tun
     * раньше, чем сервис успевает среагировать; для этого случая нужен
     * системный «Блокировать соединения без VPN» (Настройки → VPN).
     */
    val killSwitch: Flow<Boolean> = context.vpnSettingsStore.data.map { it[KEY_KILL_SWITCH] == true }
    suspend fun setKillSwitch(enabled: Boolean) {
        context.vpnSettingsStore.edit { it[KEY_KILL_SWITCH] = enabled }
    }

/**
     * Переподключение после разрыва (Фаза 7b): экспоненциальный бэкофф, на время
     * попыток Kill Switch (если включён) держит трафик заблокированным. По умолчанию
     * включено — «упавший туннель сам не поднимается» было главной жалобой.
     */
    val autoReconnect: Flow<Boolean> = context.vpnSettingsStore.data.map { it[KEY_AUTO_RECONNECT] != false }
    suspend fun setAutoReconnect(enabled: Boolean) {
        context.vpnSettingsStore.edit { it[KEY_AUTO_RECONNECT] = enabled }
    }

    /** Автоподключение к последнему серверу при запуске приложения (если VPN-согласие уже выдано). */
    val autoConnectOnAppStart: Flow<Boolean> = context.vpnSettingsStore.data.map { it[KEY_AUTOCONNECT_APP] == true }
    suspend fun setAutoConnectOnAppStart(enabled: Boolean) {
        context.vpnSettingsStore.edit { it[KEY_AUTOCONNECT_APP] = enabled }
    }

    /** Автоподключение при загрузке устройства (BOOT_COMPLETED) — без открытия Activity. */
    val autoConnectOnBoot: Flow<Boolean> = context.vpnSettingsStore.data.map { it[KEY_AUTOCONNECT_BOOT] == true }
    suspend fun setAutoConnectOnBoot(enabled: Boolean) {
        context.vpnSettingsStore.edit { it[KEY_AUTOCONNECT_BOOT] = enabled }
    }

    /** Последний выбранный/подключённый сервер — источник истины для тайла и автозапуска. */
    val lastServerId: Flow<Long?> = context.vpnSettingsStore.data.map { it[KEY_LAST_SERVER_ID] }
    suspend fun setLastServerId(id: Long) {
        context.vpnSettingsStore.edit { it[KEY_LAST_SERVER_ID] = id }
    }
}
