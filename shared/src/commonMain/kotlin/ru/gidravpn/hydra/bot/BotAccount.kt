package ru.gidravpn.hydra.bot

import org.json.JSONObject
import ru.gidravpn.hydra.data.model.Subscription
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Аккаунт бота «Радар» (= Hydra VPN; API для приложений, контракт — docs/API_APPS.md в репозитории
 * Chistovik92/radar) для ПК-клиента. Логика та же, что у Android (`data/botaccount`): вход по
 * одноразовому коду, чтение выданных подписок, выход. API только читает.
 */
data class BotSubscription(
    val panel: String,
    val title: String,
    /** `subscription` — ссылка подписки (её и импортируем); `key` и `config` — одиночный ключ/файл. */
    val linkKind: String,
    /** `ok`, `no_link`, `panel_error`, `missing`. */
    val state: String,
    val enabled: Boolean,
    val url: String,
    /** Из ответа бота: unix-время окончания (0 — бессрочно), лимит и использованный трафик в байтах (0 — без предела). */
    val expire: Long = 0,
    val trafficLimit: Long = 0,
    val trafficUsed: Long = 0,
) {
    val importable: Boolean get() = state == "ok" && enabled && linkKind == "subscription" && url.isNotBlank()
}

data class BotProfile(val userId: String, val username: String, val vpnState: String, val panels: Int)

/** Отказ бота: [code] — HTTP-код (0 — сети нет). */
class BotException(val code: Int, message: String) : IOException(message)

object BotJson {
    fun token(body: String): String = JSONObject(body).getString("token")

    fun errorText(body: String): String =
        runCatching { JSONObject(body).optString("error") }.getOrDefault("")

    fun profile(body: String): BotProfile {
        val o = JSONObject(body)
        val vpn = o.optJSONObject("vpn")
        return BotProfile(o.optString("user_id"), o.optString("username"), vpn?.optString("state") ?: "none", vpn?.optInt("panels") ?: 0)
    }

    fun subscriptions(body: String): List<BotSubscription> {
        val arr = JSONObject(body).optJSONArray("subscriptions") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            BotSubscription(
                panel = o.optString("panel"),
                title = o.optString("title").ifBlank { "#" + o.optString("panel") },
                linkKind = o.optString("link_kind", "subscription"),
                state = o.optString("state", "ok"),
                enabled = o.optBoolean("enabled", false),
                url = o.optString("url"),
                expire = o.optLong("expire", 0), trafficLimit = o.optLong("traffic_limit", 0), trafficUsed = o.optLong("traffic_used", 0),
            )
        }
    }

    /**
     * Приводит адрес к `https://host[:port]`; null — негоден. Токен и ссылки подписок идут по этому
     * соединению, поэтому обычный http допускается только в своей сети (localhost, 10.x, 192.168.x, 172.16–31.x).
     */
    fun normalizeServer(input: String): String? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        if (!s.contains("://")) s = "https://$s"
        val scheme = s.substringBefore("://").lowercase()
        if (scheme != "https" && scheme != "http") return null
        val authority = s.substringAfter("://").substringBefore('/').substringBefore('?')
        if (authority.isEmpty() || '@' in authority) return null
        val host = authority.substringBefore(':').lowercase()
        if (host.isEmpty()) return null
        if (scheme == "http" && !isLocalHost(host)) return null
        return "$scheme://$authority"
    }

    internal fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local")) return true
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
    }
}

/** Блокирующий клиент (вызывать из IO). Редиректы не выполняются: токен не должен уйти на чужой адрес. */
class BotClient(private val timeoutMs: Int = 15_000) {

    /** Проверка адреса без токена (`GET /app/ping` → `{"api":1,"service":"radar"}`): понятная ошибка вместо «неверный код». */
    fun ping(server: String) {
        val text = try { call("GET", "$server/api/v1/app/ping", null, null) } catch (e: BotException) { if (e.code == 404 || e.code == 0) throw e else throw BotException(-1, "Это не сервер бота «Радар» — проверьте адрес.") }
        val o = runCatching { JSONObject(text) }.getOrNull()
        if (o == null || o.optString("service") != "radar") throw BotException(-1, "Это не сервер бота «Радар» — проверьте адрес.")
    }

    fun link(server: String, code: String, device: String): String {
        ping(server)
        val body = JSONObject().put("code", code.filter { it.isDigit() }).put("device", device.take(40)).put("app", "hydravpn")
        return BotJson.token(call("POST", "$server/api/v1/app/link", null, body))
    }

    fun profile(server: String, token: String): BotProfile =
        BotJson.profile(call("GET", "$server/api/v1/app/me", token, null))

    fun subscriptions(server: String, token: String): List<BotSubscription> =
        BotJson.subscriptions(call("GET", "$server/api/v1/app/subscriptions", token, null))

    fun logout(server: String, token: String) { call("DELETE", "$server/api/v1/app/session", token, null) }

    private fun call(method: String, url: String, token: String?, body: JSONObject?): String {
        val conn = try {
            URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        } catch (e: IOException) {
            throw BotException(0, e.message ?: "bad url")
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.use(::readLimited).orEmpty()
            if (code >= 400) throw BotException(code, BotJson.errorText(text).ifBlank { "HTTP $code" })
            return text
        } catch (e: BotException) {
            throw e
        } catch (e: IOException) {
            throw BotException(0, e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(s: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < MAX) {
            val n = s.read(buf, 0, minOf(buf.size, MAX - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    private companion object { const val MAX = 2 shl 20 }
}

/**
 * Сопоставление подписок бота с подписками приложения (как BotSyncPlanner на Android): связь по панели,
 * ранее заведённые подхватываются по URL; выключенные в боте помечаются; ничего не удаляется.
 */
object BotSyncPlanner {
    const val DISABLED_MARK = "отключена в боте"

    sealed interface Step {
        data class Add(val item: BotSubscription) : Step
        data class Update(val sub: Subscription, val item: BotSubscription) : Step
        data class Disable(val sub: Subscription) : Step
    }

    fun plan(existing: List<Subscription>, items: List<BotSubscription>): List<Step> {
        val steps = mutableListOf<Step>()
        val claimed = mutableSetOf<Long>()
        for (item in items) {
            val sub = existing.firstOrNull { it.id !in claimed && it.botPanel.isNotEmpty() && it.botPanel == item.panel }
                ?: existing.firstOrNull { it.id !in claimed && it.botPanel.isEmpty() && item.url.isNotBlank() && it.url.trim() == item.url.trim() }
            if (sub != null) claimed += sub.id
            when {
                item.importable -> steps += if (sub != null) Step.Update(sub, item) else Step.Add(item)
                sub != null && !item.enabled && item.linkKind == "subscription" && sub.lastError != DISABLED_MARK -> steps += Step.Disable(sub)
            }
        }
        return steps
    }
}
