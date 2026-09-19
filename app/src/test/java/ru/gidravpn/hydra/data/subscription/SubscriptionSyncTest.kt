package ru.gidravpn.hydra.data.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.Subscription
import ru.gidravpn.hydra.data.repository.HydraDevice
import ru.gidravpn.hydra.data.work.SubscriptionUpdateWorker

class SubscriptionSyncTest {

    private fun s(id: Long, host: String, name: String = host, uuid: String = "u", ping: Int = -1) =
        ServerProfile(id = id, name = name, protocolId = "vless", address = host, port = 443,
            uuidOrPassword = uuid, subscriptionId = 7, pingMs = ping)

    @Test fun removedDeletedNewAddedKeptKeepIdAndPing() {
        val existing = listOf(s(1, "a.example", ping = 40), s(2, "b.example"), s(3, "c.example"))
        val incoming = listOf(s(0, "a.example", name = "🇩🇪 A renamed"), s(0, "c.example"), s(0, "d.example"))
        val plan = SubscriptionSync.plan(7, existing, incoming)

        assertEquals(listOf(2L), plan.toDelete.map { it.id })                     // b пропал из подписки
        assertEquals(listOf("d.example"), plan.toInsert.map { it.address })       // d новый
        assertEquals(0L, plan.toInsert.single().id)
        val a = plan.toUpdate.single()                                             // c без изменений — не трогаем
        assertEquals(1L, a.id); assertEquals(40, a.pingMs); assertEquals("🇩🇪 A renamed", a.name)
    }

    @Test fun changedCredentialsIsANewServer() {
        val plan = SubscriptionSync.plan(7, listOf(s(1, "a.example", uuid = "old")), listOf(s(0, "a.example", uuid = "new")))
        assertEquals(1, plan.toDelete.size); assertEquals(1, plan.toInsert.size)
    }

    @Test fun duplicatesMatchedInOrder() {
        val plan = SubscriptionSync.plan(7, listOf(s(1, "a"), s(2, "a")), listOf(s(0, "a")))
        assertEquals(listOf(2L), plan.toDelete.map { it.id })
        assertTrue(plan.toInsert.isEmpty())
    }

    @Test fun headers() {
        val h = mapOf(
            "profile-title" to "base64:0JzQvtC5IFZQTg==",   // «Мой VPN»
            "subscription-userinfo" to "upload=100; download=2048; total=10737418240; expire=1790000000",
            "profile-update-interval" to "6",
            "support-url" to "https://t.me/support",
        )
        val i = SubscriptionHeaders.parse { h[it] }
        assertEquals("Мой VPN", i.title)
        assertEquals(100L, i.upload); assertEquals(2048L, i.download)
        assertEquals(10737418240L, i.total); assertEquals(1790000000L, i.expire)
        assertEquals(6, i.updateHours); assertEquals("https://t.me/support", i.supportUrl)
    }

    @Test fun plainTitleAndContentDispositionFallback() {
        assertEquals("Hydra Sub", SubscriptionHeaders.parse { if (it == "profile-title") "Hydra Sub" else null }.title)
        val cd = SubscriptionHeaders.parse { if (it == "content-disposition") "attachment; filename*=UTF-8''My%20Panel.txt" else null }
        assertEquals("My Panel", cd.title)
        assertEquals(null, SubscriptionHeaders.parse { if (it == "profile-update-interval") "abc" else null }.updateHours)
    }

    @Test fun hwidIsStableUuidAndUserAgentLooksLikeHapp() {
        val a = HydraDevice.hwidFrom("seed", "ru.gidravpn.hydra")
        assertEquals(a, HydraDevice.hwidFrom("seed", "ru.gidravpn.hydra"))
        assertTrue(a, Regex("^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$").matches(a))
        assertTrue(a != HydraDevice.hwidFrom("other", "ru.gidravpn.hydra"))
        assertEquals("Hydra/0.6.21/android Dalvik/2.1.0 (Linux; U; Android 15; 22081212UG)",
            HydraDevice.userAgentFor("0.6.21", "15", "22081212UG"))
    }

    @Test fun autoUpdateDue() {
        val sub = Subscription(name = "x", url = "u", autoUpdateHours = 12, lastUpdated = 0)
        val now = 100L * 3_600_000
        assertTrue(SubscriptionUpdateWorker.isDue(sub, now))
        assertTrue(!SubscriptionUpdateWorker.isDue(sub.copy(lastUpdated = now - 3_600_000), now))
        assertTrue(!SubscriptionUpdateWorker.isDue(sub.copy(autoUpdate = false), now))
    }
}
