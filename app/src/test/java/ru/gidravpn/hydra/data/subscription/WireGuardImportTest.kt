package ru.gidravpn.hydra.data.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Импорт WireGuard / AmneziaWG из текста `.conf` и ссылки `awg://` — сквозь парсер и построитель uapi.
 * Раньше парсер не сохранял открытый ключ пира в профиль, и `public_key=` в uapi оставался пустым
 * («hex string does not fit the slice» от amneziawg-go); тесты собирали профиль вручную и этого не видели.
 */
class WireGuardImportTest {

    private val priv = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val pub = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="

    private fun conf(extraIface: String = "", extraPeer: String = "") = """
        [Interface]
        PrivateKey = $priv
        Address = 10.8.0.2/32
        DNS = 1.1.1.1
        $extraIface

        [Peer]
        PublicKey = $pub
        Endpoint = 203.0.113.5:51820
        AllowedIPs = 0.0.0.0/0
        PersistentKeepalive = 25
        $extraPeer
    """.trimIndent()

    private fun uapiOf(text: String, awg: Boolean): List<String> {
        val p = WireGuardParser.toProfile(text, if (awg) "AmneziaWG" else "WireGuard", awg)
        assertNotNull("профиль должен разобраться", p)
        return WireGuardConfigBuilder.buildUapi(p!!).lines()
    }

    @Test fun peerPublicKeyReachesUapi() {
        val hex1 = "01".repeat(32)
        val lines = uapiOf(conf(), awg = false)
        assertTrue("public_key=$hex1 должен быть в uapi: $lines", "public_key=$hex1" in lines)
        assertTrue("private_key=${"00".repeat(32)}" in lines)
        assertTrue("endpoint=203.0.113.5:51820" in lines)
    }

    // Ссылка awg://<base64> тут не проверяется: android.util.Base64 в JVM-тестах заглушка; текст .conf идёт тем же путём разбора.
    @Test fun awgConfKeepsPublicKeyAndObfuscation() {
        val lines = uapiOf(conf("Jc = 4\nJmin = 40\nJmax = 70\nS1 = 15\nS2 = 23\nH1 = 11\nH2 = 22\nH3 = 33\nH4 = 44"), awg = true)
        assertTrue("public_key=${"01".repeat(32)}" in lines)
        assertEquals("jc=4", lines.first { it.startsWith("jc=") })
        // параметры уровня интерфейса — до первого public_key (иначе amneziawg-go отвергает их)
        val firstPeer = lines.indexOfFirst { it.startsWith("public_key=") }
        listOf("jc=", "jmin=", "jmax=", "s1=", "s2=", "h1=", "h4=").forEach { k ->
            assertTrue("$k до public_key", lines.indexOfFirst { it.startsWith(k) } in 0 until firstPeer)
        }
    }

    @Test fun presharedKeyIsKept() {
        val lines = uapiOf(conf(extraPeer = "PresharedKey = $pub"), awg = false)
        assertTrue("preshared_key=${"01".repeat(32)}" in lines)
    }
}
