package ru.gidravpn.hydra.data.model

enum class SplitTunnelMode { OFF, INCLUDE, EXCLUDE }

enum class NetRuleType(val singBoxKey: String) {
    IP_CIDR("ip_cidr"), DOMAIN("domain"), DOMAIN_SUFFIX("domain_suffix"), DOMAIN_KEYWORD("domain_keyword"),
}

data class NetworkRule(val type: NetRuleType, val value: String)

data class SplitTunnel(
    val mode: SplitTunnelMode = SplitTunnelMode.OFF,
    val packages: Set<String> = emptySet(),
    val netMode: SplitTunnelMode = SplitTunnelMode.OFF,
    val netRules: List<NetworkRule> = emptyList(),
) {
    val isActive get() = mode != SplitTunnelMode.OFF && packages.isNotEmpty()
    val netActive get() = netMode != SplitTunnelMode.OFF && netRules.isNotEmpty()
}
