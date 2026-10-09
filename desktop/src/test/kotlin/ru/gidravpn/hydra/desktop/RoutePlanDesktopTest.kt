package ru.gidravpn.hydra.desktop

import org.json.JSONArray
import org.json.JSONObject
import ru.gidravpn.hydra.data.dpi.DpiSettings
import ru.gidravpn.hydra.data.geo.GeoKind
import ru.gidravpn.hydra.data.geo.GeoStore
import ru.gidravpn.hydra.data.model.GeoRoutingMode
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.SplitTunnelMode
import ru.gidravpn.hydra.data.routing.RouteConfig
import ru.gidravpn.hydra.data.routing.RouteGroup
import ru.gidravpn.hydra.data.routing.RouteKind
import ru.gidravpn.hydra.data.routing.RoutePlanFactory
import ru.gidravpn.hydra.data.routing.RouteRule
import ru.gidravpn.hydra.data.routing.RouteTarget
import ru.gidravpn.hydra.data.routing.withVia
import ru.gidravpn.hydra.data.subscription.LinkParser
import ru.gidravpn.hydra.desktop.core.DesktopConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ПК: правила по программам, группы, цепочки, ByeDPI и скачанные geo-базы поверх конфига sing-box (0.7.13). */
class RoutePlanDesktopTest {

    private val api = DesktopConfig.Api(9090, "secret")
    private val dumpDir = System.getProperty("hydra.configDump")?.let { File(it).apply { mkdirs() } }

    private fun profile(link: String, id: Long) = assertNotNull(LinkParser.parseLine(link)).copy(id = id)
    private val vless = profile("vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=tls&sni=example.com#Main", 1)
    private val trojan = profile("trojan://secret@node.example.org:443?sni=node.example.org#Second", 2)

    private fun JSONObject.arr(key: String) = getJSONArray(key).let { a -> (0 until a.length()).map { a.get(it) } }
    private fun JSONObject.objs(key: String) = arr(key).map { it as JSONObject }

    @Test
    fun `process rules stay first, plan rules follow and dns rules are local`() {
        val routes = RouteConfig(
            rules = listOf(
                RouteRule(RouteKind.PROCESS, "telegram.exe", RouteTarget.node(2)),
                RouteRule(RouteKind.SUFFIX, "ya.ru", RouteTarget.DIRECT),
                RouteRule(RouteKind.APP, "org.telegram.messenger", RouteTarget.DIRECT),   // Android-вид на ПК отбрасывается
            ),
        )
        val settings = DesktopSettings(mode = ConnectionMode.TUN, routing = RoutingSettings(
            appMode = SplitTunnelMode.EXCLUDE, apps = listOf("steam.exe"), routes = routes))
        val plan = RoutePlanFactory.build(routes, vless, setOf(RouteKind.PROCESS, RouteKind.SUFFIX), { id -> if (id == 2L) trojan else null })
        val cfg = DesktopConfig.build(vless, settings, Os.WINDOWS, api, null, null, listOf("C:/hydra.exe"), plan)
        dumpDir?.let { File(it, "desktop-routeplan.json").writeText(cfg.toString(2)) }
        val rules = cfg.getJSONObject("route").objs("rules")
        assertEquals("sniff", rules[0].getString("action"))
        assertEquals("hijack-dns", rules[1].getString("action"))
        // сначала «Hydra мимо туннеля» и «программы мимо VPN», потом правила плана
        assertTrue(rules[2].has("process_path"))
        assertTrue(rules.indexOfFirst { it.has("process_name") && it.optString("outbound") == "node-2" } > 2)
        assertEquals(1, cfg.getJSONObject("dns").objs("rules").count { it.has("domain_suffix") && it.getString("server") == "local" })
        assertTrue(cfg.objs("outbounds").any { it.getString("tag") == "node-2" })
        assertFalse(rules.any { it.has("package_name") })
    }

