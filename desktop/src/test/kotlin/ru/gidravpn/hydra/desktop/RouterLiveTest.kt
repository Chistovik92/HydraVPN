package ru.gidravpn.hydra.desktop

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.gidravpn.hydra.router.RouterClient
import ru.gidravpn.hydra.router.RouterException
import ru.gidravpn.hydra.router.RouterLink

/**
 * Живая проверка клиента против настоящего hydravpn-router (https://github.com/Chistovik92/HydraVPNforRouters).
 * Без переменных окружения пропускается: `HYDRA_ROUTER_URL=http://127.0.0.1:18088 HYDRA_ROUTER_TOKEN=… gradle :desktop:test`.
 * Запускает роутер: `hydravpn-router start -c config.yaml` с `settings.api_listen` и `api_token`.
 */
class RouterLiveTest {
    private val client: RouterClient? = run {
        val url = System.getenv("HYDRA_ROUTER_URL") ?: return@run null
        val token = System.getenv("HYDRA_ROUTER_TOKEN") ?: return@run null
        RouterClient(RouterLink.parse(url, token) ?: return@run null)
    }

    @Test fun versionStatusAndErrorsMatchContract() {
        val c = client; assumeTrue(c != null); c!!
        assertTrue(c.version().first.matches(Regex("[0-9]+[.][0-9]+[.][0-9]+.*")))
        val st = c.status()
        assertTrue(st.has("dns"))
        // Неверный токен — 401 с текстом, а не падение разбора.
        val bad = RouterClient(RouterLink(c.link.host, c.link.port, "x".repeat(20), c.link.tls, c.link.fingerprint))
        try { bad.version(); error("ожидался отказ") } catch (e: RouterException) { assertEquals(401, e.code) }
    }

    @Test fun sectionsLifecycle() {
        val c = client; assumeTrue(c != null); c!!
        val name = "hydra_test_" + System.currentTimeMillis() % 100000
        c.addSection(JSONObject().put("name", name).put("label", "Test").put("enabled", true).put("action", "connection").put("provider", "singbox"))
        try {
            val s = c.sections().first { it.name == name }
            assertEquals("Test", s.label)
            assertTrue(s.editable)
            // PUT заменяет раздел целиком: поля, которых приложение не трогает, должны сохраниться.
            val edited = JSONObject(s.raw.toString()).put("label", "Edited").put("enabled", false)
            c.replaceSection(name, edited)
            val after = c.sections().first { it.name == name }
            assertEquals("Edited", after.label)
            assertFalse(after.enabled)
            // Повторное добавление с тем же именем — отказ роутера с текстом.
            try { c.addSection(JSONObject().put("name", name).put("action", "connection")); error("ожидался отказ") } catch (e: RouterException) { assertTrue(e.message!!.isNotBlank()) }
        } finally {
            c.deleteSection(name)
        }
        assertTrue(c.sections().none { it.name == name })
    }

    /** Роутер входит в аккаунт бота по коду и сам заводит подписки (docs/API.md, «Radar bot account»). */
    @Test fun routerLinksToRadarBot() {
        val c = client; assumeTrue(c != null); c!!
        val bot = FakeRadarBot()
        val name = "hydra_radar_" + System.currentTimeMillis() % 100000
        c.addSection(JSONObject().put("name", name).put("label", "Radar").put("enabled", true).put("action", "connection").put("provider", "singbox"))
        try {
            val r = c.radarLink(bot.base, "12345678", name)
            assertEquals(1, r.optJSONObject("result")?.optInt("added"))     // одиночный ключ (Outline) пропущен
            assertTrue(c.radar().optBoolean("linked"))
            assertEquals(1, c.subscriptions().count { it.section == name })
            val again = c.radarSync(name)                                   // повтор не плодит дубликатов
            assertEquals(0, again.optJSONObject("result")?.optInt("added"))
        } finally {
            runCatching { c.radarUnlink() }
            c.subscriptions().filter { it.section == name }.sortedByDescending { it.index }.forEach { runCatching { c.deleteSubscription(it.index) } }
            runCatching { c.deleteSection(name) }
            bot.server.stop(0)
        }
        assertFalse(c.radar().optBoolean("linked"))
    }

    @Test fun radarStatusAndLogs() {
        val c = client; assumeTrue(c != null); c!!
        assertFalse(c.radar().optBoolean("linked"))
        try { c.radarSync(null); error("ожидался отказ: не подключён") } catch (e: RouterException) { assertEquals(409, e.code) }
        assertTrue(c.logs(20).isNotEmpty())
        assertTrue(c.check("global").isNotBlank())
    }
}
