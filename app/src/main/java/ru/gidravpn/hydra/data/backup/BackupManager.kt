package ru.gidravpn.hydra.data.backup

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import ru.gidravpn.hydra.BuildConfig
import ru.gidravpn.hydra.data.db.AppDatabase
import ru.gidravpn.hydra.data.repository.allStores

/**
 * Резервная копия всей конфигурации (Фаза 6d): все DataStore-настройки +
 * серверы + подписки. Особенно важна, пока AppDatabase использует
 * fallbackToDestructiveMigration(): любое будущее изменение схемы Room без
 * написанной миграции молча удалит все серверы.
 */
object BackupManager {

    data class Summary(val servers: Int, val subscriptions: Int, val settings: Int)

    suspend fun export(context: Context): String {
        val db = AppDatabase.get(context)
        val prefs = context.allStores().mapValues { (_, store) ->
            store.data.first().asMap().entries.associate { (k, v) -> k.name to v }
        }
        return BackupCodec.encode(
            Backup(
                appVersion = BuildConfig.VERSION_NAME,
                createdAt = System.currentTimeMillis(),
                prefs = prefs,
                servers = db.serverDao().getAll(),
                subscriptions = db.subscriptionDao().getAll(),
            )
        )
    }

    /**
     * Полностью заменяет конфигурацию содержимым копии. Сначала разбирает весь
     * файл (BackupFormatException — до любых изменений), потом пишет. ID
     * серверов сохраняются, чтобы «последний сервер» (плитка/автозапуск)
     * указывал на тот же сервер. Хранилища, которых нет в копии (появились в
     * более новой версии), не трогаются.
     */
    suspend fun import(context: Context, text: String): Summary {
        val backup = BackupCodec.decode(text)
        val db = AppDatabase.get(context)
        db.withTransaction {
            db.serverDao().deleteAll()
            db.subscriptionDao().deleteAll()
            db.subscriptionDao().upsertAll(backup.subscriptions)
            db.serverDao().upsertAll(backup.servers)
        }
        val stores = context.allStores()
        var settings = 0
        backup.prefs.forEach { (name, values) ->
            val store = stores[name] ?: return@forEach
            store.edit { p ->
                p.clear()
                values.forEach { (k, v) -> p.putTyped(k, v) }
            }
            settings += values.size
    }
        return Summary(backup.servers.size, backup.subscriptions.size, settings)
    }

    /** Все настройки к значениям по умолчанию. Серверы и подписки не трогаются. */
    suspend fun resetSettings(context: Context) {
        context.allStores().values.forEach { store -> store.edit { it.clear() } }
    }

}

/** Записать значение с тем типом ключа, который оно несёт (бэкап, профили маршрутизации). */
@Suppress("UNCHECKED_CAST")
internal fun MutablePreferences.putTyped(key: String, value: Any) {
    when (value) {
        is Boolean -> this[booleanPreferencesKey(key)] = value
        is Int -> this[intPreferencesKey(key)] = value
        is Long -> this[longPreferencesKey(key)] = value
        is Float -> this[floatPreferencesKey(key)] = value
        is Double -> this[doublePreferencesKey(key)] = value
        is String -> this[stringPreferencesKey(key)] = value
        is Set<*> -> this[stringSetPreferencesKey(key)] = value as Set<String>
    }
}
