package ru.gidravpn.hydra.router

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Роутер HydraVPN for Router (https://github.com/Chistovik92/HydraVPNforRouters), к
 * которому подключается приложение. Приходит ссылкой сопряжения, которую печатает
 * `hydravpn-router pair` и показывает веб-интерфейс роутера:
 * `hydravpn-router://192.168.1.1:8088?token=…&tls=1&fp=<sha256 сертификата>`.
 */
data class RouterLink(
    val host: String,
    val port: Int,
    val token: String,
    val tls: Boolean = false,
    /** SHA-256 сертификата роутера (hex) — закрепляется вместо доверия самоподписанному. */
    val fingerprint: String? = null,
    val name: String = host,
) {
    val baseUrl: String
        get() = (if (tls) "https" else "http") + "://" + (if (':' in host) "[$host]" else host) + ":$port"

    /** Токен по открытому HTTP видит любой в той же сети — UI предупреждает. */
    val insecure: Boolean get() = !tls && !isLoopback(host)

    fun toUri(): String = buildString {
        append(SCHEME).append("://").append(if (':' in host) "[$host]" else host).append(':').append(port)
        append("?token=").append(URLEncoder.encode(token, "UTF-8"))
        append("&tls=").append(if (tls) "1" else "0")
        fingerprint?.let { append("&fp=").append(it) }
    }

    companion object {
        const val SCHEME = "hydravpn-router"
        const val DEFAULT_PORT = 8088
        private val FP = Regex("^[0-9a-f]{64}$")
        private val HOST = Regex("^[A-Za-z0-9.-]{1,253}$")

        private fun isLoopback(h: String) = h == "localhost" || h.startsWith("127.") || h == "::1"

        /** Ссылка сопряжения или «адрес[:порт]» + отдельно токен. null — не разобралось. */
        fun parse(raw: String, tokenOverride: String? = null): RouterLink? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            val uri = runCatching {
                URI(if ("://" in s) s else "$SCHEME://$s")
            }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            if (scheme != SCHEME && scheme != "http" && scheme != "https") return null
            val host = uri.host?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() } ?: return null
            if (':' !in host && !HOST.matches(host)) return null
            val q = uri.rawQuery.orEmpty().split('&').filter { '=' in it }
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val token = (tokenOverride?.trim()?.takeIf { it.isNotEmpty() } ?: q["token"]?.trim()).orEmpty()
            if (token.isEmpty() || token.length > 256 || token.any { it.isWhitespace() || it.isISOControl() }) return null
            val tls = when (scheme) {
                "https" -> true
                "http" -> false
                else -> q["tls"] == "1" || q["tls"].equals("true", true)
            }
            val fp = q["fp"]?.lowercase()?.replace(":", "")?.takeIf { it.isNotEmpty() }
            if (fp != null && !FP.matches(fp)) return null
            val port = uri.port.takeIf { it in 1..65535 } ?: DEFAULT_PORT
            return RouterLink(host, port, token, tls, if (tls) fp else null)
        }
    }
}

class RouterException(val code: Int, message: String) : IOException(message)

data class RouterNode(
    val name: String,
    val type: String,
    /** У группы — выбранный узел. */
    val now: String = "",
    val members: List<String> = emptyList(),
    val delayMs: Int = 0,
    val country: String = "",
) {
    val isGroup: Boolean get() = members.isNotEmpty()
}

data class RouterSubscription(
    val index: Int,
    val section: String,
    /** Роутер отдаёт адрес с замаскированным токеном. */
    val url: String,
    val autoUpdate: Boolean,
    val updateIntervalHours: Double,
)

/**
 * [raw] — раздел как его отдал роутер (с замаскированными секретами): правка через PUT берёт его за основу, чтобы не
 * потерять поля, которых приложение не показывает.
 */
data class RouterSection(
    val name: String, val label: String, val enabled: Boolean, val action: String, val provider: String,
    val raw: JSONObject? = null,
) {
    /** Ссылки и JSON исходящих роутер отдаёт замаскированными («********»): записать такой раздел обратно значило бы стереть ключи. */
    val editable: Boolean get() = raw == null || listOf("selector_proxy_links", "outbound_jsons").none { k ->
        raw.optJSONArray(k)?.let { a -> (0 until a.length()).any { a.optString(it).isNotEmpty() } } == true
    }
    fun list(key: String): List<String> = raw?.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
    val title: String get() = label.ifBlank { name }
}

