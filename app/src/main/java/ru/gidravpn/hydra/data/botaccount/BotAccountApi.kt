package ru.gidravpn.hydra.data.botaccount

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Аккаунт бота «Радар» (API для приложений, бот 5.9.1; контракт — docs/API_APPS.md в репозитории
 * Chistovik92/radar). Человек уже есть в боте и уже получил там доступ к VPN; приложение
 * подключается к его аккаунту по одноразовому коду и забирает выданные подписки.
 *
 * API только читает: выдаёт и отзывает доступ суперадминистратор в боте.
 */
data class BotSubscription(
    val panel: String,
    val title: String,
    /** `subscription` — ссылка подписки (её и импортируем); `key` и `config` — одиночный ключ/файл. */
    val linkKind: String,
    /** `ok`, `no_link`, `panel_error`, `missing`. */
    val state: String,
    val enabled: Boolean,
    val expire: Long,
    val trafficLimit: Long,
    val trafficUsed: Long,
    val url: String,
) {
    /** Годится ли запись для импорта как подписка. */
    val importable: Boolean get() = state == "ok" && enabled && linkKind == "subscription" && url.isNotBlank()
}

data class BotProfile(val userId: String, val username: String, val vpnState: String, val panels: Int)

/** Отказ бота: [code] — HTTP-код (0 — сети нет), [message] — текст бота, если он его прислал. */
class BotAccountException(val code: Int, message: String) : Exception(message)

/** Разбор ответов и проверка адреса — без сети и Android, чтобы проверять в JVM-тестах. */
object BotAccountJson {

    fun token(body: String): String = JSONObject(body).getString("token")

    fun errorText(body: String): String =
        runCatching { JSONObject(body).optString("error") }.getOrDefault("")

    fun profile(body: String): BotProfile {
        val o = JSONObject(body)
        val vpn = o.optJSONObject("vpn")
        return BotProfile(
            userId = o.optString("user_id"),
            username = o.optString("username"),
            vpnState = vpn?.optString("state") ?: "none",
            panels = vpn?.optInt("panels") ?: 0,
        )
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
                expire = o.optLong("expire", 0),
                trafficLimit = o.optLong("traffic_limit", 0),
                trafficUsed = o.optLong("traffic_used", 0),
                url = o.optString("url"),
            )
        }
    }

    /**
     * Приводит введённый адрес к виду `https://host[:port]`; null — адрес негоден.
     * Токен и ссылки подписок идут по этому соединению, поэтому обычный http допускается
     * только в своей сети (localhost, 10.x, 192.168.x, 172.16–31.x) и только если [allowHttp]: в релизе
     * приложение вообще не пускает открытый трафик (network_security_config), http там бесполезен.
     */
    fun normalizeServer(input: String, allowHttp: Boolean = true): String? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        if (!s.contains("://")) s = "https://$s"
        val scheme = s.substringBefore("://").lowercase()
        if (scheme != "https" && scheme != "http") return null
        val authority = s.substringAfter("://").substringBefore('/').substringBefore('?')
        // Логин и пароль в адресе не нужны и в журнал попасть не должны.
        if (authority.isEmpty() || '@' in authority) return null
        val host = authority.substringBefore(':').lowercase()
        if (host.isEmpty()) return null
        if (scheme == "http" && (!allowHttp || !isLocalHost(host))) return null
        return "$scheme://$authority"
    }

    internal fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local")) return true
        val p = host.split('.').map { it.toIntOrNull() ?: return false }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
    }
}

class BotAccountApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // Редирект мог бы унести токен на другой адрес или понизить https до http.
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    private val json = "application/json; charset=utf-8".toMediaType()

    /** Проверка адреса без токена: понятная ошибка вместо «неверный код» (`GET /app/ping`). */
    suspend fun ping(server: String) {
        val text = try { call(Request.Builder().url("$server/api/v1/app/ping").build()) } catch (e: BotAccountException) {
            if (e.code == 404 || e.code == 0) throw e else throw BotAccountException(-1, "")
        }
        val service = runCatching { JSONObject(text).optString("service") }.getOrDefault("")
        if (service != "radar") throw BotAccountException(-1, "")
    }

    /** Обмен кода из бота на токен устройства. Токен виден один раз — сохранить сразу. */
    suspend fun link(server: String, code: String, device: String): String {
        ping(server)
        val body = JSONObject().put("code", code.filter { it.isDigit() })
            .put("device", device.take(40)).put("app", "hydravpn").toString()
        val req = Request.Builder().url("$server/api/v1/app/link")
            .post(body.toRequestBody(json)).build()
        return BotAccountJson.token(call(req))
    }

    suspend fun profile(server: String, token: String): BotProfile =
        BotAccountJson.profile(call(authorised("$server/api/v1/app/me", token).build()))

    suspend fun subscriptions(server: String, token: String): List<BotSubscription> =
        BotAccountJson.subscriptions(call(authorised("$server/api/v1/app/subscriptions", token).build()))

    /** Отключить это устройство на стороне бота. */
    suspend fun logout(server: String, token: String) {
        call(authorised("$server/api/v1/app/session", token).delete().build())
    }

    private fun authorised(url: String, token: String) =
        Request.Builder().url(url).header("Authorization", "Bearer $token")

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw BotAccountException(resp.code, BotAccountJson.errorText(text).ifBlank { "HTTP ${resp.code}" })
                }
                text
            }
        } catch (e: BotAccountException) {
            throw e
        } catch (e: java.io.IOException) {
            throw BotAccountException(0, e.message ?: e.javaClass.simpleName)
        }
    }
}
