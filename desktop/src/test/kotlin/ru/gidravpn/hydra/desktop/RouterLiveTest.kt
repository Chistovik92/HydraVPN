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

    /** Роутер 1.3.0+: правила, списки, серверы (скрытые ключи не затираются), настройки, ядра, SMS. */
    @Test fun fullManagement() {
        val c = client; assumeTrue(c != null); c!!
        val caps = c.capabilities()
        assumeTrue("роутер до 1.3.0", caps.api >= 3)
        listOf("rules", "lists", "settings", "servers.edit", "sections.unmask", "cores", "sms").forEach { assertTrue(it, caps.has(it)) }

        val tag = "hydra_t" + System.currentTimeMillis() % 100000
        c.addSection(JSONObject().put("name", tag).put("enabled", true).put("action", "connection")
            .put("selector_proxy_links", org.json.JSONArray().put("trojan://secret-pass@t.example.com:443#t")))
        try {
            // Раздел с личной ссылкой теперь правится: роутер вернёт ключ на место.
            val s = c.sections().first { it.name == tag }.copy(serverUnmasks = caps.has("sections.unmask"))
            assertTrue(s.editable)
            c.replaceSection(tag, JSONObject(s.raw.toString()).put("label", "L"))
            assertEquals("L", c.sections().first { it.name == tag }.label)

            c.addRule(JSONObject().put("section", tag).put("enabled", true).put("domain_suffix", org.json.JSONArray().put("example.org")))
            val rule = c.rules().last()
            assertEquals(tag, rule.section)
            c.replaceRule(rule.index, JSONObject(rule.raw.toString()).put("port", "443"))
            assertEquals("443", c.rules()[rule.index].raw.optString("port"))
            c.deleteRule(rule.index)

            c.addList(JSONObject().put("name", tag).put("type", "domain").put("entries", org.json.JSONArray().put("a.example")))
            assertTrue(c.lists().any { it.name == tag && it.entries == listOf("a.example") })
            c.deleteList(tag)

            c.addServer(JSONObject().put("name", tag).put("protocol", "vless").put("listen_port", 18443).put("server_uuid", "11111111-2222-3333-4444-555555555555"))
            val srv = c.servers().first { it.optString("name") == tag }
            assertEquals("********", srv.optString("server_uuid"))
            c.replaceServer(tag, JSONObject(srv.toString()).put("listen_port", 18444))
            assertEquals(18444, c.servers().first { it.optString("name") == tag }.optInt("listen_port"))
            c.deleteServer(tag)

            val before = c.settings().optBoolean("exclude_ntp")
            c.patchSettings(JSONObject().put("exclude_ntp", !before))
            assertEquals(!before, c.settings().optBoolean("exclude_ntp"))
            c.patchSettings(JSONObject().put("exclude_ntp", before))
            try { c.patchSettings(JSONObject().put("api_token", "x".repeat(20))); error("ожидался отказ") } catch (e: RouterException) { assertEquals(400, e.code) }

            assertTrue(c.cores().map { it.name }.containsAll(listOf("sing-box", "xray", "openflux", "olcrtc", "amneziawg")))
            c.sms() // модема нет — пустые списки, а не ошибка
        } finally {
            runCatching { c.rules().filter { it.section == tag }.sortedByDescending { it.index }.forEach { c.deleteRule(it.index) } }
            runCatching { c.deleteList(tag) }
            runCatching { c.deleteServer(tag) }
            runCatching { c.deleteSection(tag) }
        }
    }

    @Test fun radarStatusAndLogs() {
        val c = client; assumeTrue(c != null); c!!
        assertFalse(c.radar().optBoolean("linked"))
        try { c.radarSync(null); error("ожидался отказ: не подключён") } catch (e: RouterException) { assertEquals(409, e.code) }
        assertTrue(c.logs(20).isNotEmpty())
        assertTrue(c.check("global").isNotBlank())
    }
}
