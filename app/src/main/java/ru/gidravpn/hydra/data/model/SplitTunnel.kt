package ru.gidravpn.hydra.data.model

/**
 * Раздельное туннелирование (split tunneling):
 *  - [OFF] — весь трафик через VPN (по умолчанию);
 *  - [INCLUDE] — через VPN только выбранные приложения;
 *  - [EXCLUDE] — через VPN всё, кроме выбранных.
 *
 * Хранится в DataStore (data/repository/SplitTunnelRepository.kt),
 * применяется в HydraVpnService.establishTun.
 */
enum class SplitTunnelMode { OFF, INCLUDE, EXCLUDE }

data class SplitTunnel(
    val mode: SplitTunnelMode = SplitTunnelMode.OFF,
    val packages: Set<String> = emptySet(),
) {
    val isActive get() = mode != SplitTunnelMode.OFF && packages.isNotEmpty()

    /** Краткое описание для UI. */
    val summary: String
        get() = when (mode) {
            SplitTunnelMode.OFF -> "Весь трафик через VPN"
            SplitTunnelMode.INCLUDE -> "Через VPN: ${packages.size} прил. (только выбранные)"
            SplitTunnelMode.EXCLUDE -> "Мимо VPN: ${packages.size} прил."
        }
}
