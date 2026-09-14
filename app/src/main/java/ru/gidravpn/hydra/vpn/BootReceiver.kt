package ru.gidravpn.hydra.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import ru.gidravpn.hydra.data.repository.VpnSettingsRepository

/**
 * Автоподключение при загрузке устройства (Фаза 6b). Никакой Activity к этому
 * моменту нет, поэтому системное VPN-согласие должно быть выдано ЗАРАНЕЕ (в
 * прошлом запуске приложения) — VpnService.prepare() и после reboot помнит
 * его per-app, повторного диалога не требует. Если согласия нет — тихо
 * пропускаем: показать consent-диалог из BroadcastReceiver невозможно.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = VpnSettingsRepository(context.applicationContext)
                if (repo.autoConnectOnBoot.firstOrNull() != true) return@launch
                val id = repo.lastServerId.firstOrNull() ?: return@launch
                if (VpnService.prepare(context) != null) return@launch // согласие отозвано/не выдавалось

                val svcIntent = Intent(context, HydraVpnService::class.java).apply {
                    action = HydraVpnService.ACTION_CONNECT
                    putExtra(HydraVpnService.EXTRA_SERVER_ID, id)
                }
                ContextCompat.startForegroundService(context, svcIntent)
            } finally {
                pending.finish()
            }
        }
    }
}
