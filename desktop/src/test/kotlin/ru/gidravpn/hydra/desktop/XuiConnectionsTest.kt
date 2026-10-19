package ru.gidravpn.hydra.desktop

import org.json.JSONObject
import ru.gidravpn.hydra.data.model.EngineToggles
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.subscription.LinkParser
import ru.gidravpn.hydra.servers.XuiConnection
import ru.gidravpn.hydra.servers.XuiPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 0.8.2-a2: все типы подключений 3x-ui и ссылки, которые на них выдаёт панель (образцы — со стенда 3x-ui v3.10.2). */
class XuiConnectionsTest {
    private val cert = XuiConnection.Companion.Cert("/root/cert/ip/fullchain.pem", "/root/cert/ip/privkey.pem", "203.0.113.5")

    @Test fun everyTypeBuildsAnInbound() {
        for (t in XuiConnection.entries) {
            val ib = XuiConnection.inbound(t, 31000, "t", priv = "PRIV", pub = "PUB", cert = cert)
            assertEquals(t.protocol, ib.getString("protocol"), t.name)
            assertEquals(31000, ib.getInt("port"))
            assertTrue(ib.getBoolean("enable"))
            // клиентов в инбаунде нет: пользователь подключается потом, ключи выдаёт панель
            if (t != XuiConnection.AMNEZIAWG) assertEquals(0, ib.getJSONObject("settings").optJSONArray("clients")?.length() ?: 0, t.name)
        }
        assertEquals("reality", XuiConnection.inbound(XuiConnection.TROJAN_REALITY, 1, "t", priv = "a", pub = "b").getJSONObject("streamSettings").getString("security"))
        assertEquals("tcp", XuiConnection.inbound(XuiConnection.VLESS_TCP_REALITY, 1, "t", priv = "a", pub = "b").getJSONObject("streamSettings").getString("network"))
        val hy = XuiConnection.inbound(XuiConnection.HYSTERIA2, 1, "t", cert = cert).getJSONObject("streamSettings")
        assertEquals("hysteria", hy.getString("network"))
        assertEquals("/root/cert/ip/fullchain.pem", hy.getJSONObject("tlsSettings").getJSONArray("certificates").getJSONObject(0).getString("certificateFile"))
        assertEquals("/root/cert/ip/privkey.pem", XuiConnection.inbound(XuiConnection.TUIC, 1, "t", cert = cert).getJSONObject("settings").getJSONObject("server").getString("private_key"))
        val ss = XuiConnection.inbound(XuiConnection.SHADOWSOCKS_2022, 1, "t").getJSONObject("settings")
        assertEquals(32, java.util.Base64.getDecoder().decode(ss.getString("password")).size)
        assertEquals("{}", XuiConnection.inbound(XuiConnection.AMNEZIAWG, 1, "t").getJSONObject("settings").toString())   // всё заполнит панель
        // без ключей REALITY или сертификата — понятный отказ
        assertFailsWith<IllegalArgumentException> { XuiConnection.inbound(XuiConnection.TROJAN_REALITY, 1, "t") }
        assertFailsWith<IllegalArgumentException> { XuiConnection.inbound(XuiConnection.HYSTERIA2, 1, "t") }
        // прежний инбаунд по умолчанию (rc1) не изменился
        assertEquals(XuiPanel.defaultInbound(1, "a", "b", rnd = java.security.SecureRandom()).getJSONObject("streamSettings").getString("network"), "grpc")
    }

