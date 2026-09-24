package ru.gidravpn.hydra.vpn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import ru.gidravpn.hydra.MainActivity
import ru.gidravpn.hydra.R
import ru.gidravpn.hydra.data.model.ServerLocation
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.repository.ServerRepository
import ru.gidravpn.hydra.data.repository.VpnSettingsRepository
import ru.gidravpn.hydra.vpn.core.ConnectionState

/**
 * Виджет на рабочем столе (Фаза 8): локация и статус (тап — открыть приложение) и кнопка
 * «Подключить / Отключить».
 *
 * Кнопка — прямой PendingIntent на сервис, а не broadcast в этот провайдер: старт
 * foreground-сервиса из фона Android 12+ разрешает именно по нажатию на виджет, а из
 * BroadcastReceiver — нет. Поэтому намерение кнопки пересобирается при каждой смене
 * состояния ([update] зовётся из HydraApp). Нет VPN-согласия — кнопка открывает приложение:
 * системный диалог согласия умеет показать только Activity.
 */
class HydraWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                update(context, VpnState.state.value, VpnState.activeServer.value ?: lastServer(context))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Последний выбранный сервер — его виджет показывает и подключает, пока туннель опущен. */
        suspend fun lastServer(ctx: Context): ServerProfile? = runCatching {
            val id = VpnSettingsRepository(ctx).lastServerId.firstOrNull() ?: return null
            ServerRepository(ctx).byId(id)
        }.getOrNull()

        /** Перерисовать все экземпляры виджета. Без установленных виджетов — ничего не делает. */
        fun update(ctx: Context, state: ConnectionState, server: ServerProfile?) {
            val manager = AppWidgetManager.getInstance(ctx) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(ctx, HydraWidget::class.java)) }
                .getOrNull() ?: return
            if (ids.isEmpty()) return

            val up = state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING ||
                state == ConnectionState.RECONNECTING
            val status = ctx.getString(
                when (state) {
                    ConnectionState.CONNECTED -> R.string.connected
                    ConnectionState.CONNECTING -> R.string.connecting
                    ConnectionState.RECONNECTING -> R.string.notif_reconnecting
                    ConnectionState.ERROR -> R.string.tile_blocked
                    ConnectionState.DISCONNECTED -> R.string.disconnected
                }
            )
            val location = server?.let { ServerLocation.label(it) } ?: ctx.getString(R.string.widget_no_server)

            val openApp = PendingIntent.getActivity(
                ctx, 10, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val toggle = when {
                up -> PendingIntent.getService(
                    ctx, 11,
                    Intent(ctx, HydraVpnService::class.java).setAction(HydraVpnService.ACTION_DISCONNECT),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                server == null || VpnService.prepare(ctx) != null -> openApp
                else -> PendingIntent.getForegroundService(
                    ctx, 12,
                    Intent(ctx, HydraVpnService::class.java).setAction(HydraVpnService.ACTION_CONNECT)
                        .putExtra(HydraVpnService.EXTRA_SERVER_ID, server.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }

            val views = RemoteViews(ctx.packageName, R.layout.widget_vpn).apply {
                setTextViewText(R.id.widget_location, location)
                setTextViewText(R.id.widget_status, status)
                setTextColor(R.id.widget_status, if (up) 0xFF00E599.toInt() else 0xFF8A94A6.toInt())
                setTextViewText(R.id.widget_toggle, ctx.getString(if (up) R.string.widget_disconnect else R.string.widget_connect))
                setInt(R.id.widget_toggle, "setBackgroundResource",
                    if (up) R.drawable.widget_btn_off else R.drawable.widget_btn_on)
                setOnClickPendingIntent(R.id.widget_body, openApp)
                setOnClickPendingIntent(R.id.widget_toggle, toggle)
                setContentDescription(R.id.widget_body, "Hydra VPN, $location, $status")
            }
            runCatching { manager.updateAppWidget(ids, views) }
        }
    }
}
