package ru.gidravpn.hydra.data.model

/**
 * Раздельное туннелирование (split tunneling):
 *  - [OFF] — весь трафик через VPN (по умолчанию);
 *  - [INCLUDE] — через VPN только выбранные приложения;
 *  - [EXCLUDE] — через VPN всё, кроме выбранных.
 */
enum class SplitTunnelMode { OFF, INCLUDE, EXCLUDE }

/**
 * Тип правила маршрутизации по IP/домену — соответствует ключам
 * route.rules в конфиге sing-box (см. SingBoxConfigBuilder).
 */
enum class NetRuleType(val singBoxKey: String) {
    IP_CIDR("ip_cidr"),
    DOMAIN("domain"),
    DOMAIN_SUFFIX("domain_suffix"),
    DOMAIN_KEYWORD("domain_keyword"),
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
}