package ru.gidravpn.hydra.ui.screens

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.gidravpn.hydra.R
import ru.gidravpn.hydra.servers.PanelInstance
import ru.gidravpn.hydra.servers.PanelWeb
import ru.gidravpn.hydra.ui.theme.AccentCyan
import ru.gidravpn.hydra.ui.theme.Bg
import ru.gidravpn.hydra.ui.theme.TextMuted
import ru.gidravpn.hydra.ui.theme.TextPrimary

/**
 * Сайт панели внутри приложения (0.8.1-rc2). Вход — за пользователя: на первой загруженной странице выполняется тот же
 * запрос входа, что у формы панели ([PanelWeb.script]); итог приходит меткой в консоли страницы. Не вышло (сменили пароль,
 * включили 2FA) — пароль кладётся в буфер на 30 с, и человек входит сам. [url] — адрес панели или её туннеля (127.0.0.1);
 * только у туннеля разрешено несовпадение сертификата: он выписан на IP сервера, а соединение и так идёт по SSH.
 * Cookie сессии стираются при закрытии.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PanelWebDialog(panel: PanelInstance, url: String, tunnel: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val login = remember(url) { PanelWeb.login(panel, url) }
    var status by remember { mutableStateOf(if (login != null) ctx.getString(R.string.ms_web_logging_in) else "") }
    val failedMsg = stringResource(R.string.ms_web_login_failed)
    var web by remember { mutableStateOf<WebView?>(null) }
    val origin = remember(url) { java.net.URL(url).let { "${it.protocol}://${it.authority}" } }

    DisposableEffect(Unit) {
        onDispose {
            web?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
            CookieManager.getInstance().removeSessionCookies(null)
        }
    }

    Dialog(onDismissRequest = { if (web?.canGoBack() == true) web?.goBack() else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Bg)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(panel.kind.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    if (status.isNotBlank()) Text(status, color = TextMuted, fontSize = 11.sp)
                }
                TextButton(onClick = onClose) { Text(stringResource(R.string.ms_web_close), color = AccentCyan) }
            }
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportMultipleWindows(false)
                    CookieManager.getInstance().setAcceptCookie(true)
                    var tried = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, u: String?) {
                            // вход — один раз и только на сайте самой панели
                            if (!tried && login != null && u?.startsWith(origin) == true) {
                                tried = true
                                view.evaluateJavascript(PanelWeb.script(login), null)
                            }
                        }

                        @SuppressLint("WebViewClientOnReceivedSslError")
                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                            val u = runCatching { java.net.URL(error.url) }.getOrNull()
                            // только туннель на этом устройстве: сертификат выписан на IP сервера, а не на 127.0.0.1
                            if (tunnel && u != null && u.host == "127.0.0.1" && "${u.protocol}://${u.authority}" == origin) handler.proceed()
                            else handler.cancel()
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            when (m.message()) {
                                PanelWeb.OK -> status = ""
                                PanelWeb.FAIL -> {
                                    status = failedMsg
                                    copySecretWeb(clip, panel.password)
                                    Toast.makeText(ctx, failedMsg, Toast.LENGTH_LONG).show()
                                }
                                else -> return false
                            }
                            return true
                        }
                    }
                    loadUrl(url)
                    web = this
                }
            })
        }
    }
}

/** Пароль в буфер на 30 секунд — как у «Открыть в браузере». */
private fun copySecretWeb(clip: androidx.compose.ui.platform.ClipboardManager, text: String) {
    if (text.isBlank()) return
    clip.setText(androidx.compose.ui.text.AnnotatedString(text))
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        if (clip.getText()?.text == text) clip.setText(androidx.compose.ui.text.AnnotatedString(""))
    }, 30_000)
}
