package ru.gidravpn.hydra.desktop

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.gidravpn.hydra.bot.BotJson
import ru.gidravpn.hydra.bot.BotSubscription
import ru.gidravpn.hydra.bot.BotSyncPlanner
import ru.gidravpn.hydra.data.model.Subscription

class BotAccountTest {

    private fun item(panel: String, url: String = "https://p/$panel", enabled: Boolean = true, state: String = "ok") =
        BotSubscription(panel, "T$panel", "subscription", state, enabled, url)

    private fun sub(id: Long, url: String, panel: String = "", lastError: String = "") =
        Subscription(id = id, name = "s$id", url = url, botPanel = panel, lastError = lastError)

    @Test fun serverAddressNormalization() {
        assertEquals("https://radar.example.org", BotJson.normalizeServer("radar.example.org/"))
        assertEquals("http://192.168.1.5:8080", BotJson.normalizeServer("http://192.168.1.5:8080"))
        assertNull(BotJson.normalizeServer("http://radar.example.org"))   // открытый http — только в своей сети
        assertNull(BotJson.normalizeServer("https://user:pw@radar.example.org"))
        assertNull(BotJson.normalizeServer("ftp://x"))
    }

    @Test fun plannerFollowsPanelWhenUrlChanges() {
        val u = BotSyncPlanner.plan(listOf(sub(5, "https://old", "1")), listOf(item("1", "https://new"))).single()
            as BotSyncPlanner.Step.Update
        assertEquals(5L, u.sub.id)
        assertEquals("https://new", u.item.url)
    }

    @Test fun plannerAddsAdoptsDisablesOnce() {
        assertTrue(BotSyncPlanner.plan(emptyList(), listOf(item("1"))).single() is BotSyncPlanner.Step.Add)
        assertEquals(5L, (BotSyncPlanner.plan(listOf(sub(5, "https://p/1")), listOf(item("1"))).single() as BotSyncPlanner.Step.Update).sub.id)
        val off = item("1", enabled = false)
        assertTrue(BotSyncPlanner.plan(listOf(sub(5, "u", "1")), listOf(off)).single() is BotSyncPlanner.Step.Disable)
        assertTrue(BotSyncPlanner.plan(listOf(sub(5, "u", "1", BotSyncPlanner.DISABLED_MARK)), listOf(off)).isEmpty())
        assertTrue(BotSyncPlanner.plan(listOf(sub(5, "u", "1")), listOf(item("1", state = "panel_error"))).isEmpty())
    }

    @Test fun storeKeepsBotLinkAndPanel() {
        val state = HydraState(
            subscriptions = listOf(sub(1, "https://p/1", "7")),
            bot = BotLink("https://radar.example.org", "tok", "user", 2),
        )
        val back = Store.fromJson(JSONObject(Store.toJson(state).toString()))
        assertEquals(BotLink("https://radar.example.org", "tok", "user", 2), back.bot)
        assertEquals("7", back.subscriptions.single().botPanel)
        assertNull(Store.fromJson(JSONObject(Store.toJson(HydraState()).toString())).bot)
    }
}
