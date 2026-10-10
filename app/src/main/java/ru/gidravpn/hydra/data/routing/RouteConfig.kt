package ru.gidravpn.hydra.data.routing

import org.json.JSONArray
import org.json.JSONObject
import ru.gidravpn.hydra.data.dpi.DpiSettings
import ru.gidravpn.hydra.data.dpi.DpiStrategies
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder

/**
 * То, что пользователь настраивает (0.7.13): правила «что → через какой выход», группы выходов и обход DPI.
 * Хранится одной JSON-строкой (Android — DataStore, ПК — hydra.json, iOS — App Group), входит в резервную копию.
 * «Через что идёт сам сервер» хранится в профиле: `extra.via` = `dpi` | `node-<id>` (см. [viaOf]) — у любого сервера,
 * так что цепочки получаются любой глубины.
 */
data class RouteConfig(
    val rules: List<RouteRule> = emptyList(),
    val groups: List<RouteGroup> = emptyList(),
    val dpi: DpiSettings = DpiSettings(),
) {
    fun toJson(): String = JSONObject().apply {
        put("rules", JSONArray().apply {
            rules.forEach {
                put(JSONObject().put("kind", it.kind.id).put("value", it.value).put("target", it.target)
                    .apply { if (it.invert) put("invert", true); if (it.group.isNotBlank()) put("group", it.group) })
            }
        })
        put("groups", JSONArray().apply {
            groups.forEach {
                put(JSONObject().put("name", it.name).put("type", it.type).put("members", JSONArray(it.members))
                    .put("url", it.url).put("interval", it.intervalSec))
            }
        })
        put("dpi", JSONObject()
            .put("enabled", dpi.enabled).put("strategy", dpi.strategy).put("port", dpi.port)
            .put("direct_via_dpi", dpi.directViaDpi).put("fake_sni", dpi.fakeSni).put("probe", dpi.probe.toJson()))
    }.toString()

    companion object {
        fun fromJson(json: String?): RouteConfig = runCatching {
            val o = JSONObject(json ?: "{}")
            val arr = o.optJSONArray("rules") ?: JSONArray()
            val rules = (0 until arr.length()).mapNotNull { i ->
                val r = arr.getJSONObject(i)
                val kind = RouteKind.fromId(r.optString("kind")) ?: return@mapNotNull null
                RouteRule(kind, r.optString("value"), r.optString("target", RouteTarget.PROXY), r.optBoolean("invert", false), r.optString("group"))
            }
            val ga = o.optJSONArray("groups") ?: JSONArray()
            val groups = (0 until ga.length()).mapNotNull { i ->
                val g = ga.getJSONObject(i)
                val name = g.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val m = g.optJSONArray("members") ?: JSONArray()
                RouteGroup(name, g.optString("type", RouteGroup.TYPE_URLTEST), (0 until m.length()).map { m.getString(it) },
                    g.optString("url", "https://www.gstatic.com/generate_204"), g.optInt("interval", 300))
            }
            val d = o.optJSONObject("dpi")
            RouteConfig(
                rules, groups,
                if (d == null) DpiSettings() else DpiSettings(
                    enabled = d.optBoolean("enabled", false),
                    strategy = d.optString("strategy", DpiStrategies.DEFAULT).ifBlank { DpiStrategies.DEFAULT },
                    port = d.optInt("port", DpiSettings.DEFAULT_PORT).takeIf { it in 1024..65535 } ?: DpiSettings.DEFAULT_PORT,
                    directViaDpi = d.optBoolean("direct_via_dpi", true),
                    fakeSni = d.optString("fake_sni", DpiStrategies.FAKE_SNI).trim().ifEmpty { DpiStrategies.FAKE_SNI },
                    probe = ru.gidravpn.hydra.data.dpi.DpiProbeSettings.fromJson(d.optJSONObject("probe")),
                ),
            )
        }.getOrDefault(RouteConfig())
    }
}

/** Цепочка профиля: `dpi`, `node-<id>` или null. */
fun viaOf(p: ServerProfile): String? =
    runCatching { JSONObject(p.extra).optString("via") }.getOrNull()?.takeIf { it.isNotBlank() }

/** Записать цепочку в `extra` профиля (null/пусто — убрать). */
fun withVia(p: ServerProfile, via: String?): ServerProfile {
    val o = runCatching { JSONObject(p.extra) }.getOrDefault(JSONObject())
    if (via.isNullOrBlank()) o.remove("via") else o.put("via", via)
    return p.copy(extra = o.toString())
}

object RoutePlanFactory {

    private const val MAX_NODES = 24

    /**
     * Собирает [RoutePlan] для подключения [profile].
     * @param supported виды правил, которые умеет платформа: Android — [RouteKind.APP], ПК — [RouteKind.PROCESS], iOS — ни тот ни другой.
     * @param lookup профиль по id (для выходов «через другой сервер»).
     * @param nodeOutbound outbound для узла; по умолчанию — только протоколы sing-box. Платформа подменяет, чтобы OpenFlux, olcRTC и
     *   прочие клиенты-подпроцессы тоже могли быть выходами (она запускает подпроцесс и возвращает socks на его порт).
     */
    fun build(
        cfg: RouteConfig,
        profile: ServerProfile?,
        supported: Set<RouteKind>,
        lookup: (Long) -> ServerProfile?,
        nodeOutbound: (ServerProfile, String) -> JSONObject? = SingBoxConfigBuilder::nodeOutbound,
    ): RoutePlan {
        val rules = cfg.rules.filter { it.kind in supported }
        val via = profile?.let(::viaOf)

        // Группы, на которые кто-то ссылается (правилом, цепочкой, другой группой).
        val groupsByTag = cfg.groups.associateBy { it.tag }
        val usedGroups = linkedMapOf<String, RouteGroup>()
        fun useGroup(tag: String) {
            val g = groupsByTag[tag] ?: return
            if (usedGroups.put(tag, g) == null) g.members.filter(RouteTarget::isGroup).forEach(::useGroup)
        }
        (rules.map { it.target } + listOfNotNull(via)).filter(RouteTarget::isGroup).forEach(::useGroup)

        // Узлы: из правил, членов групп и цепочек — вместе с цепочками самих узлов.
        val queue = ArrayDeque((rules.map { it.target } + usedGroups.values.flatMap { it.members } + listOfNotNull(via)).distinct())
        val nodes = linkedMapOf<String, RouteNode>()
        while (queue.isNotEmpty() && nodes.size < MAX_NODES) {
            val target = queue.removeFirst()
            val id = RouteTarget.nodeId(target) ?: continue
            if (target in nodes || id == profile?.id) continue
            val p = lookup(id) ?: continue
            val o = nodeOutbound(p, target) ?: continue
            val nodeVia = viaOf(p)
            nodes[target] = RouteNode(target, o, nodeVia)
            if (nodeVia != null) queue.addLast(nodeVia)
        }
        return RoutePlan(rules = rules, nodes = nodes.values.toList(), groups = usedGroups.values.toList(), dpi = cfg.dpi, proxyVia = via)
    }
}
