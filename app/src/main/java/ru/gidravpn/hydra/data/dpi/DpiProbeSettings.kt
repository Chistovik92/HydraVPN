package ru.gidravpn.hydra.data.dpi

import org.json.JSONArray
import org.json.JSONObject

/** Свой список доменов для проверки (как «Добавить список» в ByeByeDPI). */
data class CustomSiteList(val name: String, val domains: List<String>)

/**
 * Настройки подбора стратегии (0.7.15) — те же, что в ByeByeDPI, но с пояснениями:
 *  - [delaySec] — пауза между проверками стратегий (больше — стабильнее на слабой сети);
 *  - [requests] — сколько раз спрашивать каждый домен; сайт считается открытым, если ответило большинство попыток;
 *  - [parallel] — сколько запросов идёт одновременно (больше — быстрее, но менее стабильно);
 *  - [timeoutSec] — сколько ждать ответа домена;
 *  - [groups] — какие встроенные списки доменов проверять (ключи [DpiStrategies.SITES]); [custom] — свои списки;
 *  - [customStrategiesOn] + [customStrategies] — проверять не встроенные 60 стратегий, а свой список (по одной в строке).
 */
data class DpiProbeSettings(
    val delaySec: Int = 1,
    val requests: Int = 1,
    val parallel: Int = 16,
    val timeoutSec: Int = 4,
    val groups: Set<String> = setOf("youtube", "discord", "telegram"),
    val custom: List<CustomSiteList> = emptyList(),
    val customStrategiesOn: Boolean = false,
    val customStrategies: String = "",
) {
    /** Выбранные списки: название → домены (порядок как в [DpiStrategies.GROUP_ORDER], потом свои). */
    fun selectedSites(): Map<String, List<String>> {
        val out = linkedMapOf<String, List<String>>()
        DpiStrategies.GROUP_ORDER.forEach { g -> if (g in groups) DpiStrategies.SITES[g]?.let { out[g] = it } }
        custom.forEach { c -> if (c.name in groups && c.domains.isNotEmpty()) out[c.name] = c.domains }
        return out
    }

    /** Свои стратегии: непустые строки, без повторов. */
    fun strategyLines(): List<String> = customStrategies.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct()

    fun toJson(): JSONObject = JSONObject()
        .put("delay", delaySec).put("requests", requests).put("parallel", parallel).put("timeout", timeoutSec)
        .put("groups", JSONArray(groups.toList()))
        .put("custom", JSONArray().apply { custom.forEach { put(JSONObject().put("name", it.name).put("domains", JSONArray(it.domains))) } })
        .put("custom_strategies_on", customStrategiesOn).put("custom_strategies", customStrategies)

    companion object {
        fun fromJson(o: JSONObject?): DpiProbeSettings {
            if (o == null) return DpiProbeSettings()
            val d = DpiProbeSettings()
            val g = o.optJSONArray("groups")
            val c = o.optJSONArray("custom")
            return DpiProbeSettings(
                delaySec = o.optInt("delay", d.delaySec).coerceIn(0, 30),
                requests = o.optInt("requests", d.requests).coerceIn(1, 5),
                parallel = o.optInt("parallel", d.parallel).coerceIn(1, 50),
                timeoutSec = o.optInt("timeout", d.timeoutSec).coerceIn(1, 20),
                groups = if (g == null) d.groups else (0 until g.length()).map { g.getString(it) }.toSet(),
                custom = if (c == null) emptyList() else (0 until c.length()).mapNotNull { i ->
                    val x = c.getJSONObject(i)
                    val name = x.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val dm = x.optJSONArray("domains") ?: JSONArray()
                    CustomSiteList(name, (0 until dm.length()).map { dm.getString(it) })
                },
                customStrategiesOn = o.optBoolean("custom_strategies_on", false),
                customStrategies = o.optString("custom_strategies", ""),
            )
        }
    }
}
