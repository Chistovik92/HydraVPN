package ru.gidravpn.hydra.vpn.core

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.HandlerThread
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
    private var monitorThread: HandlerThread? = null

    /** libbox запрашивает tun через openTun — отдаём дескриптор нашего VpnService. */
    override fun openTun(options: TunOptions): Int = existingTun.detachFd()

    override fun useProcFS(): Boolean = false

    /**
     * VpnService.protect(fd) на исходящие сокеты, если libbox сам его запросит.
     * На практике для VLESS/gRPC этот колбэк не вызывается ни разу (реальная
     * причина "no available network interface" была в getInterfaces() —
     * см. флаги ниже), но это официальный Android-механизм именно под эту
     * задачу, и он ничего не стоит держать реализованным на будущее/другие ядра.
     */
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        ru.gidravpn.hydra.vpn.SocketGuard.protect(fd)
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

    /**
     * Монитор дефолтного интерфейса через системный NetworkCallback.
     *
     * Колбэк регистрируется с явным Handler'ом на выделенном HandlerThread,
     * а не на внутреннем потоке ConnectivityManager по умолчанию: вызов
     * listener.updateDefaultInterface() внутри колбэка — это JNI-вызов в Go,
     * и на «чужом», не контролируемом нами потоке он воспроизводимо валил
     * процесс нативным SIGABRT (тумбстоун, поток ConnectivityThread) —
     * такой краш не перехватывается никаким try/catch/runCatching на
     * стороне Kotlin, так как это не JVM-исключение.
     */
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val ctx = appContext ?: run {
            onLogLine("sing-box: monitor интерфейса: нет AppCtx.appContext"); return
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: run {
            onLogLine("sing-box: monitor интерфейса: нет ConnectivityManager"); return
        }
        val thread = HandlerThread("Hydra-NetMonitor").apply { start() }
        monitorThread = thread
        val handler = android.os.Handler(thread.looper)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notify(network, listener)
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) =
                notify(network, listener)
        }
        networkCallback = cb
        // registerDefaultNetworkCallback() у владельца VPN воспроизводимо репортит
        // собственный tun как "дефолтную сеть" (onAvailable сразу отдаёт LinkProperties
        // с interfaceName=tun0) — sing-box получает команду биндить исходящий сокет на
        // собственный туннель и падает с "no available network interface" на 100%
        // соединений. NET_CAPABILITY_NOT_VPN в запросе делает такое совпадение
        // структурно невозможным: подходит только реальная подложенная сеть (Wi-Fi/мобильная).
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(request, cb, handler) }
            .onFailure { onLogLine("sing-box: monitor интерфейса: ошибка регистрации: ${it.message}") }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val ctx = appContext ?: return
        networkCallback?.let { cb ->
            runCatching {
                ctx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            }
            networkCallback = null
        }
        monitorThread?.quitSafely()
        monitorThread = null
    }

    private fun notify(network: Network, listener: InterfaceUpdateListener) {
        runCatching {
            val ctx = appContext ?: run { onLogLine("sing-box: notify: нет appContext"); return }
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
                ?: run { onLogLine("sing-box: notify: нет ConnectivityManager"); return }
            val lp = cm.getLinkProperties(network)
                ?: run { onLogLine("sing-box: notify: нет LinkProperties для $network"); return }
            val caps = cm.getNetworkCapabilities(network)
            val name = lp.interfaceName
                ?: run { onLogLine("sing-box: notify: нет interfaceName в $lp"); return }
            val index = java.net.NetworkInterface.getByName(name)?.index ?: 0
            val isMetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
            listener.updateDefaultInterface(name, index, isMetered, false)
        }.onFailure { onLogLine("sing-box: notify: исключение: ${it}") }
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
                    // Go net.Flags: Up=1, Broadcast=2, Loopback=4, PointToPoint=8,
                    // Multicast=16, Running=32. Поле не заполнялось вовсе (оставалось 0
                    // = "интерфейс не поднят") — вероятная причина, по которой sing-box
                    // отбрасывал абсолютно все интерфейсы как непригодные.
                    flags = runCatching {
                        var f = 0
                        if (ni.isUp) f = f or 1 or 32
                        if (ni.isLoopback) f = f or 4
                        if (ni.isPointToPoint) f = f or 8
                        if (ni.supportsMulticast()) f = f or 16
                        f
                    }.getOrDefault(0)
                    // sing-box парсит каждую строку как netip.ParsePrefix("адрес/длина_маски").
                    // Go явно отказывается парсить zone-id внутри префикса ("IPv6 zones cannot
                    // be present in a prefix") — воспроизведено на link-local IPv6 rmnet_data0
                    // вида "fe80::...%rmnet_data0" (hostAddress для scoped-адресов включает
                    // "%zone" в Java) — обрезаем zone перед добавлением "/prefix".
                    addresses = StringIteratorImpl(
                        runCatching {
                            ni.interfaceAddresses.mapNotNull { ia ->
                                val host = ia.address?.hostAddress
                                    ?.substringBefore('%')
                                    ?: return@mapNotNull null
                                "$host/${ia.networkPrefixLength}"
                            }.toList()
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
