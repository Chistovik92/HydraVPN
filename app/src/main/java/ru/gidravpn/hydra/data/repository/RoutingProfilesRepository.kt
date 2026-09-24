package ru.gidravpn.hydra.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import ru.gidravpn.hydra.data.backup.BackupCodec
import ru.gidravpn.hydra.data.backup.putTyped

/**
 * Профили маршрутизации (Фаза 8): «Дом», «Поездка» и т. п. Профиль — снимок всего, что
 * решает, куда идёт трафик: DNS, geo-режим и страны, MTU, фрагментация TLS, IPv6
 * (хранилище routing_settings целиком) плюс раздельное туннелирование по приложениям и по
 * IP/доменам (ключи split_* из settings). Остальные настройки профиль не трогает.
 *
 * Значения хранятся с тегом типа тем же кодеком, что и резервная копия, — ключ DataStore
 * при применении получает ровно тот тип, который читает репозиторий.
 */
class RoutingProfilesRepository(private val context: Context) {

    data class Profile(val name: String, val routing: Map<String, Any>, val split: Map<String, Any>)

    private val KEY_PROFILES = stringPreferencesKey("profiles")

    val profiles: Flow<List<Profile>> = context.profilesStore.data.map { decode(it[KEY_PROFILES]) }

    /** Сохранить текущие настройки под именем [name]; профиль с тем же именем заменяется. */
    suspend fun saveCurrent(name: String) {
        val routing = context.routingStore.data.first().asNamedMap()
        val split = context.settingsStore.data.first().asNamedMap().filterKeys { it in SPLIT_KEYS }
        context.profilesStore.edit { p ->
            val list = decode(p[KEY_PROFILES]).filterNot { it.name.equals(name, ignoreCase = true) } +
                Profile(name.trim(), routing, split)
            p[KEY_PROFILES] = encode(list)
        }
    }

    /** Применить профиль: routing_settings заменяется целиком, split_* — только эти ключи. */
    suspend fun apply(profile: Profile) {
        context.routingStore.edit { p ->
            p.clear()
            profile.routing.forEach { (k, v) -> p.putTyped(k, v) }
        }
        context.settingsStore.edit { p ->
            p.asMap().keys.filter { it.name in SPLIT_KEYS }.forEach { p.remove(it) }
            profile.split.forEach { (k, v) -> p.putTyped(k, v) }
        }
    }

    suspend fun delete(name: String) {
        context.profilesStore.edit { p ->
            p[KEY_PROFILES] = encode(decode(p[KEY_PROFILES]).filterNot { it.name == name })
        }
    }

    private fun Preferences.asNamedMap(): Map<String, Any> = asMap().entries.associate { (k, v) -> k.name to v }

    companion object {
        /** Ключи раздельного туннелирования в хранилище settings (SplitTunnelRepository). */
        val SPLIT_KEYS = setOf("split_mode", "split_packages", "split_net_mode", "split_net_rules")

        internal fun encode(list: List<Profile>): String = JSONArray().apply {
            list.forEach { pr ->
                put(JSONObject().put("name", pr.name)
                    .put("routing", pr.routing.toJson())
                    .put("split", pr.split.toJson()))
            }
        }.toString()

        internal fun decode(json: String?): List<Profile> = runCatching {
            val arr = JSONArray(json ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Profile(o.getString("name"), o.optJSONObject("routing").toMap(), o.optJSONObject("split").toMap())
            }
        }.getOrDefault(emptyList())

        private fun Map<String, Any>.toJson() = JSONObject().apply {
            forEach { (k, v) -> put(k, BackupCodec.encodeValue(v)) }
        }

        private fun JSONObject?.toMap(): Map<String, Any> =
            if (this == null) emptyMap() else keys().asSequence().associateWith { BackupCodec.decodeValue(getJSONObject(it)) }
    }
}
