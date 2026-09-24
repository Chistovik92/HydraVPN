package ru.gidravpn.hydra.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Проверка обновлений (Фаза 8) — последний релиз на GitHub, основном канале распространения.
 * Приложение само APK не скачивает и не ставит (не нужен REQUEST_INSTALL_PACKAGES): показывает
 * версию и открывает страницу релиза в браузере.
 */
object UpdateChecker {
    const val REPO = "Chistovik92/HydraVPN"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    data class Release(val version: String, val pageUrl: String, val apkUrl: String?)

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    }

    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Hydra-VPN-update-check")
            .build()
        client.newCall(req).execute().use { resp ->
            require(resp.isSuccessful) { "HTTP ${resp.code}" }
            parse(resp.body?.string().orEmpty())
        }
    }

    internal fun parse(json: String): Release {
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets")
        val apk = assets?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }
                .firstOrNull { it.optString("name").matches(Regex("Hydra-full-.*\\.apk")) }
                ?.optString("browser_download_url")
        }
        return Release(
            version = o.getString("tag_name").removePrefix("v"),
            pageUrl = o.optString("html_url", "https://github.com/$REPO/releases/latest"),
            apkUrl = apk,
        )
    }

    /** true, если [remote] новее [current] («0.6.23» > «0.6.22.2»); суффикс «-stub» не мешает. */
    fun isNewer(remote: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val c = parts(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val d = r.getOrElse(i) { 0 } - c.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
