package ru.gidravpn.hydra.desktop

import org.json.JSONObject
import ru.gidravpn.hydra.data.manual.ManualLink
import ru.gidravpn.hydra.data.subscription.LinkParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ручной ввод на ПК (0.8.0-rc4): ссылка из полей разбирается парсером ПК в профиль нужного протокола. */
class ManualLinkDesktopTest {
    private fun parse(id: String, v: Map<String, String>) = LinkParser.parseLine(ManualLink.build(id, "N", v)!!)!!

    @Test fun allProtocolsParseOnDesktop() {
        val cases = mapOf(
            "vless" to mapOf("address" to "203.0.113.5", "port" to "443", "uuid" to "u", "pbk" to "PBK"),
            "vmess" to mapOf("address" to "h", "port" to "443", "uuid" to "u"),
            "trojan" to mapOf("address" to "h", "port" to "443", "password" to "p@ss word"),
            "ss" to mapOf("address" to "h", "port" to "8388", "password" to "pw"),
            "hysteria2" to mapOf("address" to "h", "port" to "443", "password" to "pw"),
            "tuic" to mapOf("address" to "h", "port" to "443", "uuid" to "u", "password" to "pw"),
            "sstp" to mapOf("address" to "h", "port" to "443", "user" to "ivan", "password" to "pw"),
            "l2tp" to mapOf("address" to "h", "port" to "1701", "user" to "ivan", "password" to "pw"),
            "openflux" to mapOf("transport" to "direct", "url" to "203.0.113.5:24680", "key" to "k".repeat(32)),
            "olcrtc" to mapOf("room" to "room", "key" to "a".repeat(64)),
            "byedpi" to mapOf("strategy" to "-o1"),
        )
        for ((id, v) in cases) assertEquals(id, parse(id, v).protocolId, id)
        assertEquals("p@ss word", parse("trojan", cases["trojan"]!!).uuidOrPassword)
        assertEquals("PBK", JSONObject(parse("vless", cases["vless"]!!).extra).getString("reality_pbk"))
    }

    @Test fun amneziaConfIsNotMistakenForWireGuard() {
        val conf = "[Interface]\nPrivateKey = cHJpdmF0ZWtleXByaXZhdGVrZXlwcml2YXRla2V5MTI=\nAddress = 10.8.1.2/32\nJc = 4\nJmin = 10\nJmax = 50\nH1 = 5\n\n" +
            "[Peer]\nPublicKey = cHVibGlja2V5cHVibGlja2V5cHVibGlja2V5cHViMTI=\nEndpoint = 203.0.113.5:51820\nAllowedIPs = 0.0.0.0/0\n"
        assertEquals("awg", LinkParser.parseLine(conf)!!.protocolId)
        // и наоборот: обычный WireGuard остаётся WireGuard
        assertTrue(LinkParser.parseLine(conf.lines().filterNot { it.startsWith("J") || it.startsWith("H1") }.joinToString("\n"))!!.protocolId == "wireguard")
    }
}
