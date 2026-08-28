package ru.gidravpn.hydra.vpn.core

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder
import java.io.File

/**
 * Интеграция sing-box через libbox.aar (gomobile-биндинг experimental/libbox).
 *
 * Собрано и проверено против **libbox 1.12.9** (сигнатуры PlatformInterface
 * сверены с фактическим .aar). При обновлении .aar компилятор укажет
 * расхождения — реф. SagerNet/sing-box-for-android → PlatformInterfaceWrapper.
 */
class SingBoxCore : VpnCore {

    override val name = "sing-box ${Libbox.version()}"
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

        val platform = HydraPlatformInterface(existingTun = tun, onLogLine = onLog)
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
 * Реализация PlatformInterface (libbox 1.12.x) поверх VpnService:
 *  - tun поднят сервисом заранее, openTun отдаёт его fd;
 *  - монитор дефолтного интерфейса — ConnectivityManager.NetworkCallback;
 *  - сокеты sing-box не требуют protect: собственное приложение исключено
 *    из VPN правилами establishTun (addDisallowedApplication).
 */
class HydraPlatformInterface(
    private val existingTun: ParcelFileDescriptor,
    private val onLogLine: (String) -> Unit,
) : PlatformInterface {

    private val appContext get() = ru.gidravpn.hydra.AppCtx.appContext
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** libbox запрашивает tun через openTun — отдаём дескриптор нашего VpnService. */
    override fun openTun(options: TunOptions): Int = existingTun.detachFd()

    override fun useProcFS(): Boolean = false

    /** autoDetectInterfaceControl не нужен: наше приложение исключено из VPN. */
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = false

    override fun autoDetectInterfaceControl(fd: Int) {
        // no-op (используется только при usePlatformAutoDetectInterfaceControl = true)
    }

    override fun writeLog(message: String) {
        onLogLine(message)
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    /** UID владельца соединения (Android 10+); 0 = неизвестно. */
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): Int {
        val ctx = appContext ?: return 0
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return 0
        return runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return 0
            cm.getConnectionOwnerUid(
                ipProtocol,
                java.net.InetSocketAddress(sourceAddress, sourcePort),
                java.net.InetSocketAddress(destinationAddress, destinationPort),
            )
        }.getOrDefault(0)
    }

    override fun packageNameByUid(uid: Int): String {
        val ctx = appContext ?: return "unknown"
        return runCatching {
            ctx.packageManager.getNameForUid(uid) ?: "uid:$uid"
        }.getOrDefault("unknown")
    }

    override fun uidByPackageName(packageName: String): Int {
        val ctx = appContext ?: return -1
        return runCatching {
            ctx.packageManager.getApplicationInfo(packageName, 0).uid
        }.getOrDefault(-1)
    }

    /** Монитор дефолтного интерфейса через системный NetworkCallback. */
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val ctx = appContext ?: return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notify(network, listener)
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) =
                notify(network, listener)
        }
        networkCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onFailure { onLogLine("sing-box: monitor интерфейса: ${it.message}") }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val ctx = appContext ?: return
        networkCallback?.let { cb ->
            runCatching {
                ctx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            }
            networkCallback = null
        }
    }

    private fun notify(network: Network, listener: InterfaceUpdateListener) {
        runCatching {
            val ctx = appContext ?: return
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
            val lp = cm.getLinkProperties(network) ?: return
            val caps = cm.getNetworkCapabilities(network)
            val name = lp.interfaceName ?: return
            val index = java.net.NetworkInterface.getByName(name)?.index ?: 0
            val isMetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
            listener.updateDefaultInterface(name, index, isMetered, false)
        }
    }

    /**
     * Перечисление системных сетевых интерфейсов для libbox.
     *
     * Вызывается синхронно из Go-кода sing-box через JNI-мост gomobile —
     * необработанное исключение здесь всплывает через границу JNI и валит
     * весь процесс (а не просто соединение), поэтому вся логика — под
     * runCatching, как и в остальных методах этого класса.
     */
    override fun getInterfaces(): NetworkInterfaceIterator {
        val items = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { ni ->
                NetworkInterface().apply {
                    name = ni.name ?: ""
                    index = ni.index
                    mtu = ni.mtu
                    addresses = StringIteratorImpl(
                        runCatching {
                            ni.interfaceAddresses.mapNotNull { it.address?.hostAddress }.toList()
                        }.getOrDefault(emptyList())
                    )
                }
            }
        }.getOrDefault(emptyList())
        return object : NetworkInterfaceIterator {
            private var pos = 0
            override fun hasNext() = pos < items.size
            override fun next(): NetworkInterface = items[pos++]
        }
    }

    /** Состояние Wi-Fi для маршрутизации policy-based (не используется). */
    override fun readWIFIState(): WIFIState? = null

    /**
     * Системные CA-сертификаты (/system/etc/security/cacerts, как в SFA).
     * `listFiles()` может кинуть SecurityException на прошивках со своей
     * SELinux-политикой доступа к /system — не должно ронять JNI-вызов.
     */
    override fun systemCertificates(): StringIterator {
        val names = runCatching {
            File("/system/etc/security/cacerts").listFiles().orEmpty()
                .map { it.name }.sorted()
        }.getOrDefault(emptyList())
        return StringIteratorImpl(names)
    }

    /** Локальный DNS-транспорт платформы: null → sing-box использует свои серверы. */
    override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport? = null

    /** Уведомления ядра (например, об обновлении geodata) — в лог. */
    override fun sendNotification(notification: Notification) {
        onLogLine("sing-box: [${notification.typeName}] ${notification.title}")
    }

    /** Очистка кэша DNS — системный DNS-резолвер пересоздаётся при следующем запросе. */
    override fun clearDNSCache() {}
}

/** java.util.List<String> → libbox StringIterator. */
internal class StringIteratorImpl(private val items: List<String>) : StringIterator {
    private var pos = 0
    override fun hasNext() = pos < items.size
    override fun next(): String = items[pos++]
    override fun len(): Int = items.size
}
