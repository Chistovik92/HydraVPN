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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private var connectJob: Job? = null
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
        runCatching { core?.stop() }
        core = null
        runCatching { tun?.close() }
        tun = null
        try {
            VpnState.state.value = ConnectionState.CONNECTING
            val profile = ru.gidravpn.hydra.data.repository.ServerRepository(applicationContext).byId(serverId)
                ?: throw IllegalStateException("Сервер #$serverId не найден")

            VpnState.activeServer.value = profile
            VpnState.log("Подключение к ${profile.address}…")

            val fd = establishTun(profile)
            tun = fd

            val vpnCore = CoreFactoryProvider.factory.create(profile)
            core = vpnCore
            VpnState.log("Ядро: ${vpnCore.name}")

            vpnCore.start(
                tun = fd,
                profile = profile,
                onLog = { VpnState.log(it) },
                onStats = { VpnState.stats.value = it },
            )

            VpnState.connectedSince.value = System.currentTimeMillis()
            VpnState.state.value = ConnectionState.CONNECTED
            VpnState.log("✓ Соединение установлено")
            updateNotification(ConnectionState.CONNECTED, profile.name)
        } catch (t: Throwable) {
            VpnState.log("Ошибка: ${t.message}")
            VpnState.state.value = ConnectionState.ERROR
            stopTunnel()
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
            runCatching { core?.stop() }
            core = null
            runCatching { tun?.close() }
            tun = null
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
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = when (state) {
            ConnectionState.CONNECTED -> "Подключено • $server"
            ConnectionState.CONNECTING -> "Подключение…"
            else -> "Отключено"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hydra")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(state: ConnectionState, server: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(state, server))
    }
}
