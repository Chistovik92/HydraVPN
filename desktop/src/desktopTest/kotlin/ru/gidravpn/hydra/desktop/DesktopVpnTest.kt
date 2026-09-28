package ru.gidravpn.hydra.desktop

import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.SplitTunnel
import ru.gidravpn.hydra.data.model.SplitTunnelMode
import ru.gidravpn.hydra.data.model.DnsEndpoint
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder
import ru.gidravpn.hydra.data.subscription.LinkParser
import ru.gidravpn.hydra.data.subscription.WireGuardParser
import ru.gidravpn.hydra.desktop.vpn.DesktopVpnManager
import ru.gidravpn.hydra.desktop.vpn.DesktopVpnManagerFactoryImpl
import org.junit.Test
import org.junit.Assert.*

class DesktopVpnTest {

    @Test
    fun testSingBoxConfigGeneration() {
        val profile = ServerProfile(
            name = "Test Server",
            protocolId = Protocol.VLESS.id,
            address = "example.com",
            port = 443,
            uuidOrPassword = "test-uuid-1234",
            transport = "tcp",
            security = "tls",
            sni = "example.com",
        )

        val config = SingBoxConfigBuilder.build(profile)
        val json = config.toString()

        assertTrue("Config should contain log", json.contains("\"log\""))
        assertTrue("Config should contain inbounds", json.contains("\"inbounds\""))
        assertTrue("Config should contain outbounds", json.contains("\"outbounds\""))
        assertTrue("Config should contain route", json.contains("\"route\""))
        assertTrue("Config should contain dns", json.contains("\"dns\""))
        assertTrue("Config should contain tun", json.contains("\"tun\""))
        assertTrue("Config should contain vless", json.contains("\"vless\""))
    }

    @Test
    fun testSingBoxConfigWithSplitTunnel() {
        val profile = ServerProfile(
            name = "Test Server",
            protocolId = Protocol.VLESS.id,
            address = "example.com",
            port = 443,
            uuidOrPassword = "test-uuid-1234",
        )

        val splitTunnel = SplitTunnel(
            mode = SplitTunnelMode.EXCLUDE,
            packages = setOf("com.example.app1", "com.example.app2"),
        )

        val config = SingBoxConfigBuilder.build(profile, splitTunnel = splitTunnel)
        val json = config.toString()

        assertTrue("Config should contain rules", json.contains("\"rules\""))
    }

    @Test
    fun testSingBoxConfigWithDns() {
        val profile = ServerProfile(
            name = "Test Server",
            protocolId = Protocol.VLESS.id,
            address = "example.com",
            port = 443,
            uuidOrPassword = "test-uuid-1234",
        )

        val dns = DnsEndpoint.doh("1.1.1.1")
        val config = SingBoxConfigBuilder.build(profile, dns = dns)
        val json = config.toString()

        assertTrue("Config should contain DNS server", json.contains("1.1.1.1"))
        assertTrue("Config should contain https type", json.contains("\"https\""))
    }

    @Test
    fun testLinkParser() {
        val link = "vless://test-uuid@example.com:443?security=tls&sni=example.com#Test"
        val profile = LinkParser.parseLine(link)

        assertNotNull("Profile should be parsed", profile)
        assertEquals("Protocol should be VLESS", Protocol.VLESS.id, profile?.protocolId)
        assertEquals("Address should be example.com", "example.com", profile?.address)
        assertEquals("Port should be 443", 443, profile?.port)
    }

    @Test
    fun testWireGuardParser() {
        val conf = """
[Interface]
PrivateKey = aBcDeFgHiJkLmNoPqRsTuVwXyZ1234567890AbCdEfGh=
Address = 172.19.0.2/32
DNS = 1.1.1.1

[Peer]
PublicKey = xYzAbCdEfGhIjKlMnOpQrStUvWxYz1234567890AbCdEf=
Endpoint = example.com:51820
AllowedIPs = 0.0.0.0/0, ::/0
""".trimIndent()

        val profile = WireGuardParser.toProfile(conf, "WireGuard", false)

        assertNotNull("Profile should be parsed", profile)
        assertEquals("Protocol should be WireGuard", Protocol.WIREGUARD.id, profile?.protocolId)
    }

    @Test
    fun testDesktopVpnManagerFactory() {
        val factory = DesktopVpnManagerFactoryImpl()
        val manager = factory.create()

        assertNotNull("Manager should be created", manager)
        assertFalse("Manager should not be running initially", manager.isRunning())
    }

    @Test
    fun testProtocolFromScheme() {
        assertEquals(Protocol.VLESS, Protocol.fromScheme("vless"))
        assertEquals(Protocol.TROJAN, Protocol.fromScheme("trojan"))
        assertEquals(Protocol.SHADOWSOCKS, Protocol.fromScheme("ss"))
        assertEquals(Protocol.WIREGUARD, Protocol.fromScheme("wireguard"))
        assertEquals(Protocol.AMNEZIAWG, Protocol.fromScheme("awg"))
    }

    @Test
    fun testDnsEndpointParse() {
        val dns = DnsEndpoint.parse("https://dns.google/dns-query")
        assertNotNull("DNS should be parsed", dns)
        assertEquals("Type should be https", "https", dns?.type)
        assertEquals("Host should be dns.google", "dns.google", dns?.host)
    }
}