package ru.gidravpn.hydra.desktop

import org.json.JSONArray
import org.json.JSONObject
import ru.gidravpn.hydra.data.model.EngineToggles
import ru.gidravpn.hydra.data.model.HotspotSettings
import ru.gidravpn.hydra.data.model.MtuPreset
import ru.gidravpn.hydra.data.model.NetRuleType
import ru.gidravpn.hydra.data.model.NetworkRule
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.SplitTunnelMode
import ru.gidravpn.hydra.data.subscription.LinkParser
import ru.gidravpn.hydra.data.subscription.XrayConfigBuilder
import ru.gidravpn.hydra.desktop.core.DesktopConfig
import ru.gidravpn.hydra.desktop.core.Rules
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Движок Xray, раздельное туннелирование по процессам и сайтам, валидация правил. */
class RoutingConfigTest {

    private val api = DesktopConfig.Api(9090, "secret")
    private val dumpDir = System.getProperty("hydra.configDump")?.let { File(it).apply { mkdirs() } }
    private val bridge = DesktopConfig.XrayBridge(10808, XrayConfigBuilder.SocksAuth("hydra", "pw"))

    private val xrayLinks = mapOf(
        "vless-reality" to "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=reality&pbk=Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw&sid=6ba85179e30d4fc2&sni=www.microsoft.com&fp=chrome&flow=xtls-rprx-vision#R",
        "vless-ws-tls" to "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=ws&security=tls&path=%2Fws&host=cdn.example.com&sni=cdn.example.com#WS",
        "vless-grpc" to "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=grpc&security=tls&serviceName=grpc&sni=example.com#gRPC",
        "vless-h2" to "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=http&security=tls&path=%2Fh&sni=example.com#H2",
        "trojan" to "trojan://p%40ss+word@example.com:443?sni=example.com#Trojan",
        "ss" to "ss://" + Base64.getUrlEncoder().withoutPadding().encodeToString("2022-blake3-aes-128-gcm:AAAAAAAAAAAAAAAAAAAAAA==".toByteArray()) + "@example.com:8388#SS",
        "vmess" to "vmess://" + Base64.getEncoder().encodeToString(
            """{"v":"2","ps":"VMess","add":"example.com","port":"443","id":"11111111-2222-3333-4444-555555555555","aid":"0","net":"ws","path":"/v","host":"example.com","tls":"tls","sni":"example.com"}""".toByteArray()),
    )

    private fun parse(link: String): ServerProfile = assertNotNull(LinkParser.parseLine(link)).copy(id = 1)

    private fun rules(cfg: JSONObject): List<JSONObject> = cfg.getJSONObject("route").getJSONArray("rules").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    @Test
    fun `engine selection follows toggles`() {
        val vless = parse(xrayLinks.getValue("vless-reality"))
        val hy2 = parse("hysteria2://pw@example.com:443?sni=example.com#HY2")
        val def = DesktopSettings()
        assertEquals(EngineToggles.Kind.SINGBOX, DesktopConfig.engineFor(vless, def, xrayAvailable = true))
        assertEquals(EngineToggles.Kind.XRAY, DesktopConfig.engineFor(vless, def.copy(preferXray = true), xrayAvailable = true))
        assertEquals(EngineToggles.Kind.SINGBOX, DesktopConfig.engineFor(vless, def.copy(preferXray = true), xrayAvailable = false))
        assertEquals(EngineToggles.Kind.XRAY, DesktopConfig.engineFor(vless, def.copy(singBoxEnabled = false), xrayAvailable = true))
        assertNull(DesktopConfig.engineFor(hy2, def.copy(singBoxEnabled = false), xrayAvailable = true), "Hysteria2 Xray не обслуживает")
    }

