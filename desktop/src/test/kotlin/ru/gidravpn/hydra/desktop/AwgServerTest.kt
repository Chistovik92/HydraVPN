package ru.gidravpn.hydra.desktop

import ru.gidravpn.hydra.data.subscription.AwgParams
import ru.gidravpn.hydra.servers.AwgGeneration
import ru.gidravpn.hydra.servers.AwgServerParams
import ru.gidravpn.hydra.servers.AwgServerSpec
import ru.gidravpn.hydra.servers.X25519
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AwgServerTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun x25519MatchesRfc7748() {
        // RFC 7748 §5.2, первый вектор
        val k = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val u = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        assertEquals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", X25519.scalarMult(k, u).hex())
        // §6.1: Alice
        val a = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", X25519.publicKey(a).hex())
    }

    @Test fun generatedKeysAreConsistent() {
        val kp = X25519.generate()
        assertEquals(32, Base64.getDecoder().decode(kp.privateKey).size)
        assertEquals(kp.publicKey, X25519.publicFromPrivateB64(kp.privateKey))
    }

    @Test fun paramsRespectRulesForAllGenerations() {
        repeat(300) {
            for (g in AwgGeneration.values()) {
                val p = AwgServerParams.generate(g)
                assertEquals(emptyList(), AwgServerParams.validate(p), "$g $p")
                assertEquals(
                    when (g) { AwgGeneration.V1_0, AwgGeneration.V1_5 -> "1.0"; AwgGeneration.V2_0 -> "2.0"; AwgGeneration.V3 -> "3.x" },
                    AwgParams.version(p), "$g $p")
            }
        }
    }

    @Test fun booleansAreOnOff() {
        val p = AwgServerParams.generate(AwgGeneration.V3)
        assertEquals("on", p["randomtrailers"])
    }

    @Test fun configsContainParamsAndPeers() {
        val spec = AwgServerSpec.create("203.0.113.5", 51820, AwgGeneration.V2_0)
        val c = AwgServerSpec.newClient("phone", spec.nextAddress(emptyList())!!)
        assertEquals("10.8.1.2/32", c.address)
        assertEquals("10.8.1.3/32", spec.nextAddress(listOf(c.address)))
        val server = spec.serverConf(listOf(c))
        assertTrue("ListenPort = 51820" in server && "S4 = " in server && "PublicKey = ${c.publicKey}" in server)
        val client = spec.clientConf(c)
        assertTrue("Endpoint = 203.0.113.5:51820" in client && "PublicKey = ${spec.publicKey}" in client)
        // клиентский .conf разбирается тем же разбором, что и импорт
        spec.params.forEach { (k, _) -> assertTrue(client.contains(AwgParams.ALL.first { it.conf == k }.confName + " = ")) }
    }
}
