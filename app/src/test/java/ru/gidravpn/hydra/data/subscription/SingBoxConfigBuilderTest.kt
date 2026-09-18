package ru.gidravpn.hydra.data.subscription

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.gidravpn.hydra.data.model.DnsEndpoint
import ru.gidravpn.hydra.data.model.GeoRoutingMode
import ru.gidravpn.hydra.data.model.NetRuleType
import ru.gidravpn.hydra.data.model.NetworkRule
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.SplitTunnel
import ru.gidravpn.hydra.data.model.SplitTunnelMode
import java.io.File

/**
 * Структура конфига sing-box. Каждый сгенерированный конфиг дополнительно
 * пишется в app/build/singbox-configs/ — его можно прогнать настоящим
 * `sing-box check -c <file>` той же версии, что в libbox.aar (1.12.9).
 */
class SingBoxConfigBuilderTest {

    private val profile = ServerProfile(
        name = "test", protocolId = "vless", address = "203.0.113.10", port = 443,
        uuidOrPassword = "b831381d-6324-4d53-ad4f-8cda48b30811", security = "tls", sni = "example.com",
    )

    private val geo = { mode: GeoRoutingMode ->
        SingBoxConfigBuilder.GeoRouting(
            mode,
            File("src/main/assets/geoip-ru.srs").absolutePath,
            File("src/main/assets/geosite-ru.srs").absolutePath,
        )
    }

    private fun dump(name: String, cfg: JSONObject): JSONObject {
        File("build/singbox-configs").apply { mkdirs() }.resolve("$name.json").writeText(cfg.toString(2))
        return cfg
    }

    private fun JSONObject.rules(): JSONArray = getJSONObject("route").getJSONArray("rules")
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    @Test fun sniffAndHijackDnsComeFirst() {
        val rules = dump("default", SingBoxConfigBuilder.build(profile)).rules()
        assertEquals("sniff", rules.getJSONObject(0).getString("action"))
        assertEquals("hijack-dns", rules.getJSONObject(1).getString("action"))
        assertEquals("dns", rules.getJSONObject(1).getString("protocol"))
    }

    @Test fun noLegacySpecialOutbounds() {
        val cfg = SingBoxConfigBuilder.build(profile)
        val types = cfg.getJSONArray("outbounds").objects().map { it.getString("type") }
        assertFalse("dns" in types)
        assertFalse("block" in types)
        assertFalse(cfg.rules().toString().contains("dns-out"))
    }

    @Test fun geoOffHasNoRuleSet() {
        val cfg = SingBoxConfigBuilder.build(profile, geoRouting = geo(GeoRoutingMode.OFF))
        assertFalse(cfg.getJSONObject("route").has("rule_set"))
        assertEquals("proxy", cfg.getJSONObject("route").getString("final"))
    }

    @Test fun ruDirect() {
        val cfg = dump("ru_direct", SingBoxConfigBuilder.build(profile, geoRouting = geo(GeoRoutingMode.RU_DIRECT)))
        val route = cfg.getJSONObject("route")
        assertEquals(2, route.getJSONArray("rule_set").length())
        val geoRule = cfg.rules().objects().single { it.has("rule_set") }
        assertEquals("direct", geoRule.getString("outbound"))
        assertEquals("proxy", route.getString("final"))
    }

    @Test fun ruViaProxy() {
        val cfg = dump("ru_via_proxy", SingBoxConfigBuilder.build(profile, geoRouting = geo(GeoRoutingMode.RU_VIA_PROXY)))
        val geoRule = cfg.rules().objects().single { it.has("rule_set") }
        assertEquals("proxy", geoRule.getString("outbound"))
        assertEquals("direct", cfg.getJSONObject("route").getString("final"))
    }

    @Test fun systemDnsHasOnlyLocalServer() {
        val dns = dump("system_dns", SingBoxConfigBuilder.build(profile, dns = null)).getJSONObject("dns")
        assertEquals(listOf("local"), dns.getJSONArray("servers").objects().map { it.getString("tag") })
        assertEquals("local", dns.getString("final"))
    }

    @Test fun customDnsAddressIsUsed() {
        val dns = SingBoxConfigBuilder.build(profile, dns = DnsEndpoint.doh("94.140.14.14")).getJSONObject("dns")
        val remote = dns.getJSONArray("servers").objects().single { it.getString("tag") == "remote" }
        assertEquals("94.140.14.14", remote.getString("server"))
        assertEquals("remote", dns.getString("final"))
    }

    @Test fun netIncludeWhitelistBeatsGeoMode() {
        val split = SplitTunnel(
            netMode = SplitTunnelMode.INCLUDE,
            netRules = listOf(NetworkRule(NetRuleType.DOMAIN_SUFFIX, "example.org")),
        )
        val cfg = dump("net_include_geo", SingBoxConfigBuilder.build(
            profile, splitTunnel = split, geoRouting = geo(GeoRoutingMode.RU_DIRECT),
        ))
        val rules = cfg.rules().objects()
        val domainIdx = rules.indexOfFirst { it.has("domain_suffix") }
        assertTrue("доменное правило должно идти после sniff", domainIdx > 0)
        assertEquals("proxy", rules[domainIdx].getString("outbound"))
        assertEquals("direct", cfg.getJSONObject("route").getString("final"))
    }

    /** Приватный DoH с токеном в пути — в 0.6.9 sing-box отвергал: «invalid server address». */
    @Test fun privateDohUrlWithPathAndBootstrap() {
        val endpoint = DnsEndpoint.parse("https://dns.example.net/dns-query/7652f1b8235e4ef9628859b3a8047dc5")!!
        val dns = dump("private_doh", SingBoxConfigBuilder.buildXrayBridge(10808, dns = endpoint)).getJSONObject("dns")
        val servers = dns.getJSONArray("servers").objects()
        val remote = servers.single { it.getString("tag") == "remote" }
        assertEquals("dns.example.net", remote.getString("server"))
        assertEquals("/dns-query/7652f1b8235e4ef9628859b3a8047dc5", remote.getString("path"))
        assertEquals("bootstrap", remote.getString("domain_resolver"))
        assertEquals("1.1.1.1", servers.single { it.getString("tag") == "bootstrap" }.getString("server"))
    }

    @Test fun dotEndpoint() {
        val endpoint = DnsEndpoint.parse("tls://dns.example.net:853")!!
        val remote = dump("dot", SingBoxConfigBuilder.build(profile, dns = endpoint)).getJSONObject("dns")
            .getJSONArray("servers").objects().single { it.getString("tag") == "remote" }
        assertEquals("tls", remote.getString("type"))
        assertEquals(853, remote.getInt("server_port"))
        assertFalse(remote.has("path"))
    }

    @Test fun xrayBridgeUsesLocalSocks() {
        val cfg = dump("xray_bridge", SingBoxConfigBuilder.buildXrayBridge(10808, geoRouting = geo(GeoRoutingMode.RU_DIRECT)))
        val proxy = cfg.getJSONArray("outbounds").objects().single { it.getString("tag") == "proxy" }
        assertEquals("socks", proxy.getString("type"))
        assertEquals(10808, proxy.getInt("server_port"))
        assertEquals("sniff", cfg.rules().getJSONObject(0).getString("action"))
    }
}
