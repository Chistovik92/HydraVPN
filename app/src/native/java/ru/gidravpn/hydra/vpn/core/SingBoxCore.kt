package ru.gidravpn.hydra.vpn.core

import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.TunOptions
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder

/**
 * Интеграция sing-box через libbox.aar (gomobile-биндинг experimental/libbox).
 *
 * ВНИМАНИЕ: сигнатуры методов libbox/PlatformInterface меняются между версиями
 * sing-box. Код ориентирован на sing-box ~1.12. При обновлении .aar
 * компилятор укажет, какие члены PlatformInterface нужно доопределить —
 * это ожидаемо. Сверяйтесь с SagerNet/sing-box-for-android →
 * io.nekohasekai.sfa.bg.PlatformInterfaceWrapper (лучший референс).
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

        // Периодический опрос статистики через CommandClient (см. SingBoxRuntime)
        SingBoxRuntime.startStatsPolling(onStats)
    }

    override fun stop() {
        SingBoxRuntime.stopStatsPolling()
        runCatching { service?.close() }
        service = null
    }
}

/**
 * Провайдер fd tun'а из VpnService в PlatformInterface libbox + платформенные
 * методы. Версионно-зависимые члены (возвращающие libbox-типы) помечены
 * TODO(libbox-версия) — точные сигнатуры сверяются с собранным .aar.
 */
class HydraPlatformInterface(
    private val existingTun: ParcelFileDescriptor,
    private val onLogLine: (String) -> Unit,
) : PlatformInterface {

    /** Контекст приложения для системных сервисов. */
    private val appContext get() = ru.gidravpn.hydra.AppCtx.appContext

    /**
     * libbox запрашивает tun через openTun(options). Мы уже подняли интерфейс
     * в VpnService, поэтому просто отдаём его файловый дескриптор.
     */
    override fun openTun(options: TunOptions): Int = existingTun.detachFd()

    override fun useProcFS(): Boolean = false

    override fun writeLog(message: String) { onLogLine(message) }

    // ----- методы, доступные без libbox-типов -----

    /** Владелец соединения (uid → package): Android 10+, ConnectionManager. */
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): String {
        val ctx = appContext ?: return "unknown"
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return "unknown"
        return runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
            val uid = cm.getConnectionOwnerUid(
                ipProtocol,
                java.net.InetSocketAddress(sourceAddress, sourcePort),
                java.net.InetSocketAddress(destinationAddress, destinationPort),
            )
            ctx.packageManager.getNameForUid(uid) ?: "uid:$uid"
        }.getOrDefault("unknown")
    }

    /**
     * Использовать платформенный монитор интерфейсов по умолчанию
     * (NetworkCallback) вместо внутреннего netlink-монитора sing-box.
     */
    override fun usePlatformDefaultInterfaceMonitor(): Boolean = true

    override fun underNetworkExtension(): Boolean = false

    /** Включать все сети (для системных VPN-профилей); мы — обычный VpnService. */
    override fun includeAllNetworks(): Boolean = false

    // ----- версионно-зависимые методы (возвращают libbox-типы) -----
    // Точная сигнатура сверяется с собранным libbox.aar (~1.12):
    // реф. io.nekohasekai.sfa.bg.PlatformInterfaceWrapper.

    override fun startDefaultInterfaceMonitor(listener: io.nekohasekai.libbox.DefaultInterfaceMonitorListener) {
        // TODO(libbox-версия): регистрировать ConnectivityManager.NetworkCallback
        //   и дёргать listener.onUpdate(interfaceName, index, flags)? при изменении
        //   дефолтной сети (реализация по образцу PlatformInterfaceWrapper.startDefaultInterfaceMonitor).
        onLogLine("sing-box: platform interface monitor — каркас (см. PlatformInterfaceWrapper)")
    }

    override fun closeDefaultInterfaceMonitor() {
        // TODO(libbox-версия): снимать NetworkCallback.
    }

    override fun getInterfaces(): io.nekohasekai.libbox.NetworkInterfaceIterator? {
        // TODO(libbox-версия): перечислить java.net.NetworkInterface.getNetworkInterfaces()
        //   в libbox.NetworkInterfaceIterator (реф. PlatformInterfaceWrapper.getInterfaces).
        return null
    }

    override fun readWIFIState(): io.nekohasekai.libbox.WIFIState? = null

    override fun systemCertificates(): io.nekohasekai.libbox.CertificateIterator? {
        // TODO(libbox-версия): перечислить системное хранилище CA
        //   (реф. PlatformInterfaceWrapper.systemCertificates).
        return null
    }
}
