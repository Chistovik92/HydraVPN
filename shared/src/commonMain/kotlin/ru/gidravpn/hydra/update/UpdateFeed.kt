package ru.gidravpn.hydra.update

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Обновления напрямую из релизов GitHub (0.7.1): общий код Android и ПК. Ищет последний релиз, выбирает
 * файл под платформу, скачивает и сверяет SHA-256 (GitHub отдаёт `digest` у каждого файла релиза).
 * Установку делает платформа: Android — PackageInstaller, ПК — установщик ОС.
 * Только java.net — работает и на Android, и на ПК. Всё синхронно: вызывать не из UI-потока.
 */
data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)

data class ReleaseInfo(val version: String, val pageUrl: String, val assets: List<ReleaseAsset>)

object UpdateFeed {
    const val REPO = "Chistovik92/HydraVPN"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    fun latest(userAgent: String, timeoutMs: Int = 10_000): ReleaseInfo {
        val conn = URL(API).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", userAgent)
            check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            return parse(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            conn.disconnect()
        }
    }

    fun parse(json: String): ReleaseInfo {
        val o = JSONObject(json)
        val page = o.optString("html_url").takeIf { it.startsWith("https://github.com/") }
            ?: "https://github.com/$REPO/releases/latest"
        val arr = o.optJSONArray("assets")
        val assets = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val a = arr!!.optJSONObject(i) ?: return@mapNotNull null
            val url = a.optString("browser_download_url")
            if (!trustedUrl(url)) return@mapNotNull null
            ReleaseAsset(
                name = a.optString("name"), url = url, size = a.optLong("size"),
                sha256 = a.optString("digest").removePrefix("sha256:").lowercase().takeIf { Regex("^[0-9a-f]{64}$").matches(it) },
            )
        }
        return ReleaseInfo(o.getString("tag_name").removePrefix("v"), page, assets)
    }

    /** Файлы берём только с GitHub: имя хоста — github.com или *.githubusercontent.com, и только по https. */
    fun trustedUrl(url: String): Boolean {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = u.host?.lowercase() ?: return false
        return u.scheme == "https" && (host == "github.com" || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com"))
    }

    /** true, если [remote] новее [current] («0.7.1» > «0.7.0»); суффиксы «-stub»/«-dev» не мешают. */
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

    // ------------------------------------------------------------ выбор файла

    /**
     * Android: с 0.7.2 `Hydra-full-X-<abi>.apk` (по одному на архитектуру; берётся первая из [abis] телефона по
     * порядку предпочтения), раньше — один универсальный `Hydra-full-X.apk`. `stub` — для stub-сборки.
     */
    fun pickAndroid(info: ReleaseInfo, stub: Boolean, abis: List<String> = emptyList()): ReleaseAsset? {
        val kind = if (stub) "stub" else "full"
        for (abi in abis) {
            info.assets.firstOrNull { it.name.matches(Regex("Hydra-$kind-[0-9][0-9.]*-${Regex.escape(abi)}[.]apk")) }?.let { return it }
        }
        return info.assets.firstOrNull { it.name.matches(Regex("Hydra-$kind-[0-9][0-9.]*[.]apk")) }
    }

    /**
     * ПК: `Hydra-desktop-X-<os>-<arch>[-portable|-classic].<ext>`. [os] — windows/linux/macos, [arch] — x64/arm64/x86/armv7,
     * [exts] — подходящие расширения по убыванию предпочтения («msi», «exe», «zip» …).
     * [classic] — сборка Classic (Swing; 32-бит и Windows 7): `…-classic.<ext>`. Обычная Hydra её файлы не берёт,
     * а Classic — только их (у x64 есть и обычные, и classic-файлы с одним префиксом).
     */
    fun pickDesktop(info: ReleaseInfo, os: String, arch: String, exts: List<String>, classic: Boolean = false): ReleaseAsset? {
        for (ext in exts) {
            info.assets.firstOrNull { a ->
                a.name.startsWith("Hydra-desktop-") && a.name.contains("-$os-$arch") && a.name.endsWith(".$ext", ignoreCase = true) &&
                    a.name.contains("-classic") == classic
            }?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------ загрузка

    /**
     * Скачивает [asset] в [dest] (через временный файл) и сверяет размер и SHA-256. При несовпадении файл
     * удаляется и бросается [IOException]: подменённый или оборванный файл не должен дойти до установки.
     * [onProgress] получает (скачано, всего); возврат false из него прерывает загрузку.
     */
    fun download(asset: ReleaseAsset, dest: File, onProgress: (Long, Long) -> Boolean = { _, _ -> true }): File {
        require(trustedUrl(asset.url)) { "адрес файла не с GitHub" }
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.delete()
        var url = asset.url
        var conn: HttpURLConnection? = null
        try {
            var redirects = 0
            while (true) {
                val c = URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "Hydra-update")
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location").orEmpty()
                    c.disconnect()
                    if (++redirects > 5 || !trustedUrl(next)) throw IOException("переадресация на чужой адрес")
                    url = next
                    continue
                }
                if (code !in 200..299) { c.disconnect(); throw IOException("HTTP $code") }
                conn = c
                break
            }
            val total = conn!!.contentLengthLong.takeIf { it > 0 } ?: asset.size
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); done += n
                        if (!onProgress(done, total)) throw IOException("загрузка отменена")
                    }
                }
            }
            if (asset.size > 0 && done != asset.size) throw IOException("файл скачан не полностью ($done из ${asset.size} байт)")
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (asset.sha256 == null) throw IOException("у файла нет контрольной суммы — установка отклонена")
            if (!hex.equals(asset.sha256, ignoreCase = true)) {
                throw IOException("контрольная сумма не совпала — файл не будет установлен")
            }
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("не удалось сохранить файл")
            return dest
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            conn?.disconnect()
        }
    }
}
