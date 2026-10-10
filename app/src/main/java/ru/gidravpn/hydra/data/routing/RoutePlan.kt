package ru.gidravpn.hydra.data.routing

import org.json.JSONArray
import org.json.JSONObject
import ru.gidravpn.hydra.data.dpi.DpiSettings

/**
 * Полная маршрутизация как на роутерах (0.7.13): несколько выходов одновременно, цепочки любой глубины,
 * группы выходов (автовыбор лучшего / ручной выбор), условия «И» / «НЕ», geo-базы в правилах и в DNS.
 *
 * План накладывается на готовый конфиг sing-box ([RoutePlanApplier]) — поэтому одинаково работает на Android, ПК и iOS
 * и не зависит от того, каким движком поднят основной сервер.
 */
enum class RouteKind(val id: String) {
    APP("app"),          // Android: package_name
    PROCESS("process"),  // ПК: process_name
    DOMAIN("domain"),
    SUFFIX("suffix"),
    KEYWORD("keyword"),
    REGEX("regex"),
    CIDR("cidr"),
    SRC_CIDR("src_cidr"),// адрес клиента: раздача в LAN, хотспот
    PORT("port"),
    PROTOCOL("protocol"),// определённый sniff-ом: tls, http, quic, dns, bittorrent…
    NETWORK("network"),  // tcp | udp
    GEOIP("geoip"),      // значение — код страны или имя набора (ru, ru-blocked)
    GEOSITE("geosite");  // значение — имя набора (category-ru, youtube, ru-blocked)

    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } }
}

/** Выходы. Остальные — узлы из списка серверов ([node]) и группы ([group]). */
object RouteTarget {
    const val PROXY = "proxy"
    const val DIRECT = "direct"
    const val DPI = "dpi"
    /** «Telegram через WebSocket» (0.7.14): локальный SOCKS5 [ru.gidravpn.hydra.data.tgws.TgWsProxy]. */
    const val TGWS = "tgws"
    const val TGWS_PORT = 10881
    /** MTProto-прокси того же TG WS (0.7.20): `tg://proxy` с секретом - его без сбоев принимает и Telegram для Android. */
    const val TGWS_MT_PORT = 10870
    const val BLOCK = "block"
    private const val NODE_PREFIX = "node-"
    private const val GROUP_PREFIX = "grp-"

    fun node(profileId: Long) = "$NODE_PREFIX$profileId"
    fun nodeId(target: String): Long? = target.takeIf { it.startsWith(NODE_PREFIX) }?.substring(NODE_PREFIX.length)?.toLongOrNull()
    fun group(name: String) = GROUP_PREFIX + name.trim().lowercase().replace(Regex("[^a-z0-9а-яё_-]+"), "-").trim('-')
    fun isGroup(target: String) = target.startsWith(GROUP_PREFIX)
}

/**
 * Одно условие. Условия с одинаковыми [group] (не пустой) и [target] склеиваются в одно правило по «И»
 * (например: приложение Telegram И порт 443); [invert] — «НЕ».
 */
data class RouteRule(val kind: RouteKind, val value: String, val target: String, val invert: Boolean = false, val group: String = "")

/** Группа выходов: `urltest` — сам выбирает самый быстрый из живых (отказоустойчивость), `selector` — выбирает человек (по умолчанию первый). */
data class RouteGroup(
    val name: String,
    val type: String = TYPE_URLTEST,
    val members: List<String> = emptyList(),
    val url: String = "https://www.gstatic.com/generate_204",
    val intervalSec: Int = 300,
) {
    val tag get() = RouteTarget.group(name)

    companion object {
        const val TYPE_URLTEST = "urltest"
        const val TYPE_SELECTOR = "selector"
    }
}

/** Дополнительный выход: готовый outbound sing-box с тегом [tag]; [via] — через что он сам ходит к своему серверу. */
data class RouteNode(val tag: String, val outbound: JSONObject, val via: String? = null)

