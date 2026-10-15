package ru.gidravpn.hydra.desktop

import org.json.JSONObject
import ru.gidravpn.hydra.servers.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.8.1-rc2: HTTPS у s-ui и x-ui, закрытие порта панели, вход в панель за пользователя, понятные ошибки на uk/fa/zh. */
class Rc2PanelsTest {
    /** SQL в команде — в одинарных кавычках оболочки: `'` внутри записан как `'\''`. */
    private fun unq(c: String) = c.replace("'\\''", "'")

    private val raws = listOf("Auth fail", "timed out", "Connection refused", "unknown host", "ENETUNREACH", "checksum", "algorithm negotiation",
        "Отпечаток сервера изменился", "Не удалось скачать установщик", "Порт уже занят", "Панель недоступна по сети", "уже стоит 3x-ui",
        "Let's Encrypt не смог проверить сервер", "Туннель к панели не открылся: x", "что-то совсем другое")

    /** Все английские тексты, которые может показать [FriendlyText]. */
    private fun allEnglish(): Set<String> = buildSet {
        raws.forEach { val f = FriendlyText.explain(it, UiLang.EN); add(f.title); add(f.advice) }
        val pre = Preflight("amd64", "OSNAME", true, true, true, true, "none", emptyList())
        FriendlyText.checklist(pre, UiLang.EN).forEach { add(it.title.replace("OSNAME", "%s").replace("(amd64)", "(%s)")); add(it.help) }
        ServerKind.entries.forEach { k ->
            FriendlyText.planSentences(k, true, UiLang.EN, https = true).forEach { add(it.replace(k.panel?.title ?: "\u0000", "%s").replace("official 3x-ui", "official 3x-ui")) }
            add(FriendlyText.recommendation(k, UiLang.EN))
        }
    }

    @Test fun everyEnglishTextHasUkFaZh() {
        val keys = FriendlyI18n.keys
        // у 3x-ui название панели в тексте свой ключ (без %s)
        val missing = allEnglish().filter { it !in keys && it.replace("%s panel", "3x-ui panel") !in keys }
        assertTrue(missing.isEmpty(), "нет перевода: $missing")
    }

    @Test fun ukFaZhAreShownAndDifferFromEnglish() {
        val cyr = Regex("[а-яА-ЯёЁ]")
        for (l in listOf(UiLang.UK, UiLang.FA, UiLang.ZH)) {
            raws.forEach {
                val en = FriendlyText.explain(it, UiLang.EN); val x = FriendlyText.explain(it, l)
                assertTrue(x.title != en.title && x.advice != en.advice, "$l: $it")
                assertEquals(it, x.raw)
                if (l != UiLang.UK) assertFalse(cyr.containsMatchIn(x.title + x.advice), "$l: $it")
            }
            val pre = Preflight("arm64", "Debian 12", false, false, false, false, "none", listOf("x"))
            FriendlyText.checklist(pre, l).zip(FriendlyText.checklist(pre, UiLang.EN)).forEach { (a, b) -> assertTrue(a.title != b.title && a.help != b.help, "$l ${b.title}") }
            assertTrue(FriendlyText.checklist(pre, l).first().title.contains("Debian 12") && FriendlyText.checklist(pre, l).first().title.contains("arm64"))
            ServerKind.entries.forEach { k ->
                FriendlyText.planSentences(k, true, l, https = true).zip(FriendlyText.planSentences(k, true, UiLang.EN, https = true))
                    .forEach { (a, b) -> assertTrue(a != b, "$l $k $b") }
                assertTrue(FriendlyText.recommendation(k, l) != FriendlyText.recommendation(k, UiLang.EN))
            }
        }
        // ru/en по-прежнему через en = true/false
        assertEquals(FriendlyText.explain("Auth fail", true), FriendlyText.explain("Auth fail", UiLang.EN))
        assertEquals("Сервер не принял логин или пароль", FriendlyText.explain("Auth fail").title)
    }

    @Test fun langFromLocaleTag() {
        assertEquals(UiLang.RU, UiLang.of("ru-RU")); assertEquals(UiLang.UK, UiLang.of("uk")); assertEquals(UiLang.FA, UiLang.of("fa_IR"))
        assertEquals(UiLang.ZH, UiLang.of("zh-CN")); assertEquals(UiLang.EN, UiLang.of("de")); assertEquals(UiLang.EN, UiLang.of(null))
    }

