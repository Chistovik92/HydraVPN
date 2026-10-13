package ru.gidravpn.hydra.data.subscription

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.gidravpn.hydra.data.model.Protocol

/** 0.8.0-rc3: ручная форма раньше сохраняла профиль без ключа/пароля — подключиться он не мог. */
class ManualServerTest {

    @Test fun vlessKeepsUuidAndSni() {
        val p = ManualServer.build(Protocol.VLESS, "", "example.com", 443, secret = "uuid-1", sni = "cdn.example.com")!!
        assertEquals("uuid-1", p.uuidOrPassword)
        assertEquals("tls", p.security)
        assertEquals("cdn.example.com", p.sni)
        assertTrue(p.name.contains("example.com"))
    }

    @Test fun shadowsocksGetsDefaultCipher() {
        val p = ManualServer.build(Protocol.SHADOWSOCKS, "ss", "1.2.3.4", 8388, secret = "pw")!!
        assertEquals("aes-256-gcm", JSONObject(p.extra).getString("method"))
        assertEquals("pw", p.uuidOrPassword)
    }

    @Test fun tuicNeedsBothSecrets() {
        val p = ManualServer.build(Protocol.TUIC, "t", "h", 443, secret = "u", secret2 = "pass")!!
        assertEquals("u", p.uuidOrPassword)
        assertEquals("pass", JSONObject(p.extra).getString("password"))
        assertEquals("h3", p.alpn)
    }

    @Test fun pppProtocolsKeepUsername() {
        val p = ManualServer.build(Protocol.SSTP, "s", "vpn.example.com", 443, secret = "pw", user = "bob")!!
        assertEquals("bob", JSONObject(p.extra).getString("username"))
        assertEquals("pw", p.uuidOrPassword)
    }

    @Test fun missingRequiredFieldsGiveNull() {
        assertNull(ManualServer.build(Protocol.VLESS, "x", "", 443, secret = "u"))
        assertNull(ManualServer.build(Protocol.TROJAN, "x", "h", 443, secret = ""))
    }

    @Test fun confAndLinkOnlyProtocolsHaveNoCredentialForm() {
        assertEquals(ManualServer.Kind.CONF, ManualServer.kind(Protocol.AMNEZIAWG))
        assertEquals(ManualServer.Kind.CONF, ManualServer.kind(Protocol.WIREGUARD))
        assertEquals(ManualServer.Kind.LINK_ONLY, ManualServer.kind(Protocol.OPENFLUX))
        assertNull(ManualServer.build(Protocol.AMNEZIAWG, "x", "h", 51820, secret = "k"))
        assertNotNull(ManualServer.build(Protocol.HYSTERIA2, "x", "h", 443, secret = "pw"))
    }
}
