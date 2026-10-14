package ru.gidravpn.hydra.data.subscription

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.gidravpn.hydra.data.model.ServerProfile

class WireGuardConfigBuilderTest {

    // base64 из 32 нулевых байт → 64 нуля hex; настоящие ключи тут не нужны, важен порядок и имена
    private val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    private fun awg(extra: JSONObject = JSONObject()) = ServerProfile(
        name = "awg", protocolId = "awg", address = "203.0.113.5", port = 51820, uuidOrPassword = key,
        extra = extra.put("public_key", key).put("local_address", "10.8.0.2/32, fd00::2/128").toString(),
    )

    /** 0.8.0-rc4: replace_allowed_ips после allowed_ip стирал маршруты пира — «подключено», но трафик не шёл. */
    @Test fun replaceAllowedIpsComesBeforeAllowedIps() {
        val lines = WireGuardConfigBuilder.buildUapi(awg()).lines()
        val replace = lines.indexOf("replace_allowed_ips=true")
        val allowed = lines.indexOfFirst { it.startsWith("allowed_ip=") }
        assertTrue(lines.toString(), replace in 0 until allowed)
        assertEquals(1, lines.count { it == "replace_allowed_ips=true" })
        assertTrue(lines.count { it.startsWith("allowed_ip=") } >= 2)
    }

    @Test fun awgParamsAreInterfaceLevelBeforeFirstPeer() {
        val u = WireGuardConfigBuilder.buildUapi(awg(JSONObject().put("jc", "5").put("s1", "20").put("h1", "111").put("i1", "<r 16>")))
        val lines = u.lines()
        val firstPeer = lines.indexOfFirst { it.startsWith("public_key=") }
        listOf("jc=5", "s1=20", "h1=111", "i1=<r 16>").forEach {
            val i = lines.indexOf(it)
            assertTrue("$it отсутствует в:\n$u", i >= 0)
            assertTrue("$it должен идти до public_key", i < firstPeer)
        }
        assertFalse("префикс awg_ — несуществующий ключ uapi", u.contains("awg_"))
    }

    @Test fun plainWireguardHasNoObfuscationKeys() {
        val p = awg(JSONObject().put("jc", "5")).copy(protocolId = "wireguard")
        assertFalse(WireGuardConfigBuilder.buildUapi(p).contains("jc="))
    }

    @Test fun tunAddressesRoutesAndMtu() {
        val p = awg(JSONObject().put("allowed_ips", "0.0.0.0/0, ::/0").put("mtu", "1280"))
        assertEquals(listOf("10.8.0.2" to 32, "fd00::2" to 128), WireGuardConfigBuilder.localAddresses(p))
        assertEquals(listOf("0.0.0.0" to 0, "::" to 0), WireGuardConfigBuilder.allowedIps(p))
        assertEquals(1280, WireGuardConfigBuilder.mtu(p))
        assertEquals(1380, WireGuardConfigBuilder.mtu(awg()))
    }

    @Test fun awg3ParamsGoToUapiConverted() {
        val u = WireGuardConfigBuilder.buildUapi(awg(JSONObject()
            .put("jc", "4").put("s3", "10").put("h1", "100-200").put("i1", "<b 0xf6ab>")
            .put("headerprotectionkey", key).put("contentpaddingaddition", "16")
            .put("randomtrailers", "on").put("disablecookies", "off").put("maxhandshakeattempts", "5")))
        val lines = u.lines()
        val firstPeer = lines.indexOfFirst { it.startsWith("public_key=") }
        listOf("jc=4", "s3=10", "h1=100-200", "i1=<b 0xf6ab>", "header_protection_key=" + "0".repeat(64),
            "content_padding_addition=16", "random_trailers=1", "disable_cookies=0", "max_handshake_attempts=5",
        ).forEach {
            val i = lines.indexOf(it)
            assertTrue("$it нет в:\n$u", i >= 0)
            assertTrue("$it должен идти до public_key", i < firstPeer)
        }
    }

    @Test fun awgVersionDetection() {
        assertEquals("plain", AwgParams.version(emptyMap()))
        assertEquals("1.0", AwgParams.version(mapOf("jc" to "4", "h1" to "123")))
        assertEquals("1.5", AwgParams.version(mapOf("jc" to "4", "i1" to "<b 0x1>")))
        assertEquals("2.0", AwgParams.version(mapOf("jc" to "4", "s3" to "1")))
        assertEquals("2.0", AwgParams.version(mapOf("h1" to "100-200")))
        assertEquals("3.x", AwgParams.version(mapOf("jc" to "4", "randomtrailers" to "on")))
    }

    @Test fun awgConfKeepsAllGenerations() {
        val conf = WireGuardConfigBuilder.buildConf(awg(JSONObject().put("s4", "7").put("headerprotectionkey", key)))
        assertTrue(conf, "S4 = 7" in conf && "HeaderProtectionKey = $key" in conf)
    }
}
