package ru.gidravpn.hydra.vpn.core

import android.content.Context
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import ru.gidravpn.hydra.AppCtx
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.SplitTunnel
import ru.gidravpn.hydra.data.repository.SplitTunnelRepository
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Общая схема «клиент-исполняемый файл → локальный SOCKS5 → sing-box → tun» (olcRTC, OpenFlux).
 *
 * Клиент — не библиотека, а обычный Go-бинарь `lib<имя>.so` из `nativeLibraryDir`
 * (собирается scripts/build-*.sh), запускаемый подпроцессом: у него свой Go-рантайм в своём
 * процессе, поэтому нет конфликта go.Seq с libbox. Официальный Android-клиент OpenFlux
 * устроен так же. Сокеты подпроцесса принадлежат UID приложения, а приложение исключено из
 * tun (addDisallowedApplication) — петли маршрутизации нет.
 *
 * Секреты (ключи, токены) лежат в файлах внутреннего каталога только на время работы и
 * удаляются в [stop].
 */
abstract class SocksBridgeCore : VpnCore {

    /** Имя исполняемого файла в nativeLibraryDir, например `libolcrtc.so`. */
    protected abstract val binaryName: String
    protected abstract val socksPort: Int
    /** Короткое имя для логов и ошибок. */
    protected abstract val label: String
    protected abstract val buildScript: String
    protected open val readyTimeoutMs: Long = 45_000

    /** Готовит запуск: пишет файлы конфигурации (регистрируя их в [secrets]) и возвращает аргументы. */
    protected abstract fun prepare(ctx: Context, profile: ServerProfile, secrets: MutableList<File>): List<String>

    @Volatile private var process: Process? = null
    @Volatile private var running = false
    @Volatile private var deathListener: ((String) -> Unit)? = null
    private var bridge: SingBoxCore? = null
    private val secretFiles = mutableListOf<File>()

    override fun setDeathListener(listener: (reason: String) -> Unit) { deathListener = listener }

    override fun start(
        tun: ParcelFileDescriptor,
        profile: ServerProfile,
        onLog: (String) -> Unit,
        onStats: (TrafficStats) -> Unit,
    ) {
        val ctx = AppCtx.appContext ?: error("AppCtx не инициализирован")
        val exe = File(ctx.applicationInfo.nativeLibraryDir, binaryName)
        check(exe.exists()) { "$label: в сборке нет $binaryName — соберите клиент ($buildScript) и пересоберите приложение" }

        val args = prepare(ctx, profile, secretFiles)
        val lastLines = ArrayDeque<String>()
        val proc = ProcessBuilder(listOf(exe.path) + args)
            .directory(ctx.filesDir)
            .redirectErrorStream(true)
            .apply { environment()["TMPDIR"] = ctx.cacheDir.path; environment()["HOME"] = ctx.filesDir.path }
            .start()
        process = proc
        running = true
        onLog("$label: клиент запущен")

        thread(name = "$binaryName-log", isDaemon = true) {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(lastLines) { lastLines.addLast(line); if (lastLines.size > 8) lastLines.removeFirst() }
                    onLog("$label: $line")
                }
            }
        }
        thread(name = "$binaryName-wait", isDaemon = true) {
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            if (running) deathListener?.invoke("процесс $label завершился (код $code)")
        }

        // SOCKS5 начинает слушать не сразу: клиент сначала устанавливает сессию с транспортом.
        val deadline = System.currentTimeMillis() + readyTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            check(proc.isAlive) {
                "$label: клиент завершился при запуске: " + synchronized(lastLines) { lastLines.joinToString(" | ") }
            }
            if (socksReady()) break
            Thread.sleep(300)
        }
        check(socksReady()) { "$label: SOCKS5 не поднялся за ${readyTimeoutMs / 1000} с — проверьте параметры" }
        onLog("$label: SOCKS5 127.0.0.1:$socksPort готов")

        val split = runBlocking { SplitTunnelRepository(ctx).settings.firstOrNull() } ?: SplitTunnel()
        val opts = resolveRouting(ctx)
        val bridgeConfig = SingBoxConfigBuilder.buildXrayBridge(
            socksPort, split, dns = opts.dns, geoRouting = opts.geoRouting, mtu = opts.mtu,
            hotspot = resolveHotspot(ctx),
        ).toString(2)
        val b = SingBoxCore()
        bridge = b
        b.setDeathListener { reason -> deathListener?.invoke(reason) }
        b.runConfig(tun, bridgeConfig, onLog, onStats)
    }

    override fun stop() {
        running = false
        bridge?.stop()
        bridge = null
        process?.let { p ->
            runCatching { p.destroy() }
            thread(isDaemon = true) { Thread.sleep(2000); runCatching { if (p.isAlive) p.destroyForcibly() } }
        }
        process = null
        secretFiles.forEach { runCatching { it.delete() } }
        secretFiles.clear()
    }

    private fun socksReady(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", socksPort), 300); true }
    } catch (_: Exception) { false }

    /** Файл с секретом: читаем только мы. */
    protected fun secretFile(ctx: Context, name: String, text: String, secrets: MutableList<File>): File {
        val f = File(ctx.filesDir, name)
        f.writeText(text)
        f.setReadable(false, false); f.setReadable(true, true)
        secrets += f
        return f
    }
}
