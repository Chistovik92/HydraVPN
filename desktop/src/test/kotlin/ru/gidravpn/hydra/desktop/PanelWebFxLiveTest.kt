package ru.gidravpn.hydra.desktop

import org.junit.Assume.assumeTrue
import ru.gidravpn.hydra.desktop.ui.PanelWebFx
import ru.gidravpn.hydra.servers.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.8.2-a2: вход в панель внутри приложения — подстановкой логина и пароля в форму панели ([PanelWeb.script]) движком окна ПК
 * (JavaFX WebEngine, без окна на экране). Панель — стоящая на стенде 3x-ui (`XuiLiveTest` с HYDRA_XUI_KEEP=1), логин и пароль —
 * из `install-result.env`. HYDRA_SSH_TEST, HYDRA_FX_LIVE=1. Проверяется и путь «форма», и запасной «запрос входа».
 */
class PanelWebFxLiveTest {
    private fun runLogin(url: String, login: PanelWeb.Login, formOnly: Boolean): Pair<String, String> {
        val done = CountDownLatch(1)
        var result = ""; var location = ""
        javafx.application.Platform.runLater {
            val eng = javafx.scene.web.WebEngine()
            var tried = false
            eng.loadWorker.stateProperty().addListener { _, _, st ->
                println("FX state $st ${eng.location}")
                if (st == javafx.concurrent.Worker.State.FAILED) { result = "FAILED ${eng.loadWorker.exception}"; done.countDown() }
                if (st == javafx.concurrent.Worker.State.SUCCEEDED && !tried) {
                    tried = true
                    // formOnly: запасной путь выключен — проверяется именно подстановка в форму
                    val js = PanelWeb.script(if (formOnly) login.copy(url = "http://127.0.0.1:1/none") else login)
                    eng.executeScript(js)
                    val start = eng.location
                    val t = java.util.Timer(true)
                    var n = 0
                    t.scheduleAtFixedRate(object : java.util.TimerTask() {
                        override fun run() = javafx.application.Platform.runLater {
                            val r0 = runCatching { eng.executeScript("window.${PanelWeb.RESULT_VAR}||''") as? String }.getOrNull().orEmpty()
                            val pw = runCatching { (eng.executeScript(PanelWeb.PASSWORD_FIELDS_JS) as Number).toInt() }.getOrDefault(1)
                            val r = if (PanelWeb.loggedIn(r0, start, eng.location, pw)) "ok" else r0
                            if (r.isNotEmpty() || ++n > 60) {
                                t.cancel()
                                // после входа панель уходит со страницы входа; даём ей дорисоваться
                                java.util.Timer(true).schedule(object : java.util.TimerTask() {
                                    override fun run() = javafx.application.Platform.runLater {
                                        result = r; location = eng.location
                                        location += " | password-field=" + eng.executeScript("document.querySelectorAll('input[type=password]').length")
                                        done.countDown()
                                    }
                                }, 2500)
                            }
                        }
                    }, 500, 500)
                }
            }
            eng.load(url)
        }
        done.await(60, TimeUnit.SECONDS)
        return result to location
    }

    @Test fun formLoginInAppEngine() {
        val v = System.getenv("HYDRA_SSH_TEST")
        assumeTrue(v != null && System.getenv("HYDRA_FX_LIVE") != null)
        val p = v!!.split(":", limit = 4)
        val login = SshTarget(p[0], p[1].toInt(), p[2], password = p[3])
        val res = SshConnector.connect(login, SshConnector.fingerprint(login)).use { XuiPanel.parseResult(it.exec("cat ${XuiPanel.RESULT}").out) }
        val base = "http://${login.host}:${res.getValue("XUI_PANEL_PORT")}/${res["XUI_WEB_BASE_PATH"].orEmpty().trim('/')}"
        val panel = PanelInstance(PanelKind.XUI3, base, res.getValue("XUI_USERNAME"), res.getValue("XUI_PASSWORD"), res.getValue("XUI_API_TOKEN"))
        assertTrue(PanelWebFx.start(), "JavaFX не запустился")
        for (formOnly in listOf(true, false)) {
            // новая сессия: cookie прошлого входа — прочь
            java.net.CookieHandler.setDefault(java.net.CookieManager())
            val (r, loc) = runLogin(base + "/", PanelWeb.login(panel)!!, formOnly)
            println("FX formOnly=$formOnly result=$r location=$loc")
            // первый проход — вход формой; второй — сессия WebKit уже есть (cookie движка живут до перезапуска), панель открыта сразу
            if (formOnly) assertEquals("ok", r, "вход формой: $loc")
            assertTrue(loc.contains("password-field=0"), "панель не открылась: $loc")
        }
    }

