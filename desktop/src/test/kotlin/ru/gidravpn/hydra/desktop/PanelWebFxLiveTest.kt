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
}
