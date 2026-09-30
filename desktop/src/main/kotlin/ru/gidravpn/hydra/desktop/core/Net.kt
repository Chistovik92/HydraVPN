package ru.gidravpn.hydra.desktop.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.subscription.LinkParser
import ru.gidravpn.hydra.data.subscription.SubscriptionHeaders
import ru.gidravpn.hydra.desktop.Platform
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** clash_api ядра на 127.0.0.1: скорость и проверка задержки через туннель. */
class ClashApi(private val api: DesktopConfig.Api) {
    // Без системного прокси: иначе в режиме PROXY запрос к API пошёл бы сам через себя.
    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val base = "http://127.0.0.1:${api.port}"

    private fun req(path: String) = Request.Builder().url(base + path)
        .header("Authorization", "Bearer ${api.secret}").build()

    /** Поток {"up","down"} байт/с раз в секунду, пока [active] и ядро живо. */
    fun streamTraffic(active: () -> Boolean, onSample: (up: Long, down: Long) -> Unit) = thread(isDaemon = true, name = "clash-traffic") {
        repeat(40) {   // ядру нужно время поднять API
            if (!active()) return@thread
            val ok = runCatching {
                http.newCall(req("/traffic")).execute().use { r ->
                    if (!r.isSuccessful) return@use false
                    val src = r.body!!.source()
                    while (active()) {
                        val line = src.readUtf8Line() ?: break
                        val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        onSample(o.optLong("up"), o.optLong("down"))
                    }
                    true
                }
            }.getOrDefault(false)
            if (ok) return@thread
            Thread.sleep(250)
        }
    }

    /** Задержка HTTP-запроса через outbound "proxy" (мс) — проверка, что туннель реально работает. */
    fun delay(url: String = "https://www.gstatic.com/generate_204", timeoutMs: Int = 6000): Result<Int> = runCatching {
        val q = "url=" + URLEncoder.encode(url, "UTF-8") + "&timeout=$timeoutMs"
        http.newBuilder().readTimeout(timeoutMs + 2000L, TimeUnit.MILLISECONDS).build()
            .newCall(req("/proxies/proxy/delay?$q")).execute().use { r ->
                val body = r.body?.string().orEmpty()
                check(r.isSuccessful) { JSONObject(body).optString("message", "HTTP ${r.code}") }
                JSONObject(body).getInt("delay")
            }
    }
}

object Subscriptions {
    /** Больше — не подписка: не даём чужому серверу занять всю память приложения. */
    const val MAX_BYTES = 8L shl 20

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        // https → http при редиректе раскрыл бы токен подписки в URL по открытому каналу.
        .followSslRedirects(false)
        .build()

    data class Fetched(val servers: List<ServerProfile>, val info: SubscriptionHeaders.Info)

    /** Скачивает и разбирает подписку (plain/base64-список ссылок). */
    fun fetch(url: String, subscriptionId: Long): Fetched {
        val req = Request.Builder().url(url.trim())
            // Панели (Remnawave, Marzban, 3x-ui) по UA отдают формат: нам нужен список ссылок.
            .header("User-Agent", "Hydra/${Platform.version} (${Platform.os.name.lowercase()}; v2ray-links)")
            .build()
        http.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            val src = r.body?.source() ?: error("пустой ответ")
            check(!src.request(MAX_BYTES + 1)) { "ответ больше ${MAX_BYTES shr 20} МБ — это не подписка" }
            val body = src.buffer.readUtf8()
            val servers = LinkParser.parseSubscription(body, subscriptionId)
            check(servers.isNotEmpty()) { "в ответе нет поддерживаемых ссылок" }
            return Fetched(servers, SubscriptionHeaders.parse { r.header(it) })
        }
    }
}

object Ping {
    /** Время TCP-рукопожатия с сервером, мс; -2 — недоступен. Для UDP-протоколов — ориентир. */
    fun tcp(host: String, port: Int, timeoutMs: Int = 3000): Int = runCatching {
        Socket(Proxy.NO_PROXY).use { s ->
            val t0 = System.nanoTime()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            ((System.nanoTime() - t0) / 1_000_000).toInt().coerceAtLeast(1)
        }
    }.getOrDefault(-2)
}

/** Запущенные программы — для выбора в «Раздельном туннелировании по приложениям». */
object Processes {
    data class Proc(val name: String, val path: String)

    fun running(): List<Proc> = runCatching {
        ProcessHandle.allProcesses().use { stream ->
            stream.map { it.info().command().orElse(null) }.toList()
        }.filterNotNull()
            .map { Proc(java.io.File(it).name, it) }
            .distinctBy { it.path.lowercase() }
            .sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())
}