    /** Мост и конфиг Xray для каждого протокола; пишутся для `xray run -test` и `sing-box check` в CI. */
    @Test
    fun `xray bridge configs`() {
        for ((name, link) in xrayLinks) {
            val p = parse(link)
            for (mode in ConnectionMode.entries) {
                val settings = DesktopSettings(mode = mode, preferXray = true)
                val sb = DesktopConfig.build(p, settings, Os.WINDOWS, api, null, bridge, listOf("C:\\Hydra\\Hydra.exe", "C:\\Hydra\\xray.exe"))
                val proxy = sb.getJSONArray("outbounds").getJSONObject(0)
                assertEquals("socks", proxy.getString("type"))
                assertEquals(10808, proxy.getInt("server_port"))
                assertEquals("hydra", proxy.getString("username"))
                assertEquals("pw", proxy.getString("password"))
                val bypass = rules(sb).firstOrNull { it.has("process_path") }
                assertEquals(mode == ConnectionMode.TUN, bypass != null, "обход туннеля для Hydra/Xray — только в TUN")

                val xr = DesktopConfig.xray(p, settings, bridge, resolvedIp = if (mode == ConnectionMode.TUN) "203.0.113.7" else null)
                val inbound = xr.getJSONArray("inbounds").getJSONObject(0)
                assertEquals("127.0.0.1", inbound.getString("listen"))
                assertEquals("password", inbound.getJSONObject("settings").getString("auth"))
                val out = xr.getJSONArray("outbounds").getJSONObject(0)
                val server = out.getJSONObject("settings").let { it.optJSONArray("vnext") ?: it.getJSONArray("servers") }.getJSONObject(0)
                if (mode == ConnectionMode.TUN) {
                    assertEquals("203.0.113.7", server.getString("address"), "в TUN адрес разрешён заранее")
                    out.optJSONObject("streamSettings")?.let { st ->
                        val tls = st.optJSONObject("tlsSettings") ?: st.optJSONObject("realitySettings")
                        if (tls != null) assertFalse(tls.getString("serverName").startsWith("203."), "имя сервера остаётся в SNI")
                    }
                }
                dumpDir?.let {
                    File(it, "desktop-xraybridge-$name-${mode.name.lowercase()}.json").writeText(sb.toString(2))
                    File(it.parentFile, "xray-configs").apply { mkdirs() }
                        .resolve("xray-$name-${mode.name.lowercase()}.json").writeText(xr.toString(2))
                }
            }
        }
        // http/h2 в новом Xray удалён — транслируется в XHTTP.
        val h2 = DesktopConfig.xray(parse(xrayLinks.getValue("vless-h2")), DesktopSettings(), bridge)
        assertEquals("xhttp", h2.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings").getString("network"))
    }

    @Test
    fun `process rules exclude and include`() {
        val p = parse(xrayLinks.getValue("trojan"))
        for (os in Os.entries) for (mode in ConnectionMode.entries) {
            val apps = listOf("chrome.exe", if (os == Os.WINDOWS) "C:\\Program Files\\App\\app.exe" else "/usr/bin/app")
            val local = if (mode == ConnectionMode.TUN) "tun-in" else "mixed-in"
            for (split in listOf(SplitTunnelMode.EXCLUDE, SplitTunnelMode.INCLUDE)) {
                val s = DesktopSettings(mode = mode, routing = RoutingSettings(appMode = split, apps = apps))
                val cfg = DesktopConfig.build(p, s, os, api, null, bypassPaths = listOf("/opt/hydra/bin/Hydra"))
                assertTrue(cfg.getJSONObject("route").getBoolean("find_process"))
                val rs = rules(cfg)
                assertEquals("sniff", rs[0].getString("action"))
                assertEquals("hijack-dns", rs[1].getString("action"))
                val appRule = rs.first { it.optString("type") == "logical" }
                assertEquals("direct", appRule.getString("outbound"))
                val parts = appRule.getJSONArray("rules")
                assertEquals(local, parts.getJSONObject(0).getJSONArray("inbound").getString(0), "раздачу в LAN правило не трогает")
                val match = parts.getJSONObject(1)
                assertEquals(split == SplitTunnelMode.INCLUDE, match.optBoolean("invert"))
                assertEquals("or", match.getString("mode"), "имена и пути — через «или»")
                assertTrue(rs.indexOf(appRule) < rs.indexOfFirst { it.has("ip_is_private") }, "приложения решают раньше адресов")
                dumpDir?.let { File(it, "desktop-apps-${split.name.lowercase()}-${os.name.lowercase()}-${mode.name.lowercase()}.json").writeText(cfg.toString(2)) }
            }
        }
        // Выключено или пустой список — правил и find_process нет вовсе.
        val off = DesktopConfig.build(p, DesktopSettings(routing = RoutingSettings(appMode = SplitTunnelMode.INCLUDE)), Os.LINUX, api, null)
        assertFalse(off.getJSONObject("route").has("find_process"))
    }

    @Test
    fun `site rules, mtu, ipv6 and lan share`() {
        val p = parse(xrayLinks.getValue("vless-reality"))
        val s = DesktopSettings(
            mode = ConnectionMode.TUN,
            routing = RoutingSettings(
                netMode = SplitTunnelMode.EXCLUDE, mtu = MtuPreset.MTU_1280, ipv6 = true,
                netRules = listOf(NetworkRule(NetRuleType.DOMAIN_SUFFIX, "ru"), NetworkRule(NetRuleType.IP_CIDR, "10.1.0.0/16")),
            ),
            lanShare = HotspotSettings(enabled = true, port = 3080, username = "u", password = "secret1"),
        )
        for (bridged in listOf(null, bridge)) {
            val cfg = DesktopConfig.build(p, s, Os.LINUX, api, null, bridged)
            val rs = rules(cfg)
            assertEquals("direct", rs.first { it.has("domain_suffix") }.getString("outbound"))
            assertEquals("direct", rs.first { it.has("ip_cidr") }.getString("outbound"))
            val inbounds = cfg.getJSONArray("inbounds")
            assertEquals(1280, inbounds.getJSONObject(0).getInt("mtu"))
            assertEquals("prefer_ipv4", cfg.getJSONObject("dns").getString("strategy"), "IPv6 включён — не ipv4_only")
            val lan = inbounds.getJSONObject(1)
            assertEquals("0.0.0.0", lan.getString("listen"))
            assertEquals("secret1", lan.getJSONArray("users").getJSONObject(0).getString("password"))
            dumpDir?.let { File(it, "desktop-sites-lan-${if (bridged == null) "singbox" else "xray"}.json").writeText(cfg.toString(2)) }
        }
        // Без пароля раздача не поднимается.
        val open = DesktopConfig.build(p, s.copy(lanShare = HotspotSettings(enabled = true, port = 3080)), Os.LINUX, api, null)
        assertEquals(1, open.getJSONArray("inbounds").length())
    }

    @Test
    fun `rule validation`() {
        assertEquals("10.0.0.1/32", Rules.cidr("10.0.0.1"))
        assertEquals("10.0.0.0/8", Rules.cidr("10.0.0.0/8"))
        assertNull(Rules.cidr("10.0.0.300"))
        assertNull(Rules.cidr("10.0.0.0/33"))
        assertEquals("2001:db8:0:0:0:0:0:1/128", Rules.cidr("2001:db8::1"))
        assertNull(Rules.cidr("example.com"))
        assertEquals(NetworkRule(NetRuleType.DOMAIN_SUFFIX, "youtube.com"), Rules.guess("https://YouTube.com/watch?v=1"))
        assertEquals(NetworkRule(NetRuleType.DOMAIN_SUFFIX, "site.ru"), Rules.netRule(NetRuleType.DOMAIN_SUFFIX, "*.site.ru"))
        assertEquals(NetworkRule(NetRuleType.IP_CIDR, "1.1.1.1/32"), Rules.guess("1.1.1.1"))
        assertNull(Rules.netRule(NetRuleType.DOMAIN, "bad domain"))
        assertNull(Rules.netRule(NetRuleType.DOMAIN_KEYWORD, "a\"b"))
        assertEquals(listOf("ru", "by"), Rules.countries(listOf("RU", "by", "../etc", "ru", "xyz")))
        assertEquals(listOf("chrome.exe"), Rules.apps(listOf(" chrome.exe ", "CHROME.EXE", "", "bad\u0000name")))
    }

    @Test
    fun `store migrates v1 settings and rejects garbage`() {
        val v1 = JSONObject("""{"version":1,"servers":[
            {"id":1,"name":"a","protocolId":"trojan","address":"x.com","port":443},
            {"id":2,"name":"broken","protocolId":"trojan","address":"","port":0}],
            "settings":{"mode":"TUN","dns":"8.8.8.8","geoMode":"DIRECT","geoCountries":["ru","../../x"],"tlsFragment":"RECORD","proxyPort":80}}""")
        val st = Store.fromJson(v1)
        assertEquals(listOf(1L), st.servers.map { it.id }, "сервер без адреса/порта отброшен")
        assertEquals(ConnectionMode.TUN, st.settings.mode)
        assertEquals("8.8.8.8", st.settings.routing.dns)
        assertEquals(listOf("ru"), st.settings.routing.geoCountries)
        assertEquals(2080, st.settings.proxyPort, "привилегированный порт не принимается")
        val bad = Store.fromJson(JSONObject().put("settings", JSONObject().put("routing", JSONObject()
            .put("netRules", JSONArray().put(JSONObject().put("type", "IP_CIDR").put("value", "not-an-ip"))
                .put(JSONObject().put("type", "NOPE").put("value", "x"))))))
        assertTrue(bad.settings.routing.netRules.isEmpty())
    }
}
