package ru.gidravpn.hydra.vpn.core

import android.os.ParcelFileDescriptor
import ru.gidravpn.hydra.data.model.ServerProfile

/**
 * PPTP: честный отказ.
 *
 * Причина (см. docs/PROTOCOLS.md): полезные данные PPTP идут в GRE
 * (IP-протокол 47) — без raw-сокетов (root) на Android нерализуемо;
 * системный стек PPTP удалён из Android 12/13. Ядро существует в
 * классификации протоколов, чтобы UI сообщал пользователю правду
 * вместо молчаливого отказа. Используйте SSTP/L2TP/WireGuard.
 */
class PptpCore : VpnCore {

    override val name = "PPTP (недоступно)"

    override fun start(
        tun: ParcelFileDescriptor,
        profile: ServerProfile,
        onLog: (String) -> Unit,
        onStats: (TrafficStats) -> Unit,
    ) {
        onLog("PPTP: данные переносятся в GRE (IP-протокол 47) — требует raw-сокетов/root;")
        onLog("PPTP: системный стек удалён из Android 12/13. Протокол недоступен.")
        throw UnsupportedOperationException(
            "PPTP недоступен на Android: GRE требует root, стек удалён из Android 12/13. " +
                    "Используйте SSTP, L2TP или WireGuard."
        )
    }

    override fun stop() {}
}
