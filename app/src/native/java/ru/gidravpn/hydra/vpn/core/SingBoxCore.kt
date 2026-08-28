package ru.gidravpn.hydra.vpn.core

import android.os.ParcelFileDescriptor
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.TunOptions

/**
 * Интеграция sing-box через libbox.aar (gomobile-биндинг experimental/libbox).
 *
 * ВНИМАНИЕ: сигнатуры методов libbox/PlatformInterface меняются между версиями
 * sing-box. Код ориентирован на sing-box ~1.11–1.12. При обновлении .aar
 * компилятор укажет, какие члены PlatformInterface нужно доопределить —
 * это ожидаемо. Сверяйтесь с SagerNet/sing-box-for-android для целевой версии.
 */
class SingBoxCore : VpnCore {

    override val name = "sing-box (${Libbox.version()})"
    private var service: BoxService? = null

    override fun start(
        tun: ParcelFileDescriptor,
        profile: ServerProfile,
        onLog: (String) -> Unit,
        onStats: (TrafficStats) -> Unit,
    ) {
        // libbox.setup вызывается один раз за процесс (idempotent guard в SingBoxRuntime)
        SingBoxRuntime.ensureSetup()

        val config = SingBoxConfigBuilder.build(profile).toString(2)
        onLog("sing-box: конфиг сгенерирован (${config.length} байт)")

        val platform = HydraPlatformInterface(
            existingTun = tun,
            onLogLine = onLog,
        )
        val svc = Libbox.newService(config, platform)
        service = svc
        svc.start()
        onLog("sing-box: сервис запущен")

        // Периодический опрос статистики через CommandClient (см. HydraPlatformInterface)
        SingBoxRuntime.startStatsPolling(onStats)
    }

    override fun stop() {
        SingBoxRuntime.stopStatsPolling()
        runCatching { service?.close() }
        service = null
    }
}

/** Провайдер fd tun'а из VpnService в PlatformInterface libbox. */
class HydraPlatformInterface(
    private val existingTun: ParcelFileDescriptor,
    private val onLogLine: (String) -> Unit,
) : PlatformInterface {

    /**
     * libbox запрашивает tun через openTun(options). Мы уже подняли интерфейс
     * в VpnService, поэтому просто отдаём его файловый дескриптор.
     */
    override fun openTun(options: TunOptions): Int = existingTun.detachFd()

    override fun useProcFS(): Boolean = false

    // Остальные методы PlatformInterface (findConnectionOwner, packageNameByUid,
    // usePlatformDefaultInterfaceMonitor, startDefaultInterfaceMonitor, closeDefaultInterfaceMonitor,
    // getInterfaces, underNetworkExtension, includeAllNetworks, readWIFIState, writeLog ...)
    // необходимо реализовать под конкретную версию libbox. См. SFA:
    // io.nekohasekai.sfa.bg.PlatformInterfaceWrapper — это лучший референс.
    override fun writeLog(message: String) { onLogLine(message) }
}
