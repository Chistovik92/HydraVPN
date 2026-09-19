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
}
