package ru.gidravpn.hydra.data.model

import android.content.Context
import ru.gidravpn.hydra.R

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
enum class NetRuleType(val singBoxKey: String, @androidx.annotation.StringRes val labelRes: Int) {
    IP_CIDR("ip_cidr", R.string.rule_ip_cidr),
    DOMAIN("domain", R.string.rule_domain),
    DOMAIN_SUFFIX("domain_suffix", R.string.rule_domain_suffix),
    DOMAIN_KEYWORD("domain_keyword", R.string.rule_domain_keyword),
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
    fun summary(ctx: Context): String = when (mode) {
        SplitTunnelMode.OFF -> ctx.getString(R.string.split_sum_off)
        SplitTunnelMode.INCLUDE -> ctx.getString(R.string.split_sum_include, packages.size)
        SplitTunnelMode.EXCLUDE -> ctx.getString(R.string.split_sum_exclude, packages.size)
    }

    /** Краткое описание для UI (раздел «По IP/доменам»). */
    fun netSummary(ctx: Context): String = when (netMode) {
        SplitTunnelMode.OFF -> ctx.getString(R.string.split_net_sum_off)
        SplitTunnelMode.INCLUDE -> ctx.getString(R.string.split_net_sum_include, netRules.size)
        SplitTunnelMode.EXCLUDE -> ctx.getString(R.string.split_net_sum_exclude, netRules.size)
    }
}