    @Test fun panelLinksParseIntoWorkingProfiles() {
        // Shadowsocks 2022: метод и ключи открытым текстом, «+» в base64, параметры после порта
        val ss = LinkParser.parseLine("ss://2022-blake3-aes-256-gcm:tEjg4YUw4t5fXY02z7EXRXZ3kHUM+civ8TavYMRJZoQ%3D:%2FjGeWiGVat4AFbnP5bojp9Dh0xM6kyfsPNHQvGVQfE4%3D@203.0.113.5:25005?type=tcp#t-ss")!!
        assertEquals(Protocol.SHADOWSOCKS.id, ss.protocolId)
        assertEquals(25005, ss.port)
        assertEquals("tEjg4YUw4t5fXY02z7EXRXZ3kHUM+civ8TavYMRJZoQ=:/jGeWiGVat4AFbnP5bojp9Dh0xM6kyfsPNHQvGVQfE4=", ss.uuidOrPassword)
        assertEquals("2022-blake3-aes-256-gcm", JSONObject(ss.extra).getString("method"))
        // старая форма (base64) — как раньше
        val old = LinkParser.parseLine("ss://YWVzLTI1Ni1nY206cGFzcw==@198.51.100.7:8388#old")!!
        assertEquals("pass", old.uuidOrPassword); assertEquals(8388, old.port)

        // WireGuard в URI-форме 3x-ui
        val wg = LinkParser.parseLine("wireguard://gKdK00FjCHsW8c%2BP0%2FmKU13lhnvNe2lb1p0M%2Fdt8HlE%3D@203.0.113.5:25006?address=10.0.0.2%2F32&mtu=1420&publickey=tdz4QaYZI%2FkSchPavuiCnabBSPCFHY6Iwkq4D2tnFhU%3D#t-wg")!!
        assertEquals(Protocol.WIREGUARD.id, wg.protocolId)
        assertEquals(25006, wg.port); assertEquals("203.0.113.5", wg.address)
        assertEquals("gKdK00FjCHsW8c+P0/mKU13lhnvNe2lb1p0M/dt8HlE=", JSONObject(wg.extra).getString("private_key"))
        assertEquals("tdz4QaYZI/kSchPavuiCnabBSPCFHY6Iwkq4D2tnFhU=", JSONObject(wg.extra).getString("public_key"))
        assertEquals("10.0.0.2/32", JSONObject(wg.extra).getString("local_address"))

        // AmneziaWG от 3x-ui: vpn:// + base64 .conf
        val conf = "[Interface]\nPrivateKey = gKdK00FjCHsW8c+P0/mKU13lhnvNe2lb1p0M/dt8HlE=\nAddress = 10.8.1.2/32\nJc = 5\nJmin = 75\nJmax = 290\nS1 = 97\nS2 = 23\nH1 = 7791785\nH2 = 625278498\nH3 = 1412360474\nH4 = 1671555529\n" +
            "[Peer]\nPublicKey = tdz4QaYZI/kSchPavuiCnabBSPCFHY6Iwkq4D2tnFhU=\nAllowedIPs = 0.0.0.0/0\nEndpoint = 203.0.113.5:25007\n"
        val awg = LinkParser.parseLine("vpn://" + java.util.Base64.getEncoder().encodeToString(conf.toByteArray()))!!
        assertEquals(Protocol.AMNEZIAWG.id, awg.protocolId); assertEquals(25007, awg.port)
        // vpn:// не от 3x-ui (сжатый JSON AmneziaVPN) — не подключение
        assertEquals(null, LinkParser.parseLine("vpn://AAAAeJyrVkrOz0lNUbJSSkxMLE1RKS0uScxNTFGyMjQwrq0FAN5tC0w"))

        // Trojan + REALITY: ключ REALITY не теряется, и подключение уходит в ядро Xray
        val tr = LinkParser.parseLine("trojan://11e62b84b8424836@203.0.113.5:25003?fp=chrome&pbk=PUBKEY&security=reality&sid=11f02dea&sni=vk.com&spx=%2F&type=tcp#t-tr")!!
        assertEquals("PUBKEY", JSONObject(tr.extra).getString("reality_pbk"))
        assertEquals("11f02dea", JSONObject(tr.extra).getString("reality_sid"))
        assertEquals(EngineToggles.Kind.XRAY, EngineToggles().engineFor(tr, true))
        // обычный Trojan по TLS — по-прежнему sing-box
        assertEquals(EngineToggles.Kind.SINGBOX, EngineToggles().engineFor(LinkParser.parseLine("trojan://pw@example.com:443?sni=example.com#t")!!, true))
    }
}
