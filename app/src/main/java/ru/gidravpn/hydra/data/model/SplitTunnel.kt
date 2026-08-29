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

/**
 * Тип правила маршрутизации по IP/домену — соответствует ключам
 * route.rules в конфиге sing-box (см. SingBoxConfigBuilder).
 */
enum class NetRuleType(val singBoxKey: String, val label: String) {
    IP_CIDR("ip_cidr", "IP/CIDR"),
    DOMAIN("domain", "Домен"),
    DOMAIN_SUFFIX("domain_suffix", "Поддомены"),
    DOMAIN_KEYWORD("domain_keyword", "Ключевое слово"),
}

data class NetworkRule(val type: NetRuleType, val value: String)

data class SplitTunnel(
    val mode: SplitTunnelMode = SplitTunnelMode.OFF,
    val packages: Set<String> = emptySet(),
    // Второй, независимый тип split tunneling — по IP/доменам (Фаза 2).
    // Работает только при подключении через sing-box-протоколы (см. SingBoxConfigBuilder).
    val netMode: SplitTunnelMode = SplitTunnelMode.OFF,
    val netRules: List<NetworkRule> = emptyList(),
) {
    val isActive get() = mode != SplitTunnelMode.OFF && packages.isNotEmpty()
    val netActive get() = netMode != SplitTunnelMode.OFF && netRules.isNotEmpty()

    /** Краткое описание для UI (раздел «По приложениям»). */
    val summary: String
        get() = when (mode) {
            SplitTunnelMode.OFF -> "Весь трафик через VPN"
            SplitTunnelMode.INCLUDE -> "Через VPN: ${packages.size} прил. (только выбранные)"
            SplitTunnelMode.EXCLUDE -> "Мимо VPN: ${packages.size} прил."
        }

    /** Краткое описание для UI (раздел «По IP/доменам»). */
    val netSummary: String
        get() = when (netMode) {
            SplitTunnelMode.OFF -> "IP/домены маршрутизируются как обычно"
            SplitTunnelMode.INCLUDE -> "Через VPN только: ${netRules.size} правил(о)"
            SplitTunnelMode.EXCLUDE -> "Мимо VPN: ${netRules.size} правил(о)"
        }
}
