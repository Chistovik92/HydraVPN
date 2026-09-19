package ru.gidravpn.hydra.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.Subscription

class BackupCodecTest {

    private val sample = Backup(
        appVersion = "0.6.13",
        createdAt = 1_789_000_000_000,
        prefs = mapOf(
            "vpn_settings" to mapOf("kill_switch" to true, "last_server_id" to 7L),
            "routing_settings" to mapOf("dns_custom_address" to "https://dns.example/dns-query/tok", "geo_countries" to "by,ru"),
            "theme_settings" to mapOf("x_int" to 3, "x_float" to 1.5f, "x_set" to setOf("a", "b")),
        ),
        servers = listOf(
            ServerProfile(
                id = 7, name = "Мой VLESS", protocolId = "vless", address = "203.0.113.10", port = 443,
                uuidOrPassword = "uuid", security = "reality", extra = """{"reality_pbk":"k"}""", subscriptionId = 2,
            ),
            ServerProfile(id = 9, name = "SS", protocolId = "ss", address = "198.51.100.1", port = 8388),
        ),
        subscriptions = listOf(Subscription(id = 2, name = "Панель", url = "https://panel.example/sub/abc")),
    )

    @Test fun roundTripKeepsEverythingIncludingTypes() {
        val back = BackupCodec.decode(BackupCodec.encode(sample))
        assertEquals(sample, back)
        // Типы важны: DataStore различает Long и Int, Float и Double.
        assertTrue(back.prefs["vpn_settings"]!!["last_server_id"] is Long)
        assertTrue(back.prefs["theme_settings"]!!["x_int"] is Int)
        assertTrue(back.prefs["theme_settings"]!!["x_float"] is Float)
        assertNull(back.servers[1].subscriptionId)
    }

    @Test fun missingOptionalServerFieldsGetDefaults() {
        val json = """{"format":"hydra-backup","version":1,"servers":[
            {"id":1,"name":"n","protocolId":"vless","address":"a","port":1}]}"""
        val s = BackupCodec.decode(json).servers.single()
        assertEquals(ServerProfile(id = 1, name = "n", protocolId = "vless", address = "a", port = 1), s)
    }

    @Test fun rejectsForeignFiles() {
        assertMessage("не JSON") { BackupCodec.decode("hello") }
        assertMessage("не является резервной копией") { BackupCodec.decode("""{"servers":[]}""") }
        assertMessage("более новой версией") { BackupCodec.decode("""{"format":"hydra-backup","version":99}""") }
        assertMessage("повреждена") {
            BackupCodec.decode("""{"format":"hydra-backup","version":1,"servers":[{"name":"x"}]}""")
        }
    }

    private fun assertMessage(part: String, block: () -> Unit) {
        try {
            block(); fail("ожидалось BackupFormatException с '$part'")
        } catch (e: BackupFormatException) {
            assertTrue("'${e.message}' должно содержать '$part'", e.message!!.contains(part))
        }
    }
}
