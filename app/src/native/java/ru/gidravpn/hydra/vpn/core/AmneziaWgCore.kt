package ru.gidravpn.hydra.vpn.core

import android.os.ParcelFileDescriptor
import org.amnezia.awg.GoBackend
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.subscription.WireGuardConfigBuilder
import ru.gidravpn.hydra.vpn.SocketGuard
import kotlin.concurrent.thread

/**
 * AmneziaWG (обфусцированный WireGuard), версии 1.0 / 1.5 / 2.0.
 *
 * Движок — `libwg-go.so` (amneziawg-go, см. docs/BUILD.md, раздел 2.3), лежит в
 * `app/libs/awg/<abi>/`. Обфускация — параметры в [Interface]: Jc/Jmin/Jmax/S1–S4/H1–H4
 * (мусорные пакеты, 1.0/1.5) и I1–I5 (маркеры, 2.0). Их uapi-ключи проверены по
 * исходнику `device/uapi.go` amneziawg-go v3.
 *
 * tun поднимает сервис с адресом и маршрутами из конфига (HydraVpnService.establishTun):
 * WireGuard не делает NAT, поэтому адрес интерфейса обязан совпадать с `Address` пира.
 */
class AmneziaWgCore : VpnCore {

    override val name = "AmneziaWG (amneziawg-go)"

    @Volatile private var handle = -1
    @Volatile private var running = false
    @Volatile private var deathListener: ((String) -> Unit)? = null
    private var statsThread: Thread? = null

    override fun setDeathListener(listener: (reason: String) -> Unit) { deathListener = listener }

    override fun start(
        tun: ParcelFileDescriptor,
        profile: ServerProfile,
        onLog: (String) -> Unit,
        onStats: (TrafficStats) -> Unit,
    ) {
        try {
            System.loadLibrary("wg-go")
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException(
                "AmneziaWG: в сборке нет libwg-go.so — соберите ядро (docs/BUILD.md, 2.3) и пересоберите приложение", e,
            )
        }
        val version = runCatching { org.json.JSONObject(profile.extra) }
            .getOrDefault(org.json.JSONObject()).optString("awg_version", "1.0")
        val uapi = WireGuardConfigBuilder.buildUapi(profile)
        onLog("AmneziaWG: amneziawg-go ${runCatching { GoBackend.awgVersion() }.getOrDefault("?")}, профиль $version")
        onLog("AmneziaWG: uapi — ${uapi.lineSequence().count()} строк, ключи скрыты")

        // Go получает tun во владение (detachFd), как sing-box; закрытие — в awgTurnOff.
        val h = GoBackend.awgTurnOn("hydra-awg", tun.detachFd(), uapi)
        check(h >= 0) { "AmneziaWG: не удалось поднять туннель (смотрите logcat, тег AmneziaWG/hydra-awg)" }
        handle = h
        running = true

        // Сокеты Go — за пределы VPN. Приложение и так исключено из tun, но protect() — гарантия
        // при режиме split «только выбранные», где исключение не действует.
        listOf(GoBackend.awgGetSocketV4(h), GoBackend.awgGetSocketV6(h)).filter { it >= 0 }
            .forEach { SocketGuard.protect(it) }
        onLog("AmneziaWG: туннель поднят (handle=$h)")

        statsThread = thread(name = "awg-stats", isDaemon = true) {
            while (running) {
                val cfg = runCatching { GoBackend.awgGetConfig(h) }.getOrNull()
                if (cfg == null) {
                    if (running) deathListener?.invoke("amneziawg-go не отвечает")
                    break
                }
                var rx = 0L; var tx = 0L
                cfg.lineSequence().forEach { l ->
                    when {
                        l.startsWith("rx_bytes=") -> rx += l.substringAfter('=').toLongOrNull() ?: 0
                        l.startsWith("tx_bytes=") -> tx += l.substringAfter('=').toLongOrNull() ?: 0
                    }
                }
                onStats(TrafficStats(downBytes = rx, upBytes = tx))
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }
    }

    override fun stop() {
        running = false
        statsThread?.interrupt()
        statsThread = null
        val h = handle
        handle = -1
        if (h >= 0) runCatching { GoBackend.awgTurnOff(h) }
    }
}
