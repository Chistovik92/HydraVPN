package ru.gidravpn.hydra.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import ru.gidravpn.hydra.MainActivity
import ru.gidravpn.hydra.R
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.vpn.core.ConnectionState
import ru.gidravpn.hydra.vpn.core.CoreFactoryProvider
import ru.gidravpn.hydra.vpn.core.VpnCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Основной туннель для всех движков (sing-box / Xray / amneziawg-go /
 * userspace-PPP: SSTP, L2TP). Поднимает tun-интерфейс и передаёт его
 * дескриптор в ядро, выбранное по протоколу профиля.
 *
 * Транспортные сокеты userspace-ядер (TLS для SSTP, UDP для L2TP)
 * выводятся из-под VPN-маршрутизации через [SocketGuard].
 */
class HydraVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var core: VpnCore? = null
    // Сырой fd tun-интерфейса, снятый ДО detachFd() (см. releaseTun()).
    private var tunFd: Int = -1
    private var connectJob: Job? = null
    // Держит уведомление в актуальном состоянии по мере роста трафика.
    private var notifJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val ACTION_CONNECT = "ru.gidravpn.hydra.CONNECT"
        const val ACTION_DISCONNECT = "ru.gidravpn.hydra.DISCONNECT"
        const val EXTRA_SERVER_ID = "server_id"
        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIF_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        SocketGuard.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> { stopTunnel(); return START_NOT_STICKY }
            else -> {
                val serverId = intent?.getLongExtra(EXTRA_SERVER_ID, -1) ?: -1
                startForeground(NOTIF_ID, buildNotification(ConnectionState.CONNECTING, "…"))
                connect(serverId)
            }
        }
        return START_STICKY
    }

    private fun connect(serverId: Long) {
        connectJob?.cancel()
        connectJob = scope.launch { doConnect(serverId) }
    }

    private suspend fun doConnect(serverId: Long) {
        // Переключение на другой сервер при уже активном соединении вызывает
        // connect() повторно — без явного гашения предыдущих core/tun они
        // просто перезаписываются полями ниже и утекают активными в фоне
        // (соединение остаётся поднятым, невидимым для UI/disconnect).
        releaseTun()

        // fd/ядро держим в локальных переменных, пока попытка не подтверждена
        // активной, — иначе гонка с параллельным stopTunnel()/новым connect()
        // (оба просто отдельные scope.launch{} на Dispatchers.IO) может
        // записать их в поля класса уже ПОСЛЕ того, как отключение решило,
        // что core/tun пусты, и просто ничего не остановило. cancel() при этом
        // не прерывает уже идущий синхронный vpnCore.start() — отмена
        // проверяется явно через ensureActive() сразу после него.
        var newTun: ParcelFileDescriptor? = null
        var newTunFd = -1
        var newCore: VpnCore? = null
        try {
            VpnState.state.value = ConnectionState.CONNECTING
            val profile = ru.gidravpn.hydra.data.repository.ServerRepository(applicationContext).byId(serverId)
                ?: throw IllegalStateException("Сервер #$serverId не найден")

            VpnState.activeServer.value = profile
            VpnState.log("Подключение к ${profile.address}…")

            newTun = establishTun(profile)
            newTunFd = newTun.fd // снимаем ДО openTun()/detachFd() внутри vpnCore.start()
            val vpnCore = CoreFactoryProvider.factory.create(profile)
            newCore = vpnCore
            VpnState.log("Ядро: ${vpnCore.name}")

            vpnCore.start(
                tun = newTun,
                profile = profile,
                onLog = { VpnState.log(it) },
                onStats = { VpnState.stats.value = it },
            )

            currentCoroutineContext().ensureActive()

            tun = newTun
            tunFd = newTunFd
            core = newCore
            VpnState.connectedSince.value = System.currentTimeMillis()
            VpnState.state.value = ConnectionState.CONNECTED
            VpnState.log("✓ Соединение установлено")
            updateNotification(ConnectionState.CONNECTED, profile.name)

            // StateFlow не эмитит одинаковые значения, так что при нулевом
            // трафике уведомление не перерисовывается впустую.
            notifJob?.cancel()
            notifJob = scope.launch {
                VpnState.stats.collect { updateNotification(ConnectionState.CONNECTED, profile.name) }
            }
        } catch (t: Throwable) {
            runCatching { newCore?.stop() }
            runCatching { newTun?.close() }
            if (newTunFd >= 0) runCatching { ParcelFileDescriptor.adoptFd(newTunFd).close() }
            if (t is CancellationException) throw t
            VpnState.log("Ошибка: ${t.message}")
            VpnState.state.value = ConnectionState.ERROR
            stopTunnel()
        }
    }

    /**
     * Гасит текущее ядро/tun. sing-box (openTun -> detachFd()) забирает
     * реальный fd себе — после этого ParcelFileDescriptor.close() с нашей
     * стороны становится no-op, а core.stop() у libbox не всегда надёжно/
     * синхронно закрывает fd на своей стороне: воспроизведено на реальном
     * устройстве — VPN-сеть системы (dumpsys connectivity) оставалась
     * CONNECTED ещё десятки секунд после штатного отключения в UI, пока не
     * убивался процесс целиком (что закрывает вообще все fd процесса).
     * Подстраховываемся, закрывая сырой fd явно через adoptFd(...).close().
     */
    private fun releaseTun() {
        notifJob?.cancel()
        notifJob = null
        runCatching { core?.stop() }
        core = null
        runCatching { tun?.close() }
        tun = null
        if (tunFd >= 0) {
            runCatching { ParcelFileDescriptor.adoptFd(tunFd).close() }
            tunFd = -1
        }
    }

    private suspend fun establishTun(profile: ServerProfile): ParcelFileDescriptor {
        // PPP-движки (SSTP/L2TP): MRU 1400, иначе фрагментация на TLS/UDP-транспорте
        val userspace = profile.protocol?.engine == ru.gidravpn.hydra.data.model.Engine.USERSPACE
        val builder = Builder()
            .setSession("Hydra")
            .setMtu(if (userspace) 1400 else 9000)
            .addAddress("172.19.0.1", 28)
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)

        // Раздельное туннелирование (DataStore → правила VpnService.Builder)
        val split = ru.gidravpn.hydra.data.repository.SplitTunnelRepository(applicationContext)
            .settings.firstOrNull() ?: ru.gidravpn.hydra.data.model.SplitTunnel()
        when (split.mode) {
            ru.gidravpn.hydra.data.model.SplitTunnelMode.INCLUDE ->
                split.packages.forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) } }
            ru.gidravpn.hydra.data.model.SplitTunnelMode.EXCLUDE -> {
                split.packages.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
                runCatching { builder.addDisallowedApplication(packageName) } // собственный трафик — мимо VPN
            }
            ru.gidravpn.hydra.data.model.SplitTunnelMode.OFF ->
                runCatching { builder.addDisallowedApplication(packageName) } // не заворачиваем собственный трафик
        }

        return builder.establish() ?: error("Не удалось поднять tun (нет разрешения VPN?)")
    }

    private fun stopTunnel() {
        connectJob?.cancel()
        connectJob = null
        scope.launch {
            releaseTun()
            VpnState.state.value = ConnectionState.DISCONNECTED
            VpnState.activeServer.value = null
            VpnState.connectedSince.value = 0L
            VpnState.log("Соединение разорвано")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onRevoke() { stopTunnel() }
    override fun onDestroy() { SocketGuard.detach(); scope.cancel(); super.onDestroy() }

    // ----- notification -----
    private fun buildNotification(state: ConnectionState, server: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.vpn_notification_channel),
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val title = when (state) {
            ConnectionState.CONNECTED -> "Туннель зашифрован"
            ConnectionState.CONNECTING -> "Подключение…"
            ConnectionState.ERROR -> "Ошибка подключения"
            else -> "Отключено"
        }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setShowWhen(false)

        if (state == ConnectionState.CONNECTED) {
            val s = VpnState.stats.value
            // Строка трафика имеет смысл только когда счётчики ненулевые;
            // иначе показываем один сервер, чтобы не рисовать «0 B / 0 B».
            val traffic = if (s.downBytes > 0 || s.upBytes > 0) {
                "  ·  ↓ ${humanBytes(s.downBytes)}  ↑ ${humanBytes(s.upBytes)}"
            } else ""
            b.setContentText("$server$traffic")

            val disconnect = PendingIntent.getService(
                this, 1,
                Intent(this, HydraVpnService::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE
            )
            val servers = PendingIntent.getActivity(
                this, 2,
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_SERVERS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(0, "Отключить", disconnect)
            b.addAction(0, "Серверы", servers)
        } else {
            b.setContentText(server)
        }
        return b.build()
    }

    private fun updateNotification(state: ConnectionState, server: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(state, server))
    }

    /** Байты в человекочитаемый вид: «0,0 MB» для килобайт выглядело как поломка. */
    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f ГБ".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f МБ".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.0f КБ".format(bytes / 1_000.0)
        else -> "$bytes Б"
    }
}
