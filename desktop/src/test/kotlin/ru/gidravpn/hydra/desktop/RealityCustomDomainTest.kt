package ru.gidravpn.hydra.desktop

import org.json.JSONArray
import ru.gidravpn.hydra.servers.OtherPanelConnections
import ru.gidravpn.hydra.servers.XuiConnection
import ru.gidravpn.hydra.servers.XuiPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RealityCustomDomainTest {
    @Test fun normalizesOnlySafeDnsNames() {
        assertEquals("example.com", XuiPanel.normalizeRealityDomain(" Example.COM. "))
        assertEquals("xn--e1afmkfd.xn--p1ai", XuiPanel.normalizeRealityDomain("пример.рф"))
        listOf("", "localhost", "127.0.0.1", "1.2.3", "https://example.com", "example.com:443", "example.com/path",
            "bad domain.com", "-bad.example", "bad-.example", "bad_.example", "user@example.com", "[::1]", "a..com").forEach {
            assertNull(XuiPanel.normalizeRealityDomain(it), it)
        }
    }

    @Test fun customMaskBuildsTargetAndSniWithoutMutableEnumState() {
        val ib = XuiPanel.defaultInbound(443, "priv", "pub", XuiPanel.RealityMask.CUSTOM, customDomain = " Example.COM ")
        val reality = ib.getJSONObject("streamSettings").getJSONObject("realitySettings")
        assertEquals("example.com:443", reality.getString("target"))
        assertEquals(JSONArray(listOf("example.com")).toString(), reality.getJSONArray("serverNames").toString())
        assertEquals("", XuiPanel.RealityMask.CUSTOM.target)
        assertEquals("", XuiPanel.RealityMask.CUSTOM.sni)
        assertFailsWith<IllegalArgumentException> {
            XuiPanel.defaultInbound(443, "priv", "pub", XuiPanel.RealityMask.CUSTOM, customDomain = "https://example.com")
        }
    }

    @Test fun existingMasksAndPanelGeneratorsArePreserved() {
        val builtIn = XuiPanel.defaultInbound(443, "priv", "pub", XuiPanel.RealityMask.YANDEX, customDomain = "ignored.example")
            .getJSONObject("streamSettings").getJSONObject("realitySettings")
        assertEquals("ya.ru:443", builtIn.getString("target"))
        assertEquals("ya.ru", builtIn.getJSONArray("serverNames").getString(0))

        val inbound = XuiConnection.inbound(XuiConnection.VLESS_TCP_REALITY, 443, "t", XuiPanel.RealityMask.CUSTOM,
            priv = "priv", pub = "pub", customDomain = "example.com")
        assertEquals("example.com:443", inbound.getJSONObject("streamSettings").getJSONObject("realitySettings").getString("target"))

        val creds = OtherPanelConnections.newCreds(XuiConnection.TROJAN_REALITY, "sub")
        val xui = OtherPanelConnections.xuiInbound(XuiConnection.TROJAN_REALITY, 443, creds, XuiPanel.RealityMask.CUSTOM,
            "priv", "pub", customDomain = "example.com")
        assertEquals("example.com", xui.getJSONObject("streamSettings").getJSONObject("realitySettings").getJSONArray("serverNames").getString(0))

        val tls = OtherPanelConnections.suiRealityTls("t", XuiPanel.realitySni(XuiPanel.RealityMask.CUSTOM, "example.com"), "priv", "pub", "sid")
        assertEquals("example.com", tls.getJSONObject("server").getString("server_name"))
        assertEquals("example.com", tls.getJSONObject("server").getJSONObject("reality").getJSONObject("handshake").getString("server"))
    }
}
