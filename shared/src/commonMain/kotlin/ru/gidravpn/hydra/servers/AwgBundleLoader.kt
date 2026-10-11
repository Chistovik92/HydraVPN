package ru.gidravpn.hydra.servers

import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Серверный пакет AmneziaWG нужной архитектуры: из релиза Hydra (`Hydra-awg-server-<версия>-<arch>.zip` + `.sha256`),
 * с проверкой SHA-256 до распаковки. Для проверок без интернета — каталог [localDir] с тремя файлами.
 */
object AwgBundleLoader {
    const val REPO = "Chistovik92/HydraVPN"
    private val NAMES = listOf("amneziawg-go", "awg", "awg-quick")

    fun url(version: String, arch: String) = "https://github.com/$REPO/releases/download/v$version/Hydra-awg-server-$version-$arch.zip"

    fun fromDir(arch: String, dir: File): AwgBundle = AwgBundle(arch, NAMES.associateWith {
        val d = File(dir, it).readBytes()
        BundleFile(it, d, Installer.sha256(d))
    })

    fun download(version: String, arch: String, onLog: (String) -> Unit = {}): AwgBundle {
        onLog("Загружаю серверный пакет AmneziaWG ($arch, v$version)…")
        val zip = get(url(version, arch))
        val expected = String(get(url(version, arch) + ".sha256")).trim().substringBefore(' ').lowercase()
        val got = Installer.sha256(zip)
        require(expected.length == 64 && got == expected) { "Контрольная сумма пакета не совпала (ожидалась ${expected.take(12)}…, получена ${got.take(12)}…)" }
        return parse(arch, zip)
    }

    fun parse(arch: String, zip: ByteArray): AwgBundle {
        val files = mutableMapOf<String, BundleFile>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name.substringAfterLast('/')
                if (n in NAMES && !e.isDirectory) {
                    val d = z.readBytes()
                    files[n] = BundleFile(n, d, Installer.sha256(d))
                }
            }
        }
        require(files.keys.containsAll(NAMES)) { "В пакете нет файлов: ${NAMES - files.keys}" }
        return AwgBundle(arch, files)
    }

    private fun get(u: String): ByteArray {
        var url = u
        repeat(5) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 60_000; c.instanceFollowRedirects = true
            if (c.responseCode == 200) return c.inputStream.use { it.readBytes() }
            if (c.responseCode in 301..308) { url = c.getHeaderField("Location") ?: error("redirect без Location"); return@repeat }
            error("HTTP ${c.responseCode} для $u")
        }
        error("слишком много перенаправлений: $u")
    }
}