    @Test
    fun `byedpi chain groups and direct through dpi`() {
        val withChain = withVia(vless, RouteTarget.DPI)
        val routes = RouteConfig(
            rules = listOf(RouteRule(RouteKind.SUFFIX, "youtube.com", RouteTarget.DPI), RouteRule(RouteKind.SUFFIX, "corp.example", RouteTarget.group("Pool"))),
            groups = listOf(RouteGroup("Pool", members = listOf(RouteTarget.PROXY, RouteTarget.node(2)))),
            dpi = DpiSettings(enabled = true, strategy = "-o 2 -d 2", port = 10999, directViaDpi = true),
        )
        val settings = DesktopSettings(mode = ConnectionMode.PROXY, routing = RoutingSettings(routes = routes))
        val plan = RoutePlanFactory.build(routes, withChain, setOf(RouteKind.PROCESS, RouteKind.SUFFIX), { id -> if (id == 2L) trojan else null })
        assertTrue(plan.needsDpi)
        val warnings = mutableListOf<String>()
        val cfg = DesktopConfig.build(withChain, settings, Os.LINUX, api, null, null, emptyList(), plan, null, warnings::add)
        dumpDir?.let { File(it, "desktop-routeplan-dpi.json").writeText(cfg.toString(2)) }
        assertTrue(warnings.isEmpty(), warnings.toString())
        val outs = cfg.objs("outbounds").associateBy { it.getString("tag") }
        assertEquals("socks", outs.getValue("dpi").getString("type"))
        assertEquals(10999, outs.getValue("dpi").getInt("server_port"))
        assertEquals("dpi", outs.getValue("proxy").getString("detour"))
        assertEquals("urltest", outs.getValue("grp-pool").getString("type"))
        assertEquals("direct", outs.getValue("direct").getString("type"))
    }

    @Test
    fun `downloaded geo base wins over bundled and json source is accepted`() {
        val dir = Files.createTempDirectory("geo").toFile()
        val store = GeoStore(File(dir, "dyn"))
        val bundled = File(dir, "bundled").apply { File(this, "geoip").mkdirs() }
        File(bundled, "geoip/ru.srs").writeBytes(ByteArray(64) { if (it == 0) 'S'.code.toByte() else if (it == 1) 'R'.code.toByte() else if (it == 2) 'S'.code.toByte() else if (it == 3) 2 else 1 })
        assertEquals(File(bundled, "geoip/ru.srs"), DesktopConfig.geoFile(GeoKind.IP, "ru", bundled, store))
        store.install(GeoKind.IP, "ru", GeoStore.listToSource("10.0.0.0/8\n", GeoKind.IP), source = true, from = "custom")
        val f = DesktopConfig.geoFile(GeoKind.IP, "ru", bundled, store)
        assertNotNull(f); assertTrue(f.name.endsWith(".json"))

        val routes = RouteConfig(rules = listOf(RouteRule(RouteKind.GEOIP, "ru", RouteTarget.DIRECT)))
        val settings = DesktopSettings(routing = RoutingSettings(geoMode = GeoRoutingMode.DIRECT, geoCountries = listOf("ru"), routes = routes))
        val plan = RoutePlanFactory.build(routes, vless, setOf(RouteKind.PROCESS), { null })
        val cfg = DesktopConfig.build(vless, settings, Os.WINDOWS, api, bundled, null, emptyList(), plan, store)
        val sets = cfg.getJSONObject("route").objs("rule_set")
        assertTrue(sets.all { it.getString("format") == "source" && it.getString("path").endsWith(".json") }, sets.toString())
        // Конфиг не сбрасываем в файл для `sing-box check`: пути ведут во временный каталог, который удаляется ниже.
        dir.deleteRecursively()
    }

    @Test
    fun `settings round trip keeps routes and geo`() {
        val routes = RouteConfig(rules = listOf(RouteRule(RouteKind.PORT, "443", RouteTarget.BLOCK, invert = true, group = "x")),
            dpi = DpiSettings(true, "-o 1", 11000, false))
        val geo = ru.gidravpn.hydra.data.geo.GeoSettings(false, 12, "sagernet", "runetfreedom", listOf("ru-blocked"))
        val r = RoutingSettings(routes = routes, geo = geo)
        val back = Store.routingFromJson(Store.routingToJson(r))
        assertEquals(routes, back.routes)
        assertEquals(geo, back.geo)
        assertEquals(RoutingSettings().routes, Store.routingFromJson(JSONObject()).routes)
        assertEquals(JSONArray().length(), 0)
    }
}