data class RouterLogEntry(val time: String, val level: String, val message: String)

/**
 * Клиент управляющего API роутера (`/api/v1/…`, docs/API.md проекта роутера). Только
 * java.net — работает и на Android, и на ПК. Всё синхронно: вызывать не из UI-потока.
 *
 * TLS: при известном отпечатке сертификат проверяется ТОЛЬКО по нему (самоподписанный
 * сертификат роутера), без отпечатка — обычной цепочкой доверия системы.
 */
class RouterClient(val link: RouterLink, private val timeoutMs: Int = 10_000) {

    fun version(): Pair<String, String> = obj(get("/api/v1/version")).let { it.optString("version") to it.optString("commit") }

    fun status(): JSONObject = obj(get("/api/v1/status"))

    fun sections(): List<RouterSection> = arr(get("/api/v1/sections")).objects().map {
        RouterSection(it.optString("name"), it.optString("label"), it.optBoolean("enabled"),
            it.optString("action"), it.optString("provider").ifBlank { "singbox" }, it)
    }

    fun subscriptions(): List<RouterSubscription> = arr(get("/api/v1/subscriptions")).objects().map {
        RouterSubscription(
            index = it.optInt("index"),
            section = it.optString("section"),
            url = it.optString("url"),
            autoUpdate = it.optBoolean("subscription_update_enabled"),
            // time.Duration в JSON — наносекунды.
            updateIntervalHours = it.optLong("subscription_update_interval") / 3.6e12,
        )
    }

    /** Добавить подписку в секцию роутера; он сохранит конфиг и применит его сразу. */
    fun addSubscription(section: String, url: String, updateHours: Int = 24) {
        require(url.startsWith("http://", true) || url.startsWith("https://", true)) { "адрес подписки должен начинаться с https://" }
        // Сервер отвергает неизвестные поля — отправляем ровно поля SubscriptionURL.
        post("/api/v1/subscriptions", JSONObject()
            .put("section", section).put("url", url)
            .put("subscription_update_enabled", updateHours > 0)
            .put("subscription_update_interval", updateHours.coerceAtLeast(1) * 3_600_000_000_000L))
    }

    fun refreshSubscription(index: Int) { post("/api/v1/subscriptions/$index/refresh", null) }

    fun deleteSubscription(index: Int) { request("DELETE", "/api/v1/subscriptions/$index", null) }

    fun nodes(): List<RouterNode> = arr(get("/api/v1/nodes")).objects().map {
        RouterNode(it.optString("name"), it.optString("type"), it.optString("now"),
            it.optJSONArray("members")?.let { a -> (0 until a.length()).map(a::optString) }.orEmpty(),
            it.optInt("delay_ms"), it.optString("country"))
    }

    fun selectNode(group: String, node: String) {
        post("/api/v1/nodes/select", JSONObject().put("group", group).put("node", node))
    }

    fun testNode(node: String): Int = obj(post("/api/v1/nodes/test", JSONObject().put("node", node))).optInt("delay_ms", -1)

    fun logs(n: Int = 200, level: String? = null): List<RouterLogEntry> =
        arr(get("/api/v1/logs?n=${n.coerceIn(1, 1000)}" + (level?.let { "&level=" + URLEncoder.encode(it, "UTF-8") } ?: "")))
            .objects().map { RouterLogEntry(it.optString("time"), it.optString("level"), it.optString("message")) }

    fun reload() { post("/api/v1/reload", null) }

    fun restart() { post("/api/v1/restart", null) }

    /** Диагностика роутера: global, dns, singbox, nft, proxy … */
    fun check(name: String): String {
        require(name.matches(Regex("^[a-z0-9_-]{1,32}$"))) { "имя проверки" }
        return get("/api/v1/check/$name")
    }

    // ------------------------------------------------------------ разделы (PUT заменяет раздел целиком)

