package ru.gidravpn.hydra.vpn.core

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import ru.gidravpn.hydra.AppCtx
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.SplitTunnel
import ru.gidravpn.hydra.data.repository.SplitTunnelRepository
import ru.gidravpn.hydra.data.subscription.OlcRtcConfigBuilder
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * olcRTC (BETA) — TCP поверх WebRTC через легальные сервисы видеозвонков
 * (Jitsi / Яндекс Телемост / WB Stream). Апстрим — github.com/openlibrecommunity/olcrtc (WTFPL).
 *
 * Схема та же, что у Xray-моста: клиент `olcrtc` (режим `cnc`) поднимает локальный SOCKS5,
 * а sing-box берёт tun и отправляет весь трафик в этот SOCKS5 (buildXrayBridge).
 *
 * Клиент — не gomobile-библиотека, а обычный исполняемый файл `libolcrtc.so` из
 * `nativeLibraryDir` (сборка — scripts/build-olcrtc.sh), запускаемый подпроцессом. Так у
 * него свой Go-рантайм в своём процессе и нет конфликта go.Seq с libbox.
 * Сокеты подпроцесса принадлежат UID приложения, а приложение исключено из tun — петли нет.
 *
 * Ограничения: нужен сервер `olcrtc srv` с тем же ключом и комнатой; ключ лежит в YAML во
 * внутреннем каталоге приложения только на время работы (удаляется в stop()).
 * Не проверено с живым сервером.
 */
class OlcRtcCore : VpnCore {

    override val name = "olcRTC (WebRTC, beta)"

    private val socksPort = 10809
    @Volatile private var process: Process? = null
    @Volatile private var running = false
    @Volatile private var deathListener: ((String) -> Unit)? = null
    private var bridge: SingBoxCore? = null
    private var configFile: File? = null

    override fun setDeathListener(listener: (reason: String) -> Unit) { deathListener = listener }

    override fun start(
        tun: ParcelFileDescriptor,
        profile: ServerProfile,
        onLog: (String) -> Unit,
        onStats: (TrafficStats) -> Unit,
    ) {
        val ctx = AppCtx.appContext ?: error("AppCtx не инициализирован")
        val exe = File(ctx.applicationInfo.nativeLibraryDir, "libolcrtc.so")
        check(exe.exists()) {
            "olcRTC: в сборке нет libolcrtc.so — соберите клиент (scripts/build-olcrtc.sh) и пересоберите приложение"
        }

        val cfg = File(ctx.filesDir, "olcrtc-client.yaml").also { configFile = it }
        cfg.writeText(OlcRtcConfigBuilder.build(profile, socksPort))
        cfg.setReadable(false, false); cfg.setReadable(true, true)   // в конфиге ключ

        val lastLines = ArrayDeque<String>()
        val proc = ProcessBuilder(exe.path, cfg.path)
            .directory(ctx.filesDir)
            .redirectErrorStream(true)
            .apply { environment()["TMPDIR"] = ctx.cacheDir.path; environment()["HOME"] = ctx.filesDir.path }
            .start()
        process = proc
        running = true
        onLog("olcRTC: клиент запущен (провайдер ${providerOf(profile)}, транспорт ${profile.transport})")

        thread(name = "olcrtc-log", isDaemon = true) {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(lastLines) { lastLines.addLast(line); if (lastLines.size > 8) lastLines.removeFirst() }
                    onLog("olcrtc: $line")
                }
            }
        }
        thread(name = "olcrtc-wait", isDaemon = true) {
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            if (running) deathListener?.invoke("процесс olcrtc завершился (код $code)")
        }

        // Ждём, пока SOCKS5 начнёт слушать (установка WebRTC-сессии занимает секунды).
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            check(proc.isAlive) {
                "olcRTC: клиент завершился при запуске: " + synchronized(lastLines) { lastLines.joinToString(" | ") }
            }
            if (socksReady()) break
            Thread.sleep(300)
        }
        check(socksReady()) { "olcRTC: SOCKS5 не поднялся за 45 с (комната/ключ/провайдер верны?)" }
        onLog("olcRTC: SOCKS5 127.0.0.1:$socksPort готов")

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
        configFile?.let { runCatching { it.delete() } }   // в нём ключ
        configFile = null
    }

    private fun socksReady(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", socksPort), 300); true }
    } catch (_: Exception) { false }

    private fun providerOf(p: ServerProfile) =
        runCatching { org.json.JSONObject(p.extra).optString("provider") }.getOrDefault("?")
}
