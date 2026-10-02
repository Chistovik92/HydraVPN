package ru.gidravpn.hydra.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.gidravpn.hydra.bot.BotClient
import ru.gidravpn.hydra.bot.BotException
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

/**
 * Сквозная проверка входа через бота «Радар» на локальной заглушке, которая отвечает по контракту
 * `docs/API_APPS.md` (ping, link, me, subscriptions, session): вход по коду, подписки, смена `url`,
 * выключение, отзыв токена, лимит 40 знаков у имени устройства.
 */
class BotLiveTest {
    private val bot = FakeRadarBot()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file = File(Files.createTempDirectory("hydra-bot-test").toFile(), "hydra.json")

    @After fun stop() { bot.server.stop(0) }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!cond()) { check(System.currentTimeMillis() < end) { "не дождались: $what" }; Thread.sleep(100) }
    }

    @Test fun clientContract() {
        val c = BotClient()
        c.ping(bot.base)
        assertEquals("tok-abc", c.link(bot.base, "12345678", "x".repeat(80)))
        assertEquals(40, bot.lastDevice.length)                      // контракт: подпись устройства до 40 знаков
        try { c.link(bot.base, "00000000", "d"); error("ожидался отказ") } catch (e: BotException) { assertEquals(401, e.code) }
        assertEquals(2, c.profile(bot.base, "tok-abc").panels)
        val items = c.subscriptions(bot.base, "tok-abc")
        assertEquals(2, items.size)
        assertTrue(items[0].importable); assertFalse(items[1].importable)
        assertEquals(1900000000L, items[0].expire)
        assertEquals(10737418240L, items[0].trafficLimit)
        // Чужой сервер вместо бота — понятная ошибка до отправки кода.
        val other = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        other.createContext("/") { ex -> ex.sendResponseHeaders(200, 2); ex.responseBody.use { it.write("ok".toByteArray()) } }
        other.start()
        try { c.link("http://127.0.0.1:${other.address.port}", "12345678", "d"); error("ожидался отказ") } catch (e: BotException) { assertEquals(-1, e.code) } finally { other.stop(0) }
    }

    @Test fun linkSyncUrlChangeDisableAndRevoke() {
        val c = AppController(scope, Store(file), background = false)
        c.linkBot(bot.base, "12345678")
        waitFor("подписка из бота") { c.ui.value.data.subscriptions.any { it.botPanel == "1" && it.lastUpdated > 0 } && !c.ui.value.botBusy }
        var d = c.ui.value.data
        assertEquals("vasya", d.bot?.username); assertEquals(2, d.bot?.panels)
        assertEquals(1, d.subscriptions.size)                          // одиночный ключ (Outline) не импортируется
        val sub = d.subscriptions.single()
        assertEquals("Главный", sub.name)
        assertEquals(1900000000L, sub.expireAt)                        // срок из ответа бота (панель заголовков не прислала)
        assertEquals(10737418240L, sub.totalBytes)
        assertTrue(d.servers.any { it.subscriptionId == sub.id && it.address == "node.example" })

        // Смена url у панели → обновляется ТА ЖЕ подписка.
        bot.subUrl = "${bot.base}/sub/2"
        c.syncBot(); waitFor("смена url") { c.ui.value.data.subscriptions.singleOrNull()?.url?.endsWith("/sub/2") == true && !c.ui.value.botBusy }
        d = c.ui.value.data
        assertEquals(sub.id, d.subscriptions.single().id)

        // Выключено в боте → автообновление остановлено, пометка, ничего не удалено.
        bot.enabled = false
        c.syncBot(); waitFor("пометка") { c.ui.value.data.subscriptions.single().lastError.isNotEmpty() && !c.ui.value.botBusy }
        assertEquals(0, c.ui.value.data.subscriptions.single().autoUpdateHours)
        assertTrue(c.ui.value.data.servers.isNotEmpty())

        // Токен отозван в боте → при следующей синхронизации токен стирается.
        bot.revoked = true
        c.syncBot(); waitFor("стирание токена") { c.ui.value.data.bot == null }
        assertNull(c.ui.value.data.bot)
        assertNotNull(c.ui.value.data.subscriptions.singleOrNull())
    }
}
