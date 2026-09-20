package ru.gidravpn.hydra.data.repository

import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.subscription.LinkParser
import ru.gidravpn.hydra.data.subscription.SubscriptionHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Скачивает и парсит содержимое подписки (base64 или список ссылок) и служебные заголовки
 * панели. Заголовки запроса (UA, HWID) — [HydraDevice.headers].
 */
class SubscriptionFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    data class Result(val profiles: List<ServerProfile>, val info: SubscriptionHeaders.Info)

    suspend fun fetch(url: String, headers: Map<String, String>, subscriptionId: Long?): Result =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(req).execute().use { resp ->
                require(resp.isSuccessful) { "HTTP ${resp.code}" }
                // body.string() читал ответ целиком без предела: сломанная или враждебная
                // панель, отдающая поток вместо списка серверов, выедала память до OOM.
                // Даже подписка на тысячи серверов укладывается в единицы мегабайт.
                val src = resp.body?.source()
                val body = if (src == null) "" else {
                    require(!src.request(MAX_BODY_BYTES + 1)) { "ответ подписки больше $MAX_BODY_MB МБ" }
                    src.readByteArray().toString(Charsets.UTF_8)
                }
                Result(
                    profiles = LinkParser.parseSubscription(body, subscriptionId),
                    info = SubscriptionHeaders.parse { resp.header(it) },
                )
            }
        }

    private companion object {
        const val MAX_BODY_MB = 8L
        const val MAX_BODY_BYTES = MAX_BODY_MB * 1024 * 1024
    }
}
