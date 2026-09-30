package ru.gidravpn.hydra.data.botaccount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.gidravpn.hydra.data.model.Subscription

class BotSyncPlannerTest {

    private fun item(panel: String, url: String = "https://p/$panel", enabled: Boolean = true, state: String = "ok") =
        BotSubscription(panel, "T$panel", "subscription", state, enabled, 0, 0, 0, url)

    private fun sub(id: Long, url: String, panel: String = "", lastError: String = "") =
        Subscription(id = id, name = "s$id", url = url, botPanel = panel, lastError = lastError)

    @Test fun newPanelIsAdded() {
        val steps = BotSyncPlanner.plan(emptyList(), listOf(item("1")))
        assertTrue(steps.single() is BotSyncPlanner.Step.Add)
    }

    @Test fun urlChangeUpdatesSameSubscription() {
        val steps = BotSyncPlanner.plan(listOf(sub(5, "https://old", "1")), listOf(item("1", "https://new")))
        val u = steps.single() as BotSyncPlanner.Step.Update
        assertEquals(5L, u.sub.id)
        assertEquals("https://new", u.item.url)
    }

    @Test fun legacySubscriptionAdoptedByUrl() {
        val steps = BotSyncPlanner.plan(listOf(sub(5, "https://p/1 ")), listOf(item("1")))
        assertEquals(5L, (steps.single() as BotSyncPlanner.Step.Update).sub.id)
    }

    @Test fun disabledInBotIsMarkedOnce() {
        val off = item("1", enabled = false)
        assertTrue(BotSyncPlanner.plan(listOf(sub(5, "u", "1")), listOf(off)).single() is BotSyncPlanner.Step.Disable)
        assertTrue(BotSyncPlanner.plan(listOf(sub(5, "u", "1", BotSyncPlanner.DISABLED_MARK)), listOf(off)).isEmpty())
    }

    @Test fun transientPanelErrorLeavesSubscriptionAlone() {
        assertTrue(BotSyncPlanner.plan(listOf(sub(5, "u", "1")), listOf(item("1", state = "panel_error"))).isEmpty())
    }

    @Test fun twoPanelsWithSameUrlGetTwoSubscriptions() {
        val steps = BotSyncPlanner.plan(listOf(sub(5, "https://x")), listOf(item("1", "https://x"), item("2", "https://x")))
        assertEquals(1, steps.count { it is BotSyncPlanner.Step.Update })
        assertEquals(1, steps.count { it is BotSyncPlanner.Step.Add })
    }
}