    /**
     * 0.8.2-a3: PasarGuard / Remnawave — панели только для самого сервера: сайт через SSH-туннель, вход формой. Панель — стоящая
     * на стенде (`PasarGuardLiveTest` с HYDRA_XUI_KEEP=1), запись — копия `/etc/hydra/panel.json`. HYDRA_SSH_TEST, HYDRA_FX_TUNNEL=1.
     */
    @Test fun formLoginThroughTunnel() {
        val v = System.getenv("HYDRA_SSH_TEST")
        assumeTrue(v != null && System.getenv("HYDRA_FX_TUNNEL") != null)
        val p = v!!.split(":", limit = 4)
        val login = SshTarget(p[0], p[1].toInt(), p[2], password = p[3])
        SshConnector.connect(login, SshConnector.fingerprint(login)).use { ssh ->
            val panel = PanelInstance.fromJson(org.json.JSONObject(ssh.exec("cat ${OtherPanels.PANEL_STATE}").out))
            assertTrue(panel.kind.tunnelOnly && panel.local)
            val port = ssh.forwardLocal(System.getenv("HYDRA_FX_RPORT")?.toIntOrNull() ?: PanelWeb.remotePort(panel.baseUrl))
            val url = PanelWeb.tunnelUrl(panel.baseUrl, port)
            // HYDRA_FX_HOLD=секунды: туннель остаётся открытым — проверить сайт обычным браузером
            System.getenv("HYDRA_FX_HOLD")?.toLongOrNull()?.let { println("FX tunnel $url"); System.out.flush(); java.io.File(System.getenv("HYDRA_FX_HOLD_FILE") ?: "fxhold.txt").let { f ->
                java.io.File(f.path + ".js").writeText(PanelWeb.script(PanelWeb.login(panel, url)!!)); f.writeText(url) }; Thread.sleep(it * 1000) }
            assertTrue(PanelWebFx.start(), "JavaFX не запустился")
            java.net.CookieHandler.setDefault(java.net.CookieManager())
            if (System.getenv("HYDRA_FX_PROBE") != null) {
                println("FX java " + runCatching { (java.net.URL(url).openConnection() as java.net.HttpURLConnection).let { c -> c.connectTimeout = 5000; c.readTimeout = 10000; "${c.responseCode} ${c.inputStream.readBytes().size} proxy=${java.net.ProxySelector.getDefault()?.select(java.net.URI(url))}" } }.getOrElse { it.toString() })
                val l = CountDownLatch(1)
                javafx.application.Platform.runLater {
                    com.sun.javafx.webkit.WebConsoleListener.setDefaultListener { _, m, ln, src -> println("FX console $src:$ln $m") }
                    val eng = javafx.scene.web.WebEngine()
                    eng.loadWorker.exceptionProperty().addListener { _, _, e -> println("FX exception $e") }
                    eng.locationProperty().addListener { _, _, x -> println("FX location $x") }
                    eng.loadWorker.stateProperty().addListener { _, _, st -> println("FX state $st") }
                    val hs = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
                    hs.createContext("/") { ex -> val b = "<html><head><title>hello</title></head><body>hi</body></html>".toByteArray(); ex.responseHeaders.add("Content-Type", "text/html;charset=utf-8"); (System.getenv("HYDRA_FX_HDR") ?: "").split("|").filter { ":" in it }.forEach { h -> ex.responseHeaders.add(h.substringBefore(":").trim(), h.substringAfter(":").trim()) }; ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
                    hs.start()
                    val probe = System.getenv("HYDRA_FX_PROBE").removePrefix("1")
                    eng.load(if (probe == "local") "http://127.0.0.1:${hs.address.port}/" else url + probe)
                    java.util.Timer(true).schedule(object : java.util.TimerTask() { override fun run() = javafx.application.Platform.runLater {
                        println("FX probe title=${eng.title} body=" + runCatching { eng.executeScript("document.body?document.body.innerHTML.length:-1") }.getOrNull() +
                            " ua=" + eng.userAgent)
                        l.countDown() } }, 20000)
                }
                l.await(40, TimeUnit.SECONDS)
            }
            val (r, loc) = runLogin(url, PanelWeb.login(panel, url)!!, formOnly = true)
            println("FX ${panel.kind} result=$r location=$loc")
            assertEquals("ok", r, "вход формой: $loc")
            assertTrue(loc.contains("password-field=0"), "панель не открылась: $loc")
        }
    }
}
