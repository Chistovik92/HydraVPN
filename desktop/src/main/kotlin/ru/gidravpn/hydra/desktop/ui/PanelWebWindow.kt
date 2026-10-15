package ru.gidravpn.hydra.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import ru.gidravpn.hydra.servers.PanelInstance
import ru.gidravpn.hydra.servers.PanelWeb
import java.util.concurrent.ConcurrentHashMap

/**
 * Сайт панели внутри Hydra на ПК (0.8.2-a2, запрос владельца «нормальное открытие панелей в самом приложении, а не в браузере»).
 * Встроенного браузера в Compose Desktop нет — WebView из JavaFX (WebKit) в отдельном окне. Вход — [PanelWeb.script]:
 * логин и пароль вписываются в форму панели. JavaFX не запустился (нет нативной части, старая система) — [PanelWebFx.available]
 * = false, и панель открывается в браузере, как раньше.
 */
object PanelWebFx {
    @Volatile private var started = false
    /** Порты туннелей Hydra на 127.0.0.1: только для них разрешено несовпадение имени в сертификате (он выписан на IP сервера). */
    val tunnelPorts: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    val available: Boolean by lazy { runCatching { Class.forName("javafx.embed.swing.JFXPanel"); true }.getOrDefault(false) }

    @Synchronized fun start(): Boolean {
        if (started) return true
        return runCatching {
            javafx.application.Platform.setImplicitExit(false)
            runCatching { javafx.application.Platform.startup {} }   // уже запущен — исключение, это нормально
            installHostnameVerifier()
            if (java.net.CookieHandler.getDefault() == null) java.net.CookieHandler.setDefault(java.net.CookieManager())
            started = true
            true
        }.getOrDefault(false)
    }

    private fun installHostnameVerifier() {
        val base = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
        javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier { host, session ->
            (host == "127.0.0.1" && session.peerPort in tunnelPorts) || base.verify(host, session)
        }
    }
}

/**
 * Окно сайта панели. [url] — адрес панели или её туннеля; [onFailed] — вход формой и запросом не прошёл (пароль тогда кладётся
 * в буфер вызывающим). Окно закрыто — [onClose] (туннель закрывает вызывающий).
 */
@Composable
fun PanelWebWindow(panel: PanelInstance, title: String, url: String, onFailed: () -> Unit, onClose: () -> Unit) {
    val login = remember(url) { PanelWeb.login(panel, url) }
    var status by remember { mutableStateOf(if (login != null) "Вхожу в панель…" else "") }
    val origin = remember(url) { java.net.URL(url).let { "${it.protocol}://${it.authority}" } }
    var engine by remember { mutableStateOf<javafx.scene.web.WebEngine?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            val e = engine
            javafx.application.Platform.runLater { runCatching { e?.load("about:blank") } }
        }
    }
    Window(onCloseRequest = onClose, title = "Панель ${panel.kind.title} — $title", state = rememberWindowState(size = DpSize(1180.dp, 800.dp))) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(status.ifBlank { url }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { val e = engine; javafx.application.Platform.runLater { e?.reload() } }) { Text("Обновить") }
                    TextButton(onClick = onClose) { Text("Закрыть") }
                }
                SwingPanel(modifier = Modifier.fillMaxSize(), factory = {
                    javafx.embed.swing.JFXPanel().also { fx ->
                        javafx.application.Platform.runLater {
                            val wv = javafx.scene.web.WebView()
                            val eng = wv.engine
                            engine = eng
                            var tried = false
                            eng.loadWorker.stateProperty().addListener { _, _, st ->
                                if (st == javafx.concurrent.Worker.State.SUCCEEDED && !tried && login != null && eng.location.startsWith(origin)) {
                                    tried = true
                                    runCatching { eng.executeScript(PanelWeb.script(login)) }
                                    // итог входа — window.__hydraLogin (консоли у WebView нет)
                                    val start = eng.location
                                    val t = java.util.Timer(true)
                                    var n = 0
                                    t.scheduleAtFixedRate(object : java.util.TimerTask() {
                                        override fun run() {
                                            javafx.application.Platform.runLater {
                                                val r = runCatching { eng.executeScript("window.${PanelWeb.RESULT_VAR}||''") as? String }.getOrNull().orEmpty()
                                                val pw = runCatching { (eng.executeScript(PanelWeb.PASSWORD_FIELDS_JS) as Number).toInt() }.getOrDefault(1)
                                                when {
                                                    PanelWeb.loggedIn(r, start, eng.location, pw) -> { status = ""; t.cancel() }
                                                    r == "fail" -> { status = "Войти автоматически не получилось — пароль скопирован на 30 секунд, вставьте его в поле"; onFailed(); t.cancel() }
                                                    ++n > 60 -> t.cancel()
                                                }
                                            }
                                        }
                                    }, 500, 500)
                                }
                            }
                            fx.scene = javafx.scene.Scene(wv)
                            eng.load(url)
                        }
                    }
                })
            }
        }
    }
}