    @Test fun certErrorsAreNotPortBusy() {
        // «Порт 80 занят» — про сертификат, а не про выбор другого порта
        assertEquals("Не удалось получить сертификат для панели", FriendlyText.explain("Проверить, что порт 80 свободен: Порт 80 занят другой программой").title)
        assertEquals("Не удалось получить сертификат для панели", FriendlyText.explain("Сертификат получить не удалось").title)
        assertEquals("Этот порт уже занят", FriendlyText.explain("Порт 443 занят").title)
    }

    @Test fun httpsStepsPerPanel() {
        for (kind in PanelKind.entries) {
            val steps = XuiPanel.httpsSteps(kind, "203.0.113.5", true, "ufw", 31000, "/abc/")
            val svc = OtherPanels.serviceOf(kind)
            assertTrue(steps.single { it.id == "cert-install" }.command!!.contains("--reloadcmd 'systemctl restart $svc'"), kind.name)
            assertTrue(steps.single { it.id == "cert-issue" }.command!!.contains("--certificate-profile shortlived"), kind.name)
            val apply = unq(steps.single { it.id == "cert-panel" }.command!!)
            assertTrue(apply.contains("systemctl restart $svc"), kind.name)
            when (kind) {
                PanelKind.XUI3 -> assertTrue("x-ui cert -webCert" in apply && "sqlite3" !in apply)
                PanelKind.XUI -> assertTrue("x-ui cert -webCert" in apply && "subCertFile" in apply && "/etc/x-ui/x-ui.db" in apply)
                PanelKind.SUI -> assertTrue("webCertFile" in apply && "webKeyFile" in apply && "/usr/local/s-ui/db/s-ui.db" in apply && "x-ui" !in apply)
            }
            assertTrue(steps.single { it.id == "cert-check" }.command!!.contains("https://127.0.0.1:31000/abc/"), kind.name)
        }
        // старый вызов 3x-ui — те же шаги, что раньше
        assertEquals(listOf("cert-fw80", "cert-port80", "cert-acme", "cert-issue", "cert-install", "cert-panel"), XuiPanel.httpsSteps("203.0.113.5", true, "ufw").map { it.id })
        // откат стирает пути к сертификату
        val revert = unq(MyServerService { error("") }.certRevertStep(PanelKind.SUI).command!!)
        assertTrue("UPDATE settings SET value='' WHERE key='webCertFile'" in revert && "systemctl restart s-ui" in revert)
    }

    @Test fun settingsCommandIsSafeAndUpserts() {
        val c = unq(OtherPanels.settingsCommand(PanelKind.SUI, mapOf("webListen" to "127.0.0.1")))
        assertTrue("UPDATE settings SET value='127.0.0.1' WHERE key='webListen'" in c)
        assertTrue("INSERT INTO settings(key, value) SELECT 'webListen', '127.0.0.1' WHERE NOT EXISTS" in c)
        assertTrue(c.contains("command -v sqlite3"))
        // значение с кавычкой или пробелом не пройдёт в SQL
        for (bad in listOf("a'b", "a b", "x;rm")) assertTrue(runCatching { OtherPanels.settingsCommand(PanelKind.XUI, mapOf("k" to bad)) }.isFailure, bad)
    }

    @Test fun listenStepsCloseAndOpen() {
        val p = PanelInstance(PanelKind.XUI, "https://203.0.113.5:31000/abc/", "u", "p", "", "v1.14.0")
        val close = OtherPanels.listenSteps(p, true, "ufw")
        assertTrue(unq(close.first().command!!).contains("value='127.0.0.1' WHERE key='webListen'"))
        assertTrue(close.single { it.id == "panel-local-ok" }.command!!.contains("https://127.0.0.1:31000/abc/"))
        assertTrue(close.last().command!!.contains("ufw delete allow 31000/tcp"))
        val check = close.single { it.id == "panel-listen-check" }
        assertTrue(check.accept(ExecResult(0, "127.0.0.1:31000\n")))
        assertFalse(check.accept(ExecResult(0, "*:31000\n")))
        val open = OtherPanels.listenSteps(p, false, "ufw")
        assertTrue(unq(open.first().command!!).contains("value='' WHERE key='webListen'"))
        assertTrue(open.last().command!!.contains("ufw allow 31000/tcp"))
        assertTrue(open.single { it.id == "panel-listen-check" }.accept(ExecResult(0, "*:31000\n")))
        assertFalse(open.single { it.id == "panel-listen-check" }.accept(ExecResult(0, "127.0.0.1:31000\n")))
        // файрвола нет — шага открытия нет
        assertTrue(OtherPanels.listenSteps(p, false, "none").none { it.id == "panel-fw" })
    }

