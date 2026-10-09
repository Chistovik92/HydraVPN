package ru.gidravpn.hydra.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import ru.gidravpn.hydra.data.model.DnsEndpoint
import ru.gidravpn.hydra.data.geo.GeoSettings
import ru.gidravpn.hydra.data.routing.RouteConfig
import ru.gidravpn.hydra.data.model.DnsProvider
import ru.gidravpn.hydra.data.model.GeoRoutingMode
import ru.gidravpn.hydra.data.model.Ipv6Mode
import ru.gidravpn.hydra.data.model.MtuPreset
import ru.gidravpn.hydra.data.model.TlsFragmentMode

/** Настройки Фазы 6c: DNS-резолвер и geoip/geosite-маршрутизация (SingBoxConfigBuilder). */
class RoutingRepository(private val context: Context) {

    private val KEY_DNS_PROVIDER = stringPreferencesKey("dns_provider")
    private val KEY_DNS_CUSTOM = stringPreferencesKey("dns_custom_address")
    private val KEY_GEO_MODE = stringPreferencesKey("geo_routing_mode")
    private val KEY_GEO_COUNTRIES = stringPreferencesKey("geo_countries")
    private val KEY_MTU = stringPreferencesKey("tun_mtu")
    private val KEY_TLS_FRAGMENT = stringPreferencesKey("tls_fragment")
    private val KEY_IPV6 = stringPreferencesKey("ipv6_mode")
    private val KEY_ROUTE_CONFIG = stringPreferencesKey("route_config")
    private val KEY_GEO_CONFIG = stringPreferencesKey("geo_config")

    /** Источники и расписание geo-баз (0.7.13). */
    val geoSettings: Flow<GeoSettings> = context.routingStore.data.map { GeoSettings.fromJson(it[KEY_GEO_CONFIG]) }
    suspend fun setGeoSettings(s: GeoSettings) {
        context.routingStore.edit { it[KEY_GEO_CONFIG] = s.toJson() }
    }

    /** Правила «что → через какой выход» и обход DPI (0.7.12). Лежит в routing_settings — значит, входит в профили маршрутизации и резервную копию. */
    val routeConfig: Flow<RouteConfig> = context.routingStore.data.map { RouteConfig.fromJson(it[KEY_ROUTE_CONFIG]) }
    suspend fun setRouteConfig(cfg: RouteConfig) {
        context.routingStore.edit { it[KEY_ROUTE_CONFIG] = cfg.toJson() }
    }

    val ipv6Mode: Flow<Ipv6Mode> = context.routingStore.data.map { Ipv6Mode.fromId(it[KEY_IPV6]) }
    suspend fun setIpv6Mode(mode: Ipv6Mode) {
        context.routingStore.edit { it[KEY_IPV6] = mode.name }
    }

    val mtu: Flow<MtuPreset> = context.routingStore.data.map { MtuPreset.fromId(it[KEY_MTU]) }
    suspend fun setMtu(preset: MtuPreset) {
        context.routingStore.edit { it[KEY_MTU] = preset.name }
    }

    val tlsFragment: Flow<TlsFragmentMode> = context.routingStore.data
        .map { TlsFragmentMode.fromId(it[KEY_TLS_FRAGMENT]) }
    suspend fun setTlsFragment(mode: TlsFragmentMode) {
        context.routingStore.edit { it[KEY_TLS_FRAGMENT] = mode.name }
    }

    val dnsProvider: Flow<DnsProvider> = context.routingStore.data
        .map { DnsProvider.fromId(it[KEY_DNS_PROVIDER]) }
    suspend fun setDnsProvider(provider: DnsProvider) {
        context.routingStore.edit { it[KEY_DNS_PROVIDER] = provider.name }
    }

    val dnsCustomAddress: Flow<String> = context.routingStore.data
        .map { it[KEY_DNS_CUSTOM] ?: "" }
    suspend fun setDnsCustomAddress(address: String) {
        context.routingStore.edit { it[KEY_DNS_CUSTOM] = address.trim() }
    }

    val geoRoutingMode: Flow<GeoRoutingMode> = context.routingStore.data
        .map { GeoRoutingMode.fromId(it[KEY_GEO_MODE]) }
    suspend fun setGeoRoutingMode(mode: GeoRoutingMode) {
        context.routingStore.edit { it[KEY_GEO_MODE] = mode.name }
    }

    /** Страны geo-режима (ISO-коды). По умолчанию — РФ, как было до выбора стран. */
    val geoCountries: Flow<Set<String>> = context.routingStore.data.map { prefs ->
        prefs[KEY_GEO_COUNTRIES]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: setOf("ru")
    }
    suspend fun setGeoCountries(countries: Set<String>) {
        context.routingStore.edit { it[KEY_GEO_COUNTRIES] = countries.sorted().joinToString(",") }
    }

    /**
     * Действующий DNS-сервер: null — SYSTEM (резолвер платформы). Невалидный
     * свой адрес откатывается на Cloudflare — UI не даёт такой сохранить, но
     * значения из старых версий приложения могли остаться.
     */
    suspend fun resolveDns(): DnsEndpoint? =
        combine(dnsProvider, dnsCustomAddress) { provider, custom ->
            when (provider) {
                DnsProvider.SYSTEM -> null
                DnsProvider.CUSTOM -> DnsEndpoint.parse(custom) ?: DnsEndpoint.doh(DnsProvider.CLOUDFLARE.address!!)
                // Приватный DNS — только пока устройство подключено к боту; иначе Cloudflare.
                DnsProvider.HYDRA -> hydraEndpoint(
                    ru.gidravpn.hydra.BuildConfig.HYDRA_PRIVATE_DNS,
                    ru.gidravpn.hydra.data.botaccount.BotAccountStore(context).linked,
                ) ?: DnsEndpoint.doh(DnsProvider.CLOUDFLARE.address!!)
                else -> DnsEndpoint.doh(provider.address!!)
            }
        }.firstOrNull()

    companion object {
        /** Приватный DoH Hydra VPN: null, если устройство не вошло через бота или адрес не задан сборкой. */
        fun hydraEndpoint(url: String, botLinked: Boolean): DnsEndpoint? =
            if (botLinked && url.isNotBlank()) DnsEndpoint.parse(url) else null
    }
}
