package ru.gidravpn.hydra.data.model

import android.content.Context
import ru.gidravpn.hydra.R

val DnsProvider.labelRes: Int?
    get() = when (this) {
        DnsProvider.HYDRA -> R.string.dns_hydra
        DnsProvider.SYSTEM -> R.string.dns_system
        DnsProvider.CUSTOM -> R.string.dns_custom
        else -> null
    }

val GeoRoutingMode.labelRes: Int
    get() = when (this) {
        GeoRoutingMode.OFF -> R.string.geo_off
        GeoRoutingMode.DIRECT -> R.string.geo_direct
        GeoRoutingMode.VIA_PROXY -> R.string.geo_via_proxy
    }

val GeoRoutingMode.descriptionRes: Int
    get() = when (this) {
        GeoRoutingMode.OFF -> R.string.geo_off_desc
        GeoRoutingMode.DIRECT -> R.string.geo_direct_desc
        GeoRoutingMode.VIA_PROXY -> R.string.geo_via_proxy_desc
    }

val MtuPreset.labelRes: Int
    get() = when (this) {
        MtuPreset.AUTO -> R.string.mtu_auto
        MtuPreset.MTU_1500 -> R.string.mtu_1500
        MtuPreset.MTU_1400 -> R.string.mtu_1400
        MtuPreset.MTU_1280 -> R.string.mtu_1280
    }

val TlsFragmentMode.labelRes: Int
    get() = when (this) {
        TlsFragmentMode.OFF -> R.string.frag_label
        TlsFragmentMode.RECORD -> R.string.frag_record
        TlsFragmentMode.TCP -> R.string.frag_tcp
    }

val TlsFragmentMode.descriptionRes: Int
    get() = when (this) {
        TlsFragmentMode.OFF -> R.string.frag_off_desc
        TlsFragmentMode.RECORD -> R.string.frag_record_desc
        TlsFragmentMode.TCP -> R.string.frag_tcp_desc
    }

val Ipv6Mode.labelRes: Int
    get() = when (this) {
        Ipv6Mode.BLOCK -> R.string.ipv6_block
        Ipv6Mode.ENABLE -> R.string.ipv6_enable
    }

val Ipv6Mode.descriptionRes: Int
    get() = when (this) {
        Ipv6Mode.BLOCK -> R.string.ipv6_block_desc
        Ipv6Mode.ENABLE -> R.string.ipv6_enable_desc
    }

val NetRuleType.labelRes: Int
    get() = when (this) {
        NetRuleType.IP_CIDR -> R.string.rule_ip_cidr
        NetRuleType.DOMAIN -> R.string.rule_domain
        NetRuleType.DOMAIN_SUFFIX -> R.string.rule_domain_suffix
        NetRuleType.DOMAIN_KEYWORD -> R.string.rule_domain_keyword
    }

fun SplitTunnel.summary(ctx: Context): String = when (mode) {
    SplitTunnelMode.OFF -> ctx.getString(R.string.split_sum_off)
    SplitTunnelMode.INCLUDE -> ctx.getString(R.string.split_sum_include, packages.size)
    SplitTunnelMode.EXCLUDE -> ctx.getString(R.string.split_sum_exclude, packages.size)
}

fun SplitTunnel.netSummary(ctx: Context): String = when (netMode) {
    SplitTunnelMode.OFF -> ctx.getString(R.string.split_net_sum_off)
    SplitTunnelMode.INCLUDE -> ctx.getString(R.string.split_net_sum_include, netRules.size)
    SplitTunnelMode.EXCLUDE -> ctx.getString(R.string.split_net_sum_exclude, netRules.size)
}