data class RoutePlan(
    val rules: List<RouteRule> = emptyList(),
    val nodes: List<RouteNode> = emptyList(),
    val groups: List<RouteGroup> = emptyList(),
    val dpi: DpiSettings = DpiSettings(),
    /** Выход, через который основной сервер ходит к своему серверу («цепочка»): [RouteTarget.DPI] или `node-<id>`. */
    val proxyVia: String? = null,
) {
    val isEmpty get() = rules.isEmpty() && nodes.isEmpty() && groups.isEmpty() && !dpi.enabled && proxyVia == null

    /** Нужен ли локальный ByeDPI: «напрямую» через него, правило/группа/узел на выход «dpi» или цепочка «DPI → сервер». */
    val needsDpi
        get() = dpi.enabled || proxyVia == RouteTarget.DPI || rules.any { it.target == RouteTarget.DPI } ||
            nodes.any { it.via == RouteTarget.DPI } || groups.any { RouteTarget.DPI in it.members }

    /** Нужен ли локальный TG WS: правило, группа или узел с выходом «tgws». */
    val needsTgWs
        get() = rules.any { it.target == RouteTarget.TGWS } || groups.any { RouteTarget.TGWS in it.members }

    /** План без TG WS: если он не запустился (порт занят), соединение всё равно поднимается. */
    fun withoutTgWs() = copy(
        rules = rules.filter { it.target != RouteTarget.TGWS },
        groups = groups.map { g -> g.copy(members = g.members.filter { it != RouteTarget.TGWS }) },
    )

    /** План без всего, что опирается на ByeDPI: если он не запустился, соединение всё равно поднимается. */
    fun withoutDpi() = copy(
        dpi = dpi.copy(enabled = false),
        proxyVia = proxyVia?.takeIf { it != RouteTarget.DPI },
        rules = rules.filter { it.target != RouteTarget.DPI },
        nodes = nodes.map { if (it.via == RouteTarget.DPI) it.copy(via = null) else it },
        groups = groups.map { g -> g.copy(members = g.members.filter { it != RouteTarget.DPI }) },
    )
}

object RoutePlanApplier {

