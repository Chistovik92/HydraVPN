package com.shadowlink.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.shadowlink.MainActivity
import com.shadowlink.R
import com.shadowlink.data.model.ServerProfile
import com.shadowlink.vpn.core.ConnectionState
import com.shadowlink.vpn.core.CoreFactoryProvider
import com.shadowlink.vpn.core.VpnCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Основной туннель для proxy-ядер (sing-box / Xray).
 * Поднимает tun-интерфейс и передаёт его дескриптор в выбранное ядро.
 */
class ShadowLinkVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var core: VpnCore? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val ACTION_CONNECT = "com.shadowlink.CONNECT"
        const val ACTION_DISCONNECT = "com.shadowlink.DISCONNECT"
        const val EXTRA_SERVER_ID = "server_id"
        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIF_ID = 1
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

    private fun connect(serverId: Long) = scope.launch {
        try {
            VpnState.state.value = ConnectionState.CONNECTING
            val profile = com.shadowlink.data.repository.ServerRepository(applicationContext).byId(serverId)
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

    private fun establishTun(profile: ServerProfile): ParcelFileDescriptor {
        val builder = Builder()
            .setSession("ShadowLink")
            .setMtu(9000)
            .addAddress("172.19.0.1", 28)
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
        // не заворачиваем собственный трафик приложения
        runCatching { builder.addDisallowedApplication(packageName) }
        return builder.establish() ?: error("Не удалось поднять tun (нет разрешения VPN?)")
    }

    private fun stopTunnel() {
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
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

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
            .setContentTitle("ShadowLink")
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
