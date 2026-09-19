package ru.gidravpn.hydra.data.subscription

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.gidravpn.hydra.data.model.ServerProfile
import java.util.Base64

class LinkBuilderTest {

    private fun p(id: String, extra: JSONObject = JSONObject(), block: ServerProfile.() -> ServerProfile = { this }) =
        ServerProfile(
            name = "My Server", protocolId = id, address = "203.0.113.10", port = 443,
            uuidOrPassword = "b831381d-6324-4d53-ad4f-8cda48b30811", extra = extra.toString(),
        ).block()

    @Test fun vlessReality() {
        val link = LinkBuilder.toLink(
            p("vless", JSONObject().put("reality_pbk", "PBK").put("reality_sid", "ab12")) {
                copy(security = "reality", sni = "example.com", flow = "xtls-rprx-vision")
            }
        )!!
        assertTrue(link, link.startsWith("vless://b831381d-6324-4d53-ad4f-8cda48b30811@203.0.113.10:443?"))
        listOf("security=reality", "sni=example.com", "flow=xtls-rprx-vision", "pbk=PBK", "sid=ab12", "type=tcp", "encryption=none")
            .forEach { assertTrue("$it in $link", it in link) }
        assertTrue(link.endsWith("#My%20Server"))
    }

    @Test fun vlessGrpcUsesServiceName() {
        val link = LinkBuilder.toLink(p("vless") { copy(transport = "grpc", transportPath = "svc") })!!
        assertTrue(link, "serviceName=svc" in link && "path=" !in link)
    }

    @Test fun ipv6HostIsBracketed() {
        val link = LinkBuilder.toLink(p("trojan") { copy(address = "2001:db8::1") })!!
        assertTrue(link, "@[2001:db8::1]:443" in link)
    }

    @Test fun vmessIsBase64Json() {
        val link = LinkBuilder.toLink(p("vmess", JSONObject().put("aid", 2)) { copy(transport = "ws", security = "tls", sni = "h.example") })!!
        val json = JSONObject(String(Base64.getDecoder().decode(link.removePrefix("vmess://"))))
        assertEquals("My Server", json.getString("ps"))
        assertEquals("203.0.113.10", json.getString("add"))
        assertEquals("443", json.getString("port"))
        assertEquals("2", json.getString("aid"))
        assertEquals("ws", json.getString("net"))
        assertEquals("tls", json.getString("tls"))
    }

    @Test fun shadowsocksSip002() {
        val link = LinkBuilder.toLink(p("ss", JSONObject().put("method", "chacha20-ietf-poly1305")) { copy(uuidOrPassword = "pa55") })!!
        val userInfo = link.removePrefix("ss://").substringBefore("@")
        assertEquals("chacha20-ietf-poly1305:pa55", String(Base64.getUrlDecoder().decode(userInfo)))
        assertTrue(link.endsWith("@203.0.113.10:443#My%20Server"))
    }

    @Test fun hysteria2AndTuic() {
        val hy = LinkBuilder.toLink(p("hysteria2", JSONObject().put("obfs", "salamander").put("obfs_password", "op")) { copy(sni = "s.example") })!!
        assertTrue(hy, hy.startsWith("hysteria2://") && "obfs=salamander" in hy && "obfs-password=op" in hy && "sni=s.example" in hy)
        val tuic = LinkBuilder.toLink(p("tuic", JSONObject().put("password", "pw").put("congestion_control", "bbr")))!!
        assertTrue(tuic, tuic.startsWith("tuic://b831381d-6324-4d53-ad4f-8cda48b30811:pw@") && "congestion_control=bbr" in tuic)
    }

    @Test fun sstpUserPass() {
        val link = LinkBuilder.toLink(p("sstp", JSONObject().put("username", "bob").put("allow_insecure", true)) { copy(uuidOrPassword = "s3cret") })!!
        assertTrue(link, link.startsWith("sstp://bob:s3cret@203.0.113.10:443?") && "allow_insecure=1" in link)
    }

    @Test fun wireguardIsBase64Conf() {
        val extra = JSONObject().put("public_key", "PUB").put("local_address", "10.0.0.2/32")
        val link = LinkBuilder.toLink(p("wireguard", extra) { copy(port = 51820) })!!
        assertTrue(link.startsWith("wireguard://"))
        val conf = String(Base64.getUrlDecoder().decode(link.removePrefix("wireguard://").substringBefore("#")))
        assertTrue(conf, "[Interface]" in conf && "PublicKey = PUB" in conf && "Endpoint = 203.0.113.10:51820" in conf)
    }

    @Test fun unshareableProtocolsReturnNull() {
        assertNull(LinkBuilder.toLink(p("pptp")))
        assertNull(LinkBuilder.toLink(p("wdtt")))
        assertNull(LinkBuilder.toLink(p("olcrtc")))
        assertNull(LinkBuilder.toLink(p("nonsense")))
        assertNotNull(LinkBuilder.toLink(p("vless")))
    }
}
