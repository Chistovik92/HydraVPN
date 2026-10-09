package ru.gidravpn.hydra.data.routing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.gidravpn.hydra.data.dpi.DpiArgs
import ru.gidravpn.hydra.data.dpi.DpiSettings
import ru.gidravpn.hydra.data.dpi.DpiStrategies
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.SplitTunnel
import ru.gidravpn.hydra.data.model.NetRuleType
import ru.gidravpn.hydra.data.model.NetworkRule
import ru.gidravpn.hydra.data.model.SplitTunnelMode
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder
import java.io.File

/** Маршрутизация через несколько выходов, цепочки и обход DPI (0.7.12). Конфиги пишутся в build/singbox-configs для `sing-box check`. */
class RoutePlanTest {

    private val vless = ServerProfile(
        name = "main", protocolId = "vless", address = "203.0.113.10", port = 443,
        uuidOrPassword = "b831381d-6324-4d53-ad4f-8cda48b30811", security = "tls", sni = "example.com",
    )
    private val trojan = ServerProfile(
        name = "second", protocolId = "trojan", address = "203.0.113.20", port = 443,
        uuidOrPassword = "secret", security = "tls", sni = "example.org",
    )

    private fun base(split: SplitTunnel = SplitTunnel()) = SingBoxConfigBuilder.build(vless, splitTunnel = split)

