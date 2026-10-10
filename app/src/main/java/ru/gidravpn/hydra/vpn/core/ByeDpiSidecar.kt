package ru.gidravpn.hydra.vpn.core

import android.content.Context
import ru.gidravpn.hydra.data.dpi.DpiArgs
import ru.gidravpn.hydra.data.dpi.DpiSettings
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Клиент-подпроцесс с локальным SOCKS5 на 127.0.0.1:[port] (`lib<имя>.so` из nativeLibraryDir): ByeDPI, OpenFlux, olcRTC.
 * Сокеты подпроцесса принадлежат UID приложения, а приложение исключено из tun (addDisallowedApplication),
 * поэтому его исходящие соединения в туннель не возвращаются. [secrets] — файлы с ключами, удаляются в [close].
 */
class SocksProcess(
    private val ctx: Context,
    private val label: String,
    private val binary: String,
    private val args: List<String>,
    private val port: Int,
    private val readyTimeoutMs: Long = 5_000,
    private val secrets: List<File> = emptyList(),
    private val onLog: (String) -> Unit = {},
) : AutoCloseable {

    @Volatile private var process: Process? = null

    /** Запускает и ждёт, пока порт начнёт слушать. Бросает исключение, если бинаря нет или он упал. */
    fun start() {
        val exe = File(ctx.applicationInfo.nativeLibraryDir, binary)
        check(exe.exists()) { "$label: в сборке нет $binary — соберите его (scripts/) и пересоберите приложение" }
        val lastLines = ArrayDeque<String>()
        val proc = ProcessBuilder(listOf(exe.path) + args)
            .directory(ctx.filesDir).redirectErrorStream(true)
            .apply { environment()["TMPDIR"] = ctx.cacheDir.path; environment()["HOME"] = ctx.filesDir.path }
            .start()
        process = proc
        onLog("$label: запущен")
        thread(name = "$binary-log", isDaemon = true) {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(lastLines) { lastLines.addLast(line); if (lastLines.size > 6) lastLines.removeFirst() }
                    onLog("$label: $line")
                }
            }
        }
        val deadline = System.currentTimeMillis() + readyTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            check(proc.isAlive) { "$label: завершился при запуске: " + synchronized(lastLines) { lastLines.joinToString(" | ") } }
            if (portOpen(port)) return
            Thread.sleep(150)
        }
        close()
        error("$label: порт $port не открылся за ${readyTimeoutMs / 1000} с")
    }

    override fun close() {
        process?.let { p ->
            runCatching { p.destroy() }
            thread(isDaemon = true) { Thread.sleep(1500); runCatching { if (p.isAlive) p.destroyForcibly() } }
        }
        process = null
        secrets.forEach { runCatching { it.delete() } }
    }

    fun stop() = close()

    private fun portOpen(port: Int) = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200); true }
    } catch (_: Exception) { false }
}

/** Локальный ByeDPI (`ciadpi`, MIT): SOCKS5, который режет и подделывает первые пакеты соединения. Бинарь собирает scripts/build-byedpi.sh. */
class ByeDpiSidecar(ctx: Context, settings: DpiSettings, onLog: (String) -> Unit) : AutoCloseable {
    private val proc = SocksProcess(ctx, "ByeDPI", BINARY, DpiArgs.build(settings.strategy, settings.port, sni = settings.fakeSni), settings.port, 5_000, emptyList(), onLog)
    fun start() = proc.start()
    fun stop() = proc.close()
    override fun close() = proc.close()

    companion object { const val BINARY = "libciadpi.so" }
}
