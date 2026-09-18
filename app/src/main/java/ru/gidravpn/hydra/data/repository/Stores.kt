package ru.gidravpn.hydra.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/*
 * Все DataStore-файлы настроек в одном месте (Фаза 6d). Имена файлов — те же,
 * что были раньше в отдельных репозиториях, поэтому ничего не мигрирует и
 * настройки у обновившихся не теряются. Физически в один файл не сливались
 * сознательно: это миграция ради миграции, а бэкапу/сбросу нужна лишь единая
 * точка доступа — [allStores].
 *
 * Для каждого имени — ровно одно объявление в процессе, иначе DataStore
 * падает с "multiple DataStores active for the same file".
 */
internal val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
internal val Context.themeStore: DataStore<Preferences> by preferencesDataStore(name = "theme_settings")
internal val Context.engineStore: DataStore<Preferences> by preferencesDataStore(name = "engine_settings")
internal val Context.vpnSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "vpn_settings")
internal val Context.routingStore: DataStore<Preferences> by preferencesDataStore(name = "routing_settings")

/** Имя файла → хранилище; порядок стабилен (бэкап, сброс). */
internal fun Context.allStores(): Map<String, DataStore<Preferences>> = linkedMapOf(
    "settings" to settingsStore,
    "theme_settings" to themeStore,
    "engine_settings" to engineStore,
    "vpn_settings" to vpnSettingsStore,
    "routing_settings" to routingStore,
)
