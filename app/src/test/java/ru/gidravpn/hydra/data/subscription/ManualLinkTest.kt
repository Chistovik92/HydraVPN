package ru.gidravpn.hydra.data.subscription

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.gidravpn.hydra.data.manual.ManualLink

/** Ручной ввод (0.8.0-rc4): ссылка из полей формы разбирается тем же парсером, что и импорт, в правильный профиль. */
@RunWith(RobolectricTestRunner::class)
class ManualLinkTest {
    private fun parse(id: String, v: Map<String, String>, name: String = "Мой сервер") =
        LinkParser.parseLine(ManualLink.build(id, name, v) ?: error("не собралось: $id ${ManualLink.missing(id, ManualLink.defaults(id) + v)}"))!!

    @Test fun vlessRealityAndTransport() {
        val p = parse("vless", mapOf("address" to "203.0.113.5", "port" to "443", "uuid" to "b831381d-6324-4d53-ad4f-8cda48b30811",
            "sni" to "www.example.com", "pbk" to "PBKKEY", "sid" to "ab12"))
        assertEquals("vless", p.protocolId); assertEquals("reality", p.security); assertEquals("www.example.com", p.sni)
        assertEquals("xtls-rprx-vision", p.flow); assertEquals("Мой сервер", p.name)
        assertEquals("PBKKEY", JSONObject(p.extra).getString("reality_pbk"))
        val ws = parse("vless", mapOf("address" to "h.example", "port" to "8443", "uuid" to "u", "security" to "tls", "type" to "ws", "path" to "/ws", "host" to "cdn.example"))
        assertEquals("ws", ws.transport); assertEquals("/ws", ws.transportPath); assertEquals(8443, ws.port)
        // Reality без pbk не собирается
        assertNull(ManualLink.build("vless", "", mapOf("address" to "h", "port" to "443", "uuid" to "u")))
    }

    @Test fun everyProtocolRoundTrips() {
        assertEquals("vmess", parse("vmess", mapOf("address" to "h", "port" to "443", "uuid" to "u", "path" to "/p")).protocolId)
        val tr = parse("trojan", mapOf("address" to "h", "port" to "443", "password" to "p@ss word"))
        assertEquals("p@ss word", tr.uuidOrPassword)
        val ss = parse("ss", mapOf("address" to "h", "port" to "8388", "password" to "pw"))
        assertEquals("pw", ss.uuidOrPassword); assertEquals("aes-256-gcm", JSONObject(ss.extra).getString("method"))
        val hy = parse("hysteria2", mapOf("address" to "h", "port" to "443", "password" to "pw", "obfs-password" to "ob"))
        assertEquals("salamander", JSONObject(hy.extra).getString("obfs"))
        val tu = parse("tuic", mapOf("address" to "h", "port" to "443", "uuid" to "u", "password" to "pw"))
        assertEquals("pw", JSONObject(tu.extra).getString("password"))
        val sstp = parse("sstp", mapOf("address" to "vpn.example", "port" to "443", "user" to "ivan", "password" to "secret"))
        assertEquals("sstp", sstp.protocolId); assertEquals("ivan", JSONObject(sstp.extra).getString("username"))
        val of = parse("openflux", mapOf("transport" to "direct", "url" to "203.0.113.5:24680", "key" to "k".repeat(32)))
        assertEquals("openflux", of.protocolId)
        val olc = parse("olcrtc", mapOf("room" to "room-1", "key" to "a".repeat(64)))
        assertEquals("olcrtc", olc.protocolId); assertEquals("room-1", olc.address); assertEquals("Мой сервер", olc.name)
        val dpi = parse("byedpi", mapOf("strategy" to "-o1 -d1"), name = "")
        assertEquals("byedpi", dpi.protocolId); assertEquals("-o1 -d1", JSONObject(dpi.extra).getString("strategy"))
    }

    @Test fun amneziaFromFields() {
        val conf = ManualLink.build("awg", "", mapOf("privatekey" to "cHJpdmF0ZWtleXByaXZhdGVrZXlwcml2YXRla2V5MTI=", "ifaddress" to "10.8.1.2/32",
            "publickey" to "cHVibGlja2V5cHVibGlja2V5cHVibGlja2V5cHViMTI=", "endpoint" to "203.0.113.5:51820", "awgparams" to "Jc = 4\nJmin = 10\nJmax = 50\nH1 = 5"))
        assertNotNull(conf)
        val p = LinkParser.parseLine(conf!!)!!
        assertEquals("awg", p.protocolId); assertEquals("203.0.113.5", p.address); assertEquals(51820, p.port)
        assertTrue(ManualLink.missing("wireguard", mapOf()).isNotEmpty())
        assertTrue(ManualLink.missing("wireguard", mapOf("conf" to "[Interface]")).isEmpty())
    }
}