    fun addSection(section: JSONObject) { post("/api/v1/sections", section) }

    fun replaceSection(name: String, section: JSONObject) {
        require(name.matches(NAME)) { "имя раздела" }
        request("PUT", "/api/v1/sections/$name", section)
    }

    fun deleteSection(name: String) {
        require(name.matches(NAME)) { "имя раздела" }
        request("DELETE", "/api/v1/sections/$name", null)
    }

    // ------------------------------------------------------------ аккаунт бота «Радар» на самом роутере

    /** `{"linked":true,"server":"…","username":"…"}`; токен роутер никогда не отдаёт. */
    fun radar(): JSONObject = obj(get("/api/v1/radar"))

    /** Роутер меняет код на токен и сам забирает подписки в раздел [section] (пусто — `main`). */
    fun radarLink(server: String, code: String, section: String?): JSONObject {
        val b = JSONObject().put("server", server).put("code", code.filter { it.isDigit() })
        if (!section.isNullOrBlank()) b.put("section", section)
        return obj(post("/api/v1/radar/link", b))
    }

    fun radarSync(section: String?): JSONObject =
        obj(post("/api/v1/radar/sync", JSONObject().apply { if (!section.isNullOrBlank()) put("section", section) }))

    fun radarUnlink() { request("DELETE", "/api/v1/radar", null) }

    // ------------------------------------------------------------ транспорт
    private fun get(path: String) = request("GET", path, null)
    private fun post(path: String, body: JSONObject?) = request("POST", path, body ?: JSONObject())

    private fun request(method: String, path: String, body: JSONObject?): String {
        val conn = URL(link.baseUrl + path).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        try {
            if (conn is HttpsURLConnection && link.fingerprint != null) {
                conn.sslSocketFactory = pinnedContext(link.fingerprint).socketFactory
                // Имя в самоподписанном сертификате роутера не обязано совпадать с адресом:
                // подлинность уже доказана отпечатком.
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            // Проверки и обновление подписки на роутере идут до минуты с лишним.
            conn.readTimeout = if (path.startsWith("/api/v1/check") || path.startsWith("/api/v1/radar") || path.endsWith("/refresh") || method != "GET") 100_000 else timeoutMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer ${link.token}")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use(::readLimited).orEmpty()
            if (code >= 400) {
                val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: when (code) {
                        401 -> "неверный токен роутера"
                        403 -> "роутер не принимает подключения с этого адреса (api_allow)"
                        429 -> "слишком много неверных попыток — подождите минуту"
                        else -> text.trim().take(200).ifEmpty { "HTTP $code" }
                    }
                throw RouterException(code, msg)
            }
            return text
        } catch (e: java.net.UnknownServiceException) {
            // Android запрещает http без TLS (network_security_config): вместо «CLEARTEXT communication not permitted» — понятная причина.
            throw RouterException(0, "система запрещает соединение без TLS — включите api_tls_cert/api_tls_key на роутере и добавьте его заново по новой ссылке")
        } finally {
            conn.disconnect()
        }
    }

    private fun obj(s: String) = JSONObject(s)
    private fun arr(s: String) = if (s.isBlank() || s.trim() == "null") JSONArray() else JSONArray(s)
    private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }

    companion object {
        private const val MAX_RESPONSE = 4 shl 20
        private val NAME = Regex("^[A-Za-z0-9_.-]{1,64}$")

        /** Не больше [MAX_RESPONSE] байт (readNBytes на Android — только с API 33). */
        private fun readLimited(s: java.io.InputStream): String {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() < MAX_RESPONSE) {
                val n = s.read(buf, 0, minOf(buf.size, MAX_RESPONSE - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        }

        fun sha256Hex(der: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

        /** TLS, где сертификат сервера принимается только при совпадении SHA-256 с [fp]. */
        fun pinnedContext(fp: String): SSLContext {
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                    throw java.security.cert.CertificateException("client certs not supported")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("нет сертификата")
                    if (!sha256Hex(leaf.encoded).equals(fp, ignoreCase = true)) {
                        throw java.security.cert.CertificateException("сертификат роутера не совпадает с сопряжённым — возможна подмена")
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        }
    }
}