    @Test fun localFlagSurvivesRoundTrip() {
        val p = PanelInstance(PanelKind.XUI3, "https://h:1/p", "u", "pw", "t", local = true)
        assertEquals(p, PanelInstance.fromJson(JSONObject(p.toJson().toString())))
        // запись прежних версий — без поля: панель открыта
        assertFalse(PanelInstance.fromJson(JSONObject("""{"kind":"SUI","baseUrl":"http://h:2/x/","token":""}""")).local)
    }

    @Test fun webLoginPerPanel() {
        val x3 = PanelInstance(PanelKind.XUI3, "https://203.0.113.5:2053/path", "admin", "p&s=w'\"rd", "t")
        val l = PanelWeb.login(x3)!!
        assertEquals("https://203.0.113.5:2053/path/login", l.url)
        assertEquals("username=admin&password=p%26s%3Dw%27%22rd", l.body)
        assertEquals("https://203.0.113.5:2053/path/", l.then)
        val sui = PanelWeb.login(PanelInstance(PanelKind.SUI, "http://h:31000/abc/", "u", "p", ""))!!
        assertEquals("http://h:31000/abc/api/login", sui.url); assertEquals("user=u&pass=p", sui.body)
        assertEquals("http://h:31000/abc/login", PanelWeb.login(PanelInstance(PanelKind.XUI, "http://h:31000/abc/", "u", "p", ""))!!.url)
        // через туннель — тот же путь на 127.0.0.1
        assertEquals("https://127.0.0.1:40123/path/login", PanelWeb.login(x3, PanelWeb.tunnelUrl(x3.baseUrl, 40123))!!.url)
        // подключённая по токену — пароля нет, только открыть
        assertNull(PanelWeb.login(x3.copy(username = "", password = "")))
        // в скрипте данные — строковыми литералами JSON (кавычки пароля экранированы)
        val js = PanelWeb.script(l)
        assertTrue(js.contains(JSONObject.quote(l.body)) && !js.contains("p&s=w'\"rd") && js.contains(PanelWeb.OK) && js.contains(PanelWeb.FAIL))
        // CSRF 3x-ui v3: токен со страницы входа уходит заголовком
        assertTrue(js.contains("meta[name=\"csrf-token\"]") && js.contains("X-CSRF-Token"))
        assertEquals("m9gBu-du_x", PanelWeb.csrfToken("""<head><meta name="csrf-token" content="m9gBu-du_x"></head>"""))
        assertNull(PanelWeb.csrfToken("<html></html>"))
        assertEquals(2053, PanelWeb.remotePort(x3.baseUrl)); assertEquals(443, PanelWeb.remotePort("https://h/p"))
    }

    @Test fun otherPanelPlanShowsCertWhenAsked() {
        val pre = Preflight("amd64", "Debian 12", true, true, true, true, "ufw", emptyList())
        val svc = MyServerService { error("") }
        assertTrue(svc.planPreviewPanel(PanelKind.SUI, "v1.6.4", true, pre, sslIp = true, host = "203.0.113.5").any { it.id == "cert-issue" })
        assertTrue(svc.planPreviewPanel(PanelKind.SUI, "v1.6.4", true, pre).none { it.id == "cert-issue" })
        // IPv6 — без сертификата
        assertTrue(svc.planPreviewPanel(PanelKind.XUI, "v1.14.0", true, pre, sslIp = true, host = "2001:db8::1").none { it.id == "cert-issue" })
    }
}