    private fun JSONObject.rules() = getJSONObject("route").getJSONArray("rules").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun JSONObject.outs() = getJSONArray("outbounds").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun JSONObject.strs(key: String) = getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }

    private fun dump(name: String, cfg: JSONObject): JSONObject {
        File("build/singbox-configs").apply { mkdirs() }.resolve("$name.json").writeText(cfg.toString(2))
        return cfg
    }

    private fun nodeOf(p: ServerProfile, id: Long): RouteNode {
        val o = SingBoxConfigBuilder.build(p).outs().first { it.getString("tag") == "proxy" }
        return RouteNode(RouteTarget.node(id), JSONObject(o.toString()).put("tag", RouteTarget.node(id)))
    }

    @Test fun emptyPlanChangesNothing() {
        val cfg = base()
        val before = cfg.toString()
        assertTrue(RoutePlanApplier.apply(cfg, RoutePlan()).isEmpty())
        assertEquals(before, cfg.toString())
    }

    @Test fun appsAndDomainsGoToDifferentOutbounds() {
        val cfg = base()
        val plan = RoutePlan(
            rules = listOf(
                RouteRule(RouteKind.APP, "org.telegram.messenger", RouteTarget.node(7)),
                RouteRule(RouteKind.SUFFIX, ".ru", RouteTarget.DIRECT),
                RouteRule(RouteKind.PORT, "6881-6999", RouteTarget.BLOCK),
                RouteRule(RouteKind.CIDR, "198.51.100.0/24", RouteTarget.PROXY),
            ),
            nodes = listOf(nodeOf(trojan, 7)),
        )
        assertTrue(RoutePlanApplier.apply(cfg, plan).isEmpty())
        dump("route_multi", cfg)
        assertEquals(listOf("proxy", "direct", "node-7"), cfg.outs().map { it.getString("tag") })
        val rules = cfg.rules()
        // sniff и hijack-dns остаются первыми, наши правила — сразу за ними
        assertEquals("sniff", rules[0].getString("action"))
        assertEquals("hijack-dns", rules[1].getString("action"))
        assertEquals(listOf("org.telegram.messenger"), rules[2].strs("package_name"))
        assertEquals("node-7", rules[2].getString("outbound"))
        assertEquals(listOf("ru"), rules[3].strs("domain_suffix"))
        assertEquals("direct", rules[3].getString("outbound"))
        assertEquals(listOf("6881:6999"), rules[4].strs("port_range"))
        assertEquals("reject", rules[4].getString("action"))
        assertFalse(rules[4].has("outbound"))
    }

    @Test fun ruleToUnknownOutboundIsSkippedWithWarning() {
        val cfg = base()
        val warn = RoutePlanApplier.apply(cfg, RoutePlan(rules = listOf(RouteRule(RouteKind.DOMAIN, "a.com", "node-99"))))
        assertEquals(1, warn.size)
        assertEquals(2, cfg.rules().count { it.has("action") })
        assertFalse(cfg.rules().any { it.has("domain") })
    }

    @Test fun chainThroughDpiAddsDetour() {
        val cfg = base()
        val plan = RoutePlan(dpi = DpiSettings(enabled = true, directViaDpi = false), proxyVia = RouteTarget.DPI)
        assertTrue(RoutePlanApplier.apply(cfg, plan).isEmpty())
        dump("route_chain_dpi", cfg)
        val outs = cfg.outs()
        assertEquals("dpi", outs.first { it.getString("tag") == "proxy" }.getString("detour"))
        val dpi = outs.first { it.getString("tag") == "dpi" }
        assertEquals("socks", dpi.getString("type"))
        assertEquals(DpiSettings.DEFAULT_PORT, dpi.getInt("server_port"))
    }

    @Test fun chainThroughAnotherNode() {
        val cfg = base()
        val plan = RoutePlan(nodes = listOf(nodeOf(trojan, 3)), proxyVia = RouteTarget.node(3))
        assertTrue(RoutePlanApplier.apply(cfg, plan).isEmpty())
        dump("route_chain_node", cfg)
        assertEquals("node-3", cfg.outs().first { it.getString("tag") == "proxy" }.getString("detour"))
    }

    @Test fun udpProtocolIsNotChained() {
        val hy2 = ServerProfile(name = "h", protocolId = "hysteria2", address = "203.0.113.30", port = 443, uuidOrPassword = "p", sni = "x.com")
        val cfg = SingBoxConfigBuilder.build(hy2)
        val warn = RoutePlanApplier.apply(cfg, RoutePlan(dpi = DpiSettings(enabled = true), proxyVia = RouteTarget.DPI))
        assertEquals(1, warn.size)
        assertFalse(cfg.outs().first { it.getString("tag") == "proxy" }.has("detour"))
    }

    @Test fun directTrafficGoesThroughDpiButPrivateStaysDirect() {
        val split = SplitTunnel(netMode = SplitTunnelMode.EXCLUDE, netRules = listOf(NetworkRule(NetRuleType.DOMAIN_SUFFIX, "ya.ru")))
        val cfg = base(split)
        RoutePlanApplier.apply(cfg, RoutePlan(dpi = DpiSettings(enabled = true, directViaDpi = true)))
        dump("route_direct_via_dpi", cfg)
        val rules = cfg.rules()
        assertEquals("dpi", rules.first { it.has("domain_suffix") }.getString("outbound"))
        assertEquals("direct", rules.first { it.optBoolean("ip_is_private") }.getString("outbound"))
    }

    @Test fun geoRulesGetRuleSetsAndMissingBaseIsSkipped() {
        val cfg = base()
        val ru = File("src/main/assets/geoip/ru.srs").absolutePath
        val warn = RoutePlanApplier.apply(
            cfg,
            RoutePlan(rules = listOf(
                RouteKind.GEOIP.let { RouteRule(it, "RU", RouteTarget.DIRECT) },
                RouteRule(RouteKind.GEOSITE, "zz", RouteTarget.DIRECT),
            )),
        ) { kind, cc -> if (kind == RouteKind.GEOIP && cc == "ru") ru else null }
        dump("route_geo_rule", cfg)
        assertEquals(1, warn.size)
        val sets = cfg.getJSONObject("route").getJSONArray("rule_set")
        assertEquals("geoip-ru", sets.getJSONObject(0).getString("tag"))
        assertEquals(1, sets.length())
    }


    // --- 0.7.13: полная маршрутизация ---

    @Test fun andGroupAndNegationBecomeLogicalRules() {
        val cfg = base()
        val plan = RoutePlan(
            rules = listOf(
                RouteRule(RouteKind.APP, "org.telegram.messenger", RouteTarget.DIRECT, group = "tg"),
                RouteRule(RouteKind.PORT, "443", RouteTarget.DIRECT, group = "tg"),
                RouteRule(RouteKind.NETWORK, "udp", RouteTarget.BLOCK, invert = true),
            ),
        )
        assertTrue(RoutePlanApplier.apply(cfg, plan).isEmpty())
        dump("route_logic", cfg)
        val rules = cfg.rules()
        val and = rules[2]
        assertEquals(listOf("org.telegram.messenger"), and.strs("package_name"))
        assertEquals(listOf(443), (0 until and.getJSONArray("port").length()).map { and.getJSONArray("port").getInt(it) })
        assertEquals("direct", and.getString("outbound"))
        val neg = rules[3]
        assertEquals("logical", neg.getString("type"))
        assertEquals("reject", neg.getString("action"))
        assertTrue(neg.getJSONArray("rules").getJSONObject(0).getBoolean("invert"))
    }

    @Test fun urltestAndSelectorGroups() {
        val cfg = base()
        val g1 = RouteGroup("Fast", RouteGroup.TYPE_URLTEST, listOf(RouteTarget.PROXY, RouteTarget.node(1), "node-99"))
        val g2 = RouteGroup("Manual", RouteGroup.TYPE_SELECTOR, listOf(RouteTarget.DIRECT, g1.tag))
        val plan = RoutePlan(
            rules = listOf(RouteRule(RouteKind.SUFFIX, "example.com", g2.tag)),
            nodes = listOf(nodeOf(trojan, 1)), groups = listOf(g1, g2),
        )
        assertTrue(RoutePlanApplier.apply(cfg, plan).isEmpty())
        dump("route_groups", cfg)
        val urltest = cfg.outs().first { it.getString("tag") == g1.tag }
        assertEquals("urltest", urltest.getString("type"))
        assertEquals(listOf("proxy", "node-1"), urltest.strs("outbounds"))   // отсутствующий узел отброшен
        val sel = cfg.outs().first { it.getString("tag") == g2.tag }
        assertEquals("direct", sel.getString("default"))
        assertEquals(g2.tag, cfg.rules()[2].getString("outbound"))
    }

    @Test fun nodeChainsOfAnyDepthAndLoopsAreCut() {
        val cfg = base()
        val a = nodeOf(trojan, 1); val b = nodeOf(trojan, 2); val c = nodeOf(trojan, 3)
        val plan = RoutePlan(
            nodes = listOf(RouteNode(a.tag, a.outbound, RouteTarget.node(2)), RouteNode(b.tag, b.outbound, RouteTarget.node(3)),
                RouteNode(c.tag, c.outbound, RouteTarget.node(1))),   // 1→2→3→1: последнее звено — петля
            proxyVia = RouteTarget.node(1),
        )
        val warn = RoutePlanApplier.apply(cfg, plan)
        dump("route_deep_chain", cfg)
        val o = cfg.outs().associateBy { it.getString("tag") }
        assertEquals("node-1", o.getValue("proxy").getString("detour"))
        assertEquals("node-2", o.getValue("node-1").getString("detour"))
        assertEquals("node-3", o.getValue("node-2").getString("detour"))
        assertFalse(o.getValue("node-3").has("detour"))
        assertEquals(1, warn.size)
    }

    @Test fun domainRulesToDirectGetLocalDnsRule() {
        val cfg = base()
        val ru = File("src/main/assets/geoip/ru.srs").absolutePath
        RoutePlanApplier.apply(cfg, RoutePlan(rules = listOf(
            RouteRule(RouteKind.SUFFIX, "ya.ru", RouteTarget.DIRECT),
            RouteRule(RouteKind.GEOSITE, "category-ru", RouteTarget.DIRECT),
            RouteRule(RouteKind.SUFFIX, "x.com", RouteTarget.PROXY),
        ))) { _, _ -> ru }
        dump("route_dns_rules", cfg)
        val dnsRules = cfg.getJSONObject("dns").getJSONArray("rules")
        assertEquals(2, dnsRules.length())
        assertEquals("local", dnsRules.getJSONObject(0).getString("server"))
        assertFalse(dnsRules.getJSONObject(0).has("outbound"))   // legacy-поле outbound в DNS-правилах удаляется в sing-box 1.14
    }

    @Test fun routeConfigRoundTripKeepsEverything() {
        val cfg = RouteConfig(
            rules = listOf(RouteRule(RouteKind.GEOSITE, "ru-blocked", RouteTarget.DPI), RouteRule(RouteKind.PORT, "80", RouteTarget.BLOCK, invert = true, group = "g")),
            groups = listOf(RouteGroup("Fast", RouteGroup.TYPE_SELECTOR, listOf("proxy", "node-5"), intervalSec = 120)),
            dpi = DpiSettings(true, "-o 2", 10999, false),
        )
        assertEquals(cfg, RouteConfig.fromJson(cfg.toJson()))
        assertEquals(RouteConfig(), RouteConfig.fromJson("не json"))
    }

    @Test fun factoryPullsInNodesGroupsAndTheirChains() {
        val profiles = mapOf(
            2L to withVia(trojan.copy(id = 2), RouteTarget.node(3)),
            3L to trojan.copy(id = 3, address = "203.0.113.30"),
            4L to trojan.copy(id = 4, address = "203.0.113.40"),
        )
        val g = RouteGroup("Pool", members = listOf(RouteTarget.node(2), RouteTarget.node(4)))
        val cfg = RouteConfig(
            rules = listOf(RouteRule(RouteKind.SUFFIX, "a.com", g.tag), RouteRule(RouteKind.APP, "x.y", RouteTarget.DIRECT), RouteRule(RouteKind.PROCESS, "p.exe", RouteTarget.DIRECT)),
            groups = listOf(g, RouteGroup("Unused", members = listOf(RouteTarget.node(9)))),
        )
        val plan = RoutePlanFactory.build(cfg, withVia(vless.copy(id = 1), RouteTarget.DPI), setOf(RouteKind.APP, RouteKind.SUFFIX), lookup = { profiles[it] })
        assertEquals(listOf("node-2", "node-4", "node-3").sorted(), plan.nodes.map { it.tag }.sorted())
        assertEquals(listOf(g.tag), plan.groups.map { it.tag })          // неиспользуемая группа не тянет узлы
        assertEquals(2, plan.rules.size)                                  // PROCESS на Android отброшен
        assertEquals(RouteTarget.DPI, plan.proxyVia)
        assertEquals(RouteTarget.node(3), plan.nodes.first { it.tag == "node-2" }.via)
        val cfgOut = SingBoxConfigBuilder.build(vless)
        assertTrue(RoutePlanApplier.apply(cfgOut, plan).isEmpty())
        dump("route_factory_full", cfgOut)
    }

    // --- аргументы ByeDPI ---

    @Test fun dpiArgsDropDangerousAndOwnKeys() {
        val a = DpiArgs.build("ciadpi --ip 0.0.0.0 -p 1 --daemon -D -h -o1 -d3+s -w /tmp/pid -n {sni}", 10880)
        assertEquals(listOf("-i", "127.0.0.1", "-p", "10880", "-o1", "-d3+s", "-n", "max.ru"), a)
    }

    @Test fun dpiArgsHonourQuotesAndFallbackToDefault() {
        assertEquals(listOf("-H", "a b"), DpiArgs.sanitize("-H 'a b'"))
        assertEquals(listOf("-i", "127.0.0.1", "-p", "1", "-o", "2", "-d", "2"), DpiArgs.build("", 1))
    }

    @Test fun everyPresetIsUsableAndStaysLocal() {
        assertEquals(60, DpiStrategies.PRESETS.size)
        DpiStrategies.PRESETS.forEach { s ->
            assertTrue(s, DpiArgs.isUsable(s))
            assertFalse(s, DpiArgs.sanitize(s).any { it.contains("{sni}") })
        }
        assertTrue(DpiStrategies.SITES.values.all { it.isNotEmpty() })
    }

    @Test fun jsonArrayHelperSanity() {
        assertEquals(0, JSONArray().length())
    }
}
