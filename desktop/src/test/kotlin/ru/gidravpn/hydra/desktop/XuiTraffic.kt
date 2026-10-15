package ru.gidravpn.hydra.desktop

import org.json.JSONArray
import org.json.JSONObject
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder
import java.io.File
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Трафик через профиль Hydra настоящим sing-box (0.8.1-rc2, живые тесты): исходящее соединение — ровно то, что строит
 * [SingBoxConfigBuilder.nodeOutbound] (как в приложении), вход — локальный HTTP/SOCKS-прокси; через него скачивается страница.
 */
object XuiTraffic {
    fun config(p: ServerProfile, port: Int): JSONObject = JSONObject()
        .put("log", JSONObject().put("level", "debug").put("timestamp", true))
        .put("inbounds", JSONArray().put(JSONObject().put("type", "mixed").put("tag", "in").put("listen", "127.0.0.1").put("listen_port", port)))
        .put("outbounds", JSONArray().put(SingBoxConfigBuilder.nodeOutbound(p, "proxy")!!).put(JSONObject().put("type", "direct").put("tag", "direct")))
        .put("route", JSONObject().put("final", "proxy"))
        // как в приложении: WireGuard — эндпоинтом sing-box 1.11+ (старый outbound не запускается без переменной окружения)
        .also { ru.gidravpn.hydra.desktop.core.DesktopConfig.convertWireGuard(it) }

    /**
     * То же ядром Xray Hydra: конфиг — [ru.gidravpn.hydra.data.subscription.XrayConfigBuilder] (как в приложении), вход — SOCKS,
     * страница качается `curl` через него.
     */
    fun throughXray(xray: String, p: ServerProfile, url: String = "https://speed.cloudflare.com/__down?bytes=2000000"): String {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val dir = kotlin.io.path.createTempDirectory("hydra-xr").toFile()
        val cfg = File(dir, "c.json").apply { writeText(ru.gidravpn.hydra.data.subscription.XrayConfigBuilder.build(p, port, dnsUrl = null, logLevel = "info").toString(2)) }
        val log = File(dir, "log.txt")
        val proc = ProcessBuilder(xray, "run", "-c", cfg.absolutePath).redirectErrorStream(true).redirectOutput(log).start()
        try {
            Thread.sleep(1500)
            val c = ProcessBuilder("curl", "-s", "-o", File(dir, "out").absolutePath, "-w", "%{http_code} %{size_download} %{time_total}", "--max-time", "40",
                "-x", "socks5h://127.0.0.1:$port", url).redirectErrorStream(true).start()
            val out = c.inputStream.readBytes().toString(Charsets.UTF_8).trim(); c.waitFor()
            val parts = out.split(" ")
            return if (parts.getOrNull(0)?.startsWith("2") == true && (parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0) > 100_000) "ok ${parts[1].toDouble().toInt() / 1024} КБ за ${parts.getOrNull(2)} с"
            else "curl: $out; ${log.readText().lines().filter { "Penetrates" !in it && "A unified" !in it }.takeLast(8).joinToString(" | ").takeLast(1200)}"
        } finally {
            proc.destroy(); proc.waitFor()
            dir.deleteRecursively()
        }
    }

    /** «ok …» — страница скачана; иначе причина и хвост журнала sing-box. */
    fun through(singBox: String, p: ServerProfile, url: String = "https://speed.cloudflare.com/__down?bytes=2000000"): String {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val dir = kotlin.io.path.createTempDirectory("hydra-sb").toFile()
        val cfg = File(dir, "c.json").apply { writeText(config(p, port).toString(2)) }
        val log = File(dir, "log.txt")
        val proc = ProcessBuilder(singBox, "run", "-c", cfg.absolutePath).redirectErrorStream(true).redirectOutput(log).start()
        try {
            Thread.sleep(1500)
            val c = HttpClient.newBuilder().proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", port))).connectTimeout(Duration.ofSeconds(15)).build()
            val t0 = System.currentTimeMillis()
            val r = c.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(40)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            val ms = System.currentTimeMillis() - t0
            return if (r.statusCode() in 200..299 && r.body().size > 100_000) "ok ${r.body().size / 1024} КБ за $ms мс"
            else "код ${r.statusCode()}, ${r.body().size} байт; ${log.readText().takeLast(1500)}"
        } catch (e: Exception) {
            return "ошибка ${e.javaClass.simpleName}: ${e.message}; ${log.readText().lines().filter { "ERROR" in it || "WARN" in it || "error" in it }.takeLast(12).joinToString("\n")}"
        } finally {
            proc.destroy(); proc.waitFor()
            dir.deleteRecursively()
        }
    }
}
