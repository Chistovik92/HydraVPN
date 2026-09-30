package ru.gidravpn.hydra.desktop.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Проверка обновлений — последний релиз на GitHub, как UpdateChecker на Android. Сама
 * ничего не скачивает и не ставит: показывает версию и открывает страницу релиза.
 */
object Updates {
    const val REPO = "Chistovik92/HydraVPN"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    data class Release(val version: String, val pageUrl: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    fun latest(): Release {
        val req = Request.Builder().url(API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Hydra-desktop-update-check")
            .build()
        http.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            val o = JSONObject(r.body?.string().orEmpty())
            val page = o.optString("html_url").takeIf { it.startsWith("https://github.com/") }
                ?: "https://github.com/$REPO/releases/latest"
            return Release(o.getString("tag_name").removePrefix("v"), page)
        }
    }

    /** true, если [remote] новее [current] («0.6.26» > «0.6.25.2»); суффиксы «-stub»/«-dev» не мешают. */
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