    /** Протоколы sing-box, которые можно вести через другой outbound по TCP (`detour`). UDP-протоколы цепочку не терпят. */
    private val CHAINABLE = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "ssh")
    private val DOMAINISH = setOf(RouteKind.DOMAIN, RouteKind.SUFFIX, RouteKind.KEYWORD, RouteKind.REGEX, RouteKind.GEOSITE)

    /**
     * Дополняет [root] (результат `SingBoxConfigBuilder`) планом [plan].
     * @param geoPath путь к базе для правила страны/набора (null — базы нет, правило пропускается с предупреждением).
     * @return предупреждения для журнала: что из плана не применено и почему.
     */
    fun apply(root: JSONObject, plan: RoutePlan, geoPath: (RouteKind, String) -> String? = { _, _ -> null }): List<String> {
        if (plan.isEmpty) return emptyList()
        val warn = mutableListOf<String>()
        val outbounds = root.getJSONArray("outbounds")
        val route = root.getJSONObject("route")
        val rules = route.getJSONArray("rules")

        val tags = (0 until outbounds.length()).map { outbounds.getJSONObject(it).optString("tag") }.toMutableSet()
        val byTag = { t: String -> (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.firstOrNull { it.optString("tag") == t } }
        fun addOutbound(o: JSONObject) { if (tags.add(o.getString("tag"))) outbounds.put(o) }

        if (plan.needsDpi) addOutbound(dpiOutbound(plan.dpi.port))
        if (plan.needsTgWs) addOutbound(tgWsOutbound())
        plan.nodes.forEach { addOutbound(it.outbound) }

        // Цепочки: «выход → через выход». У основного сервера и у каждого узла своя; глубина любая, петли отсекаются.
        fun chain(tag: String, via: String?) {
            if (via == null) return
            val o = byTag(tag) ?: return
            when {
                via !in tags -> warn += "цепочка «$via → $tag» не применена: выхода «$via» нет"
                via == tag -> warn += "цепочка «$tag → $tag» не применена: выход ведёт сам в себя"
                o.optString("type") !in CHAINABLE ->
                    warn += "цепочка не применена к «$tag»: протокол ${o.optString("type")} работает по UDP — через другой выход его не провести"
                loops(tag, via, byTag) -> warn += "цепочка «$via → $tag» не применена: получилась бы петля"
                else -> o.put("detour", via)
            }
        }
        plan.nodes.forEach { chain(it.tag, it.via) }
        chain(RouteTarget.PROXY, plan.proxyVia)

        // Группы выходов. Члены, которых нет, пропускаются; пустая группа не создаётся.
        for (g in plan.groups) {
            val members = g.members.filter { it in tags && it != g.tag }
            if (members.isEmpty()) { warn += "группа «${g.name}» пропущена: в ней нет доступных выходов"; continue }
            val o = JSONObject().put("tag", g.tag).put("outbounds", JSONArray(members))
            if (g.type == RouteGroup.TYPE_SELECTOR) o.put("type", "selector").put("default", members.first())
            else o.put("type", "urltest").put("url", g.url).put("interval", "${g.intervalSec.coerceIn(30, 3600)}s").put("tolerance", 100)
            addOutbound(o)
        }

        // Правила: сразу после sniff и hijack-dns, до пользовательского split tunneling.
        var at = 0
        for (i in 0 until rules.length()) {
            val r = rules.getJSONObject(i)
            if (r.optString("action") == "sniff" || r.optString("action") == "hijack-dns") at = i + 1
        }
        val ruleSets = route.optJSONArray("rule_set") ?: JSONArray()
        val knownSets = (0 until ruleSets.length()).map { ruleSets.getJSONObject(it).optString("tag") }.toMutableSet()

        /** Условие → (поле sing-box, значение); null — пропущено (причина в [warn]). */
        fun cond(r: RouteRule): Pair<String, Any>? {
            val v = r.value.trim()
            return when (r.kind) {
                RouteKind.APP -> "package_name" to v
                RouteKind.PROCESS -> "process_name" to v
                RouteKind.DOMAIN -> "domain" to v
                RouteKind.SUFFIX -> "domain_suffix" to v.removePrefix(".")
                RouteKind.KEYWORD -> "domain_keyword" to v
                RouteKind.REGEX -> "domain_regex" to v
                RouteKind.CIDR -> "ip_cidr" to v
                RouteKind.SRC_CIDR -> "source_ip_cidr" to v
                RouteKind.PROTOCOL -> "protocol" to v.lowercase()
                RouteKind.NETWORK -> v.lowercase().takeIf { it == "tcp" || it == "udp" }?.let { "network" to it }
                RouteKind.PORT -> if ('-' in v) "port_range" to v.replace('-', ':') else v.toIntOrNull()?.let { "port" to it }
                RouteKind.GEOIP, RouteKind.GEOSITE -> {
                    val name = v.lowercase()
                    val tag = "${r.kind.id}-$name"
                    if (tag !in knownSets) {
                        val path = geoPath(r.kind, name)
                        if (path == null) { warn += "правило «${r.kind.id} $name» пропущено: нет базы"; return null }
                        ruleSets.put(JSONObject().put("type", "local").put("tag", tag)
                            .put("format", if (path.endsWith(".json")) "source" else "binary").put("path", path))
                        knownSets += tag
                    }
                    "rule_set" to tag
                }
            }
        }

        // Порядок сохраняем; склеиваем только условия одной непустой группы (первая встреча задаёт место правила).
        val sequence = mutableListOf<List<RouteRule>>()
        val byGroup = linkedMapOf<Pair<String, String>, MutableList<RouteRule>>()
        for (r in plan.rules) {
            if (r.value.isBlank()) continue
            if (r.target != RouteTarget.BLOCK && r.target !in tags) { warn += "правило «${r.kind.id} ${r.value.trim()}» пропущено: выхода «${r.target}» нет"; continue }
            if (r.group.isBlank()) sequence += listOf(r)
            else {
                val key = r.group to r.target
                byGroup.getOrPut(key) { mutableListOf<RouteRule>().also { sequence += it } } += r
            }
        }

        val built = mutableListOf<JSONObject>()
        val dnsRules = mutableListOf<JSONObject>()
        for (conds in sequence) {
            val target = conds.first().target
            val plain = JSONObject()
            val negated = JSONObject()
            var ok = true
            for (c in conds) {
                val kv = cond(c)
                if (kv == null) { ok = false; break }
                val dest = if (c.invert) negated else plain
                (dest.optJSONArray(kv.first) ?: JSONArray().also { dest.put(kv.first, it) }).put(kv.second)
            }
            if (!ok || (plain.length() == 0 && negated.length() == 0)) continue
            val dnsMatch = JSONObject(plain.toString())   // до того, как к правилу добавится outbound
            val rule = if (negated.length() == 0) plain else {
                // «НЕ» — логическое правило: (прямые условия) И НЕ (каждое из инвертированных)
                val parts = JSONArray()
                if (plain.length() > 0) parts.put(plain)
                negated.keys().forEach { k -> parts.put(JSONObject().put(k, negated.getJSONArray(k)).put("invert", true)) }
                JSONObject().put("type", "logical").put("mode", "and").put("rules", parts)
            }
            if (target == RouteTarget.BLOCK) rule.put("action", "reject") else rule.put("outbound", target)
            built += rule
            // DNS: имена «напрямую» разрешаем локальным резолвером, а не через прокси.
            if (conds.size == 1 && !conds[0].invert && conds[0].kind in DOMAINISH && (target == RouteTarget.DIRECT || target == RouteTarget.DPI)) {
                dnsRules += dnsMatch.put("server", "local")
            }
        }
        if (ruleSets.length() > 0 && !route.has("rule_set")) route.put("rule_set", ruleSets)

        val old = (0 until rules.length()).map { rules.get(it) }
        val out = JSONArray()
        (old.take(at) + built + old.drop(at)).forEach { out.put(it) }
        route.put("rules", out)

        if (dnsRules.isNotEmpty()) {
            root.optJSONObject("dns")?.let { dns ->
                val cur = dns.optJSONArray("rules") ?: JSONArray()
                val merged = JSONArray()
                dnsRules.forEach { merged.put(it) }
                for (i in 0 until cur.length()) merged.put(cur.get(i))
                dns.put("rules", merged)
            }
        }

        // «Напрямую» через обход DPI (локальные адреса остаются прямыми).
        if (plan.dpi.enabled && plan.dpi.directViaDpi) {
            for (i in 0 until out.length()) {
                val r = out.get(i) as? JSONObject ?: continue
                if (r.optString("outbound") == "direct" && !r.optBoolean("ip_is_private", false)) r.put("outbound", RouteTarget.DPI)
            }
            if (route.optString("final") == "direct") route.put("final", RouteTarget.DPI)
        }
        return warn
    }

    /** Петля: идя по цепочке от [via] вверх, возвращаемся к [tag]. */
    private fun loops(tag: String, via: String, byTag: (String) -> JSONObject?): Boolean {
        var cur: String? = via
        var guard = 0
        while (cur != null && guard++ < 16) {
            if (cur == tag) return true
            cur = byTag(cur)?.optString("detour")?.takeIf { it.isNotEmpty() }
        }
        return false
    }

    fun tgWsOutbound(port: Int = RouteTarget.TGWS_PORT): JSONObject = JSONObject()
        .put("type", "socks").put("tag", RouteTarget.TGWS)
        .put("server", "127.0.0.1").put("server_port", port).put("version", "5")

    fun dpiOutbound(port: Int): JSONObject = JSONObject()
        .put("type", "socks").put("tag", RouteTarget.DPI)
        .put("server", "127.0.0.1").put("server_port", port).put("version", "5")
}
