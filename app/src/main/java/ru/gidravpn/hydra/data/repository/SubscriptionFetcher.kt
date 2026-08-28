package ru.gidravpn.hydra.data.repository

import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.subscription.LinkParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Скачивает и парсит содержимое подписки (base64 или список ссылок). */
class SubscriptionFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    suspend fun fetch(url: String, userAgent: String, subscriptionId: Long?): List<ServerProfile> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")
                .build()
            client.newCall(req).execute().use { resp ->
                require(resp.isSuccessful) { "HTTP ${resp.code}" }
                val body = resp.body?.string().orEmpty()
                LinkParser.parseSubscription(body, subscriptionId)
            }
        }
}
