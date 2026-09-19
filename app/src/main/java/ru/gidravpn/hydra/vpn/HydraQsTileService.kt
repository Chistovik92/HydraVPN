package ru.gidravpn.hydra.vpn

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import ru.gidravpn.hydra.MainActivity
import ru.gidravpn.hydra.R
import ru.gidravpn.hydra.data.repository.ServerRepository
import ru.gidravpn.hydra.data.repository.VpnSettingsRepository
import ru.gidravpn.hydra.vpn.core.ConnectionState

/**
 * Плитка «Hydra VPN» в шторке быстрых настроек (Фаза 6b). Подключает/отключает
 * последний использованный сервер (VpnSettingsRepository.lastServerId) одним
 * тапом. TileService не может сам показать системный VPN-consent диалог —
 * если согласие ещё не выдавалось, тап просто открывает MainActivity.
 */
class HydraQsTileService : TileService() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(ru.gidravpn.hydra.LocaleHelper.wrap(base))
    }

    // Main: qsTile/updateTile() — API TileService, трогаем с главного потока.
    // Room и DataStore main-safe, так что onClick тоже живёт здесь.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listenJob: Job? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        listenJob?.cancel()
        listenJob = scope.launch { VpnState.state.collect { updateTile(it) } }
    }

    override fun onStopListening() {
        listenJob?.cancel()
        listenJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val current = VpnState.state.value
        if (current == ConnectionState.CONNECTED || current == ConnectionState.CONNECTING || current == ConnectionState.RECONNECTING) {
            runCatching {
                startService(Intent(this, HydraVpnService::class.java).setAction(HydraVpnService.ACTION_DISCONNECT))
            }
            return
        }
        if (VpnService.prepare(this) != null) {
            openApp() // согласие не выдано — диалог может показать только Activity
            return
        }
        unlockAndRun {
            scope.launch {
                val ctx = applicationContext
                val id = VpnSettingsRepository(ctx).lastServerId.firstOrNull()
                val servers = ServerRepository(ctx)
                val server = id?.let { servers.byId(it) } ?: servers.allServers.firstOrNull()?.firstOrNull()
                if (server == null) { openApp(); return@launch }
                val intent = Intent(ctx, HydraVpnService::class.java).apply {
                    action = HydraVpnService.ACTION_CONNECT
                    putExtra(HydraVpnService.EXTRA_SERVER_ID, server.id)
                }
                // См. BootReceiver: старт foreground-сервиса может быть отклонён
                // системой, и это не повод ронять процесс плитки.
                runCatching { ContextCompat.startForegroundService(ctx, intent) }
                    .onFailure {
                        VpnState.log("Ошибка: подключение из плитки не стартовало — ${it.javaClass.simpleName}")
                        openApp()
                    }
            }
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // startActivityAndCollapse(Intent) выбрасывает UnsupportedOperationException
            // начиная с Android 14 — обязателен вариант с PendingIntent.
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(state: ConnectionState) {
        val t = qsTile ?: return
        t.state = if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING) {
            Tile.STATE_ACTIVE
        } else {
            Tile.STATE_INACTIVE
        }
        t.icon = Icon.createWithResource(this, R.drawable.ic_tile_vpn)
        t.label = "Hydra VPN"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            t.subtitle = when (state) {
                ConnectionState.CONNECTED -> VpnState.activeServer.value?.name ?: getString(R.string.connected)
                ConnectionState.CONNECTING -> getString(R.string.connecting)
                ConnectionState.RECONNECTING -> getString(R.string.notif_reconnecting)
                ConnectionState.ERROR -> getString(R.string.tile_blocked)
                ConnectionState.DISCONNECTED -> getString(R.string.disconnected)
            }
        }
        t.updateTile()
    }
}
