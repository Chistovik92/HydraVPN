package ru.gidravpn.hydra.desktop

import org.json.JSONObject
import ru.gidravpn.hydra.data.model.GeoRoutingMode
import ru.gidravpn.hydra.data.model.HotspotSettings
import ru.gidravpn.hydra.data.model.MtuPreset
import ru.gidravpn.hydra.data.model.NetRuleType
import ru.gidravpn.hydra.data.model.NetworkRule
import ru.gidravpn.hydra.data.model.SplitTunnelMode
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.subscription.LinkParser
import ru.gidravpn.hydra.desktop.core.DesktopConfig
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopConfigTest {

    private val api = DesktopConfig.Api(9090, "secret")
    private val dumpDir = System.getProperty("hydra.configDump")?.let { File(it).apply { mkdirs() } }
    private val geoDir = System.getProperty("hydra.geoDir")?.let(::File)

    private val links = mapOf(
        "vless-reality" to "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=reality&pbk=Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw&sid=6ba85179e30d4fc2&sni=www.microsoft.com&fp=chrome&flow=xtls-rprx-vision#Reality%20NL",
        "vless-ws-tls" to "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=ws&security=tls&path=%2Fws&host=cdn.example.com&sni=cdn.example.com#WS",
        "vless-grpc" to "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=grpc&security=tls&serviceName=grpc&sni=example.com#gRPC",
        "trojan" to "trojan://p%40ss+word@example.com:443?sni=example.com#Trojan",
        "ss" to "ss://" + Base64.getUrlEncoder().withoutPadding().encodeToString("2022-blake3-aes-128-gcm:AAAAAAAAAAAAAAAAAAAAAA==".toByteArray()) + "@example.com:8388#SS",
        "hysteria2" to "hysteria2://pa+ss@example.com:443?sni=example.com&obfs=salamander&obfs-password=x#HY2",
        "tuic" to "tuic://11111111-2222-3333-4444-555555555555:pass@example.com:443?sni=example.com&congestion_control=bbr#TUIC",
        "vmess" to "vmess://" + Base64.getEncoder().encodeToString(
            """{"v":"2","ps":"VMess","add":"example.com","port":"443","id":"11111111-2222-3333-4444-555555555555","aid":"0","net":"ws","path":"/v","host":"example.com","tls":"tls","sni":"example.com"}""".toByteArray()),
        "wireguard" to "[Interface]\nPrivateKey = eCtXsJZ27+4PbhDkHnB923tkUn2Gj59wZw5wFA75MnU=\nAddress = 10.8.0.2\n\n[Peer]\nPublicKey = Cr8hWlKvtDt7nrvf+f0brNQQzabAqrjfBvas9pmowjo=\nEndpoint = example.com:51820\nAllowedIPs = 0.0.0.0/0\n",
    )

    private fun parse(link: String): ServerProfile =
        assertNotNull(LinkParser.parseLine(link), "не разобралось: $link").copy(id = 1)

    @Test
    fun `link parameters reach the config (ws Host, insecure, no uTLS for QUIC)`() {
        val ws = DesktopConfig.build(parse(links.getValue("vless-ws-tls")), DesktopSettings(), Os.LINUX, api, null)
        val wsOut = ws.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("cdn.example.com", wsOut.getJSONObject("transport").getJSONObject("headers").getString("Host"))
        val hy = parse("hysteria2://pw@example.com:443?sni=example.com&insecure=1#HY")
        val tls = DesktopConfig.build(hy, DesktopSettings(), Os.LINUX, api, null).getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls")
        assertTrue(tls.getBoolean("insecure"))
        assertFalse(tls.has("utls"))
        val tuic = parse("tuic://11111111-2222-3333-4444-555555555555:pw@example.com:443?sni=example.com&allow_insecure=1#T")
        assertTrue(DesktopConfig.build(tuic, DesktopSettings(), Os.LINUX, api, null).getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls").getBoolean("insecure"))
    }

    @Test
    fun `configs for every protocol, mode and OS`() {
        for ((name, link) in links) {
            val p = parse(link)
            assertTrue(DesktopConfig.isSupported(p), "$name должен поддерживаться")
            for (os in Os.entries) for (mode in ConnectionMode.entries) {
                val settings = DesktopSettings(mode = mode, routing = RoutingSettings(geoMode = GeoRoutingMode.DIRECT, geoCountries = listOf("ru")))
                val cfg = DesktopConfig.build(p, settings, os, api, geoDir)
                val inbound = cfg.getJSONArray("inbounds").getJSONObject(0)
                if (mode == ConnectionMode.TUN) {
                    assertEquals("tun", inbound.getString("type"))
                    assertTrue(inbound.getBoolean("auto_route"))
                    assertEquals(os != Os.MACOS, inbound.has("interface_name"), "macOS: имя utun выбирает система")
                    assertEquals("ipv4_only", cfg.getJSONObject("dns").getString("strategy"))
                } else {
                    assertEquals("mixed", inbound.getString("type"))
                    assertEquals("127.0.0.1", inbound.getString("listen"))
                }
                assertEquals("127.0.0.1:9090", cfg.getJSONObject("experimental").getJSONObject("clash_api").getString("external_controller"))
                val hasProxy = cfg.getJSONArray("outbounds").let { a -> (0 until a.length()).any { a.getJSONObject(it).getString("tag") == "proxy" } } ||
                    cfg.optJSONArray("endpoints")?.let { a -> (0 until a.length()).any { a.getJSONObject(it).getString("tag") == "proxy" } } == true
                assertTrue(hasProxy, "$name: нет outbound/endpoint proxy")
                dumpDir?.let { File(it, "desktop-$name-${os.name.lowercase()}-${mode.name.lowercase()}.json").writeText(cfg.toString(2)) }
            }
        }
    }

    /**
     * Конфиги для scripts/desktop-e2e.sh: тот же Shadowsocks-профиль, но на локальный
     * сервер 127.0.0.1:18388; в TUN — трафик процесса-сервера мимо туннеля.
     */
    @Test
    fun `e2e configs`() {
        val dir = dumpDir ?: return
        val p = parse(links.getValue("ss")).copy(address = "127.0.0.1", port = 18388)
        for (os in Os.entries) for (mode in ConnectionMode.entries) {
            val cfg = DesktopConfig.build(p, DesktopSettings(mode = mode, proxyPort = 12080), os, DesktopConfig.Api(19090, "secret"), null)
            if (mode == ConnectionMode.TUN) {
                val route = cfg.getJSONObject("route").put("find_process", true)
                val rules = route.getJSONArray("rules")
                val all = org.json.JSONArray().put(JSONObject()
                    .put("process_name", org.json.JSONArray().put(if (os == Os.WINDOWS) "sing-box-server.exe" else "sing-box-server"))
                    .put("outbound", "direct"))
                for (i in 0 until rules.length()) all.put(rules.get(i))
                route.put("rules", all)
            }
            File(dir, "e2e-${os.name.lowercase()}-${mode.name.lowercase()}.json").writeText(cfg.toString(2))

            // Движок Xray: тот же сервер, Xray на 127.0.0.1:18090 с паролем, sing-box — мост.
            val bridge = DesktopConfig.XrayBridge(18090, ru.gidravpn.hydra.data.subscription.XrayConfigBuilder.SocksAuth("hydra", "e2e"))
            val xs = DesktopSettings(mode = mode, proxyPort = 12080, preferXray = true)
            val exe = if (os == Os.WINDOWS) ".exe" else ""
            val bridged = DesktopConfig.build(p, xs, os, DesktopConfig.Api(19090, "secret"), null, bridge)
            if (mode == ConnectionMode.TUN) {
                // В приложении это process_path бинарника Xray (bypassPaths); в e2e путь неизвестен — по имени.
                val route = bridged.getJSONObject("route").put("find_process", true)
                val rules = route.getJSONArray("rules")
                val all = org.json.JSONArray().put(JSONObject()
                    .put("process_name", org.json.JSONArray().put("sing-box-server$exe").put("xray$exe"))
                    .put("outbound", "direct"))
                for (i in 0 until rules.length()) all.put(rules.get(i))
                route.put("rules", all)
            }
            File(dir, "e2e-xray-${os.name.lowercase()}-${mode.name.lowercase()}.json").writeText(bridged.toString(2))
            File(dir.parentFile, "xray-configs").apply { mkdirs() }
                .resolve("e2e-xray-${os.name.lowercase()}-${mode.name.lowercase()}.json")
                .writeText(DesktopConfig.xray(p, xs, bridge).toString(2))

            // Движок OpenFlux (0.7.10): клиент OpenFlux на 127.0.0.1:18091 (SOCKS5 без пароля), транспорт direct до
            // локального exit-узла 127.0.0.1:18500; sing-box — мост. Аргументы клиента — из того же OpenFluxArgs, что в приложении.
            val of = ServerProfile(name = "e2e", protocolId = Protocol.OPENFLUX.id, address = "127.0.0.1:18500", port = 0,
                transport = "direct", uuidOrPassword = "e2e-key-0123456789abcdef")
            val ofBridged = DesktopConfig.build(of, DesktopSettings(mode = mode, proxyPort = 12080), os, DesktopConfig.Api(19090, "secret"), null,
                DesktopConfig.XrayBridge(18091, null))
            if (mode == ConnectionMode.TUN) {
                val route = ofBridged.getJSONObject("route").put("find_process", true)
                val rules = route.getJSONArray("rules")
                val all = org.json.JSONArray().put(JSONObject()
                    .put("process_name", org.json.JSONArray().put("sing-box-server$exe").put("openflux$exe"))
                    .put("outbound", "direct"))
                for (i in 0 until rules.length()) all.put(rules.get(i))
                route.put("rules", all)
            }
            File(dir, "e2e-openflux-${os.name.lowercase()}-${mode.name.lowercase()}.json").writeText(ofBridged.toString(2))
            File(dir.parentFile, "openflux-e2e-client.args").writeText(
                ru.gidravpn.hydra.data.subscription.OpenFluxArgs.build(of, 18091, "@KEYFILE@").joinToString("\n"))
        }
    }

    @Test
    fun `wireguard becomes an endpoint with prefixed address`() {
        val cfg = DesktopConfig.build(parse(links.getValue("wireguard")), DesktopSettings(), Os.WINDOWS, api, null)
        val out = cfg.getJSONArray("outbounds")
        assertFalse((0 until out.length()).any { out.getJSONObject(it).getString("type") == "wireguard" })
        val ep = cfg.getJSONArray("endpoints").getJSONObject(0)
        assertEquals("10.8.0.2/32", ep.getJSONArray("address").getString(0))
        assertEquals("example.com", ep.getJSONArray("peers").getJSONObject(0).getString("address"))
    }

    @Test
    fun `unsupported protocols are rejected`() {
        val awg = ServerProfile(name = "awg", protocolId = Protocol.AMNEZIAWG.id, address = "x", port = 1)
        assertFalse(DesktopConfig.isSupported(awg))
        assertFailsWith<DesktopConfig.UnsupportedProtocol> { DesktopConfig.build(awg, DesktopSettings(), Os.LINUX, api, null) }
    }

    @Test
    fun `link parsing keeps plus in passwords and handles ipv6`() {
        assertEquals("p@ss+word", parse(links.getValue("trojan")).uuidOrPassword)
        assertEquals("pa+ss", parse(links.getValue("hysteria2")).uuidOrPassword)
        val v6 = parse("vless://uuid@[2001:db8::1]:8443?security=tls#v6")
        assertEquals("2001:db8::1", v6.address)
        assertEquals(8443, v6.port)
        val at = parse("trojan://pw@example.com:443#name@with-at")
        assertEquals("example.com", at.address)
        assertEquals("pw", at.uuidOrPassword)
    }

    @Test
    fun `base64 subscription with line breaks`() {
        val plain = links.filterKeys { it != "wireguard" }.values.joinToString("\n")
        val b64 = Base64.getMimeEncoder().encodeToString(plain.toByteArray())   // переносы каждые 76 символов
        assertTrue('\n' in b64 || '\r' in b64)
        assertEquals(links.size - 1, LinkParser.parseSubscription(b64).size)
    }

    @Test
    fun `store round trip`() {
        val f = File.createTempFile("hydra", ".json").apply { deleteOnExit() }
        val store = Store(f)
        val state = HydraState(
            servers = listOf(parse(links.getValue("vless-reality")).copy(subscriptionId = 3, pingMs = 42)),
            settings = DesktopSettings(
                mode = ConnectionMode.TUN, selectedServerId = 1, preferXray = true, killSwitch = true,
                routing = RoutingSettings(
                    dns = "https://dns.example/dns-query", appMode = SplitTunnelMode.EXCLUDE, apps = listOf("chrome.exe", "C:\\Apps\\tg.exe"),
                    netMode = SplitTunnelMode.INCLUDE, netRules = listOf(NetworkRule(NetRuleType.DOMAIN_SUFFIX, "youtube.com")),
                    mtu = MtuPreset.MTU_1400, ipv6 = true,
                ),
                lanShare = HotspotSettings(enabled = true, port = 3000, password = "secret1"),
            ),
            profiles = listOf(RoutingProfile("Дом", RoutingSettings(dns = "8.8.8.8"))),
        )
        store.save(state)
        assertEquals(state, store.load())
        // битый файл не роняет приложение
        f.writeText("{oops")
        assertEquals(HydraState(), store.load())
        assertTrue(File(f.parentFile, f.name + ".broken").exists(), "битый файл откладывается рядом")
    }
}
