package ru.gidravpn.hydra.vpn

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ru.gidravpn.hydra.MainActivity
import ru.gidravpn.hydra.R
import ru.gidravpn.hydra.data.model.ServerLocation
import ru.gidravpn.hydra.vpn.core.ConnectionState

/**
 * Плитка «Hydra VPN» в шторке быстрых настроек.
 *
 * С 0.6.23 тап открывает приложение (раньше — подключал/отключал последний сервер), а сама
 * плитка показывает, какая локация сейчас поднята: «🇩🇪 Германия · Frankfurt-1» крупной
 * строкой, статус — подписью. Подключение и отключение — в приложении, в уведомлении и
 * в виджете на рабочем столе.
 */
class HydraQsTileService : TileService() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(ru.gidravpn.hydra.LocaleHelper.wrap(base))
    }

    // Main: qsTile/updateTile() — API TileService, трогаем с главного потока.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listenJob: Job? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        listenJob?.cancel()
        listenJob = scope.launch {
            combine(VpnState.state, VpnState.activeServer) { s, server -> s to server }
                .collect { (s, server) -> updateTile(s, server) }
        }
    }

    override fun onStopListening() {
        listenJob?.cancel()
        listenJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        openApp()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // startActivityAndCollapse(Intent) выбрасывает UnsupportedOperationException
                // начиная с Android 14 — обязателен вариант с PendingIntent.
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }.onFailure { VpnState.log("Ошибка: плитка не открыла приложение — ${it.javaClass.simpleName}") }
    }

    private fun updateTile(state: ConnectionState, server: ru.gidravpn.hydra.data.model.ServerProfile?) {
        val t = qsTile ?: return
        val up = state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING ||
            state == ConnectionState.RECONNECTING
        t.state = if (up) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.icon = Icon.createWithResource(this, R.drawable.ic_tile_vpn)
        val status = when (state) {
            ConnectionState.CONNECTED -> getString(R.string.connected)
            ConnectionState.CONNECTING -> getString(R.string.connecting)
            ConnectionState.RECONNECTING -> getString(R.string.notif_reconnecting)
            ConnectionState.ERROR -> getString(R.string.tile_blocked)
            ConnectionState.DISCONNECTED -> getString(R.string.disconnected)
        }
        // Крупная строка — локация, пока туннель поднят (или поднимается); иначе имя приложения.
        val location = server?.takeIf { up }?.let { ServerLocation.label(it) }
        t.label = location ?: "Hydra VPN"
        // Подпись есть с Android 10; раньше видна только локация.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) t.subtitle = status
        t.contentDescription = listOfNotNull("Hydra VPN", location, status).joinToString(", ")
        t.updateTile()
    }
}
