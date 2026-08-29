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
import org.json.JSONObject
import ru.gidravpn.hydra.data.model.NetRuleType
import ru.gidravpn.hydra.data.model.NetworkRule
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
    private val KEY_NET_MODE = stringPreferencesKey("split_net_mode")
    private val KEY_NET_RULES = stringPreferencesKey("split_net_rules")

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
            netMode = runCatching {
                SplitTunnelMode.valueOf(prefs[KEY_NET_MODE] ?: SplitTunnelMode.OFF.name)
            }.getOrDefault(SplitTunnelMode.OFF),
            netRules = parseNetRules(prefs[KEY_NET_RULES]),
        )
    }

    suspend fun setMode(mode: SplitTunnelMode) {
        context.settingsStore.edit { it[KEY_MODE] = mode.name }
    }

    suspend fun setNetMode(mode: SplitTunnelMode) {
        context.settingsStore.edit { it[KEY_NET_MODE] = mode.name }
    }

    suspend fun addNetRule(rule: NetworkRule) {
        context.settingsStore.edit { prefs ->
            val current = parseNetRules(prefs[KEY_NET_RULES]).toMutableList()
            if (current.none { it.type == rule.type && it.value.equals(rule.value, ignoreCase = true) }) {
                current.add(rule)
            }
            prefs[KEY_NET_RULES] = serializeNetRules(current)
        }
    }

    suspend fun removeNetRule(rule: NetworkRule) {
        context.settingsStore.edit { prefs ->
            val current = parseNetRules(prefs[KEY_NET_RULES]).toMutableList()
            current.removeAll { it.type == rule.type && it.value == rule.value }
            prefs[KEY_NET_RULES] = serializeNetRules(current)
        }
    }

    private fun parseNetRules(json: String?): List<NetworkRule> = runCatching {
        val arr = JSONArray(json ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            NetworkRule(NetRuleType.valueOf(o.getString("type")), o.getString("value"))
        }
    }.getOrDefault(emptyList())

    private fun serializeNetRules(rules: List<NetworkRule>): String =
        JSONArray().apply {
            rules.forEach { put(JSONObject().put("type", it.type.name).put("value", it.value)) }
        }.toString()

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
