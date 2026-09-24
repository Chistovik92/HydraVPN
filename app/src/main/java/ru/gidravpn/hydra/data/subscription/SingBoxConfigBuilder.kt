package ru.gidravpn.hydra.data.subscription

import ru.gidravpn.hydra.data.model.DnsEndpoint
import ru.gidravpn.hydra.data.model.GeoRoutingMode
import ru.gidravpn.hydra.data.model.HotspotSettings
import ru.gidravpn.hydra.data.model.MtuPreset
import ru.gidravpn.hydra.data.model.TlsFragmentMode
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.SplitTunnel
import ru.gidravpn.hydra.data.model.SplitTunnelMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Преобразует [ServerProfile] в полноценный конфиг sing-box (JSON), который
 * скармливается движку libbox. Собирает tun-inbound + один proxy-outbound +
 * маршрутизацию. Формат — схема sing-box 1.12 (новый формат DNS-серверов
 * с полем type; tun без auto_route — маршруты задаёт VpnService.Builder).
 */
object SingBoxConfigBuilder {

    /** Базы одной страны: IP есть всегда, домены — только у некоторых (см. GeoAssets). */
    data class GeoCountry(val code: String, val geoipPath: String, val geositePath: String? = null)

    /**
     * geoip/geosite-маршрутизация (Фаза 6c). null/OFF/пустой список — правило
     * rule_set не добавляется вовсе, поведение как раньше.
     */
    data class GeoRouting(val mode: GeoRoutingMode, val countries: List<GeoCountry>) {
        val active get() = mode != GeoRoutingMode.OFF && countries.isNotEmpty()
    }

    fun build(
        profile: ServerProfile,
        socksPort: Int = 0,
        splitTunnel: SplitTunnel = SplitTunnel(),
        dns: DnsEndpoint? = DnsEndpoint.doh("1.1.1.1"),
        geoRouting: GeoRouting? = null,
        mtu: Int = MtuPreset.AUTO.value,
        tlsFragment: TlsFragmentMode = TlsFragmentMode.OFF,
        hotspot: HotspotSettings? = null,
        ipv6: Boolean = false,
    ): JSONObject = baseConfig(outboundFor(profile, tlsFragment), splitTunnel, dns, geoRouting, mtu, hotspot, ipv6)

    /**
     * Мост Xray → tun (см. `XrayCore` в native-flavor): Xray сам tun не
     * обслуживает, поэтому он поднимается headless с локальным socks5-inbound
     * (`127.0.0.1:$socksPort`), а весь TUN/статистику/split tunneling берёт на
     * себя sing-box — единственный outbound здесь указывает на этот же порт.
     */
    fun buildXrayBridge(
        socksPort: Int,
        splitTunnel: SplitTunnel = SplitTunnel(),
        dns: DnsEndpoint? = DnsEndpoint.doh("1.1.1.1"),
        geoRouting: GeoRouting? = null,
        mtu: Int = MtuPreset.AUTO.value,
        hotspot: HotspotSettings? = null,
        ipv6: Boolean = false,
    ): JSONObject {
        val outbound = JSONObject().put("type", "socks").put("tag", "proxy")
            .put("server", "127.0.0.1").put("server_port", socksPort)
        return baseConfig(outbound, splitTunnel, dns, geoRouting, mtu, hotspot, ipv6)
    }

    private fun baseConfig(
        outbound: JSONObject,
        splitTunnel: SplitTunnel,
        dns: DnsEndpoint?,
        geoRouting: GeoRouting?,
        mtu: Int,
        hotspot: HotspotSettings?,
        ipv6: Boolean = false,
    ): JSONObject {
        val root = JSONObject()

        root.put("log", JSONObject().put("level", "info").put("timestamp", true))

        // Счётчики трафика libbox отдаёт из clash-сервера: без этого блока
        // StatusMessage.trafficAvailable = false и в UI вечные 0,0 MB.
        // external_controller не задаём — HTTP-слушатель наружу не нужен,
        // менеджер трафика работает и без него.
        root.put("experimental", JSONObject().put("clash_api", JSONObject()))

        // DNS (схема 1.12: серверы с явным type). Сюда попадают DNS-запросы
        // приложений — их перехватывает правило hijack-dns ниже. dns == null
        // (пресет "Системный резолвер") — своего сервера нет, остаётся только
        // резолвер платформы (запросы идут мимо туннеля).
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                if (dns != null) {
                    put(JSONObject().apply {
                        put("type", dns.type); put("tag", "remote"); put("server", dns.host)
                        dns.port?.let { put("server_port", it) }
                        dns.path?.let { put("path", it) }
                        put("detour", "proxy")
                        // Хост DoH/DoT (например приватный dns.hydravpn.us) sing-box
                        // 1.12 без domain_resolver не принимает вовсе. Резолвим его
                        // через публичный DoH по IP и тоже через прокси — чтобы
                        // провайдер не видел даже имя своего DNS-сервера.
                        if (!dns.isIp) put("domain_resolver", "bootstrap")
                    })
                    if (!dns.isIp) {
                        put(JSONObject().put("type", "https").put("tag", "bootstrap")
                            .put("server", "1.1.1.1").put("detour", "proxy"))
                    }
                }
                put(JSONObject().put("type", "local").put("tag", "local"))
            })
            put("final", if (dns != null) "remote" else "local")
            put("strategy", "prefer_ipv4")
        })

        // inbound: tun (пакеты берёт наш VpnService через файловый дескриптор;
        // auto_route/strict_route выключены — маршрутизацией владеет VpnService.Builder)
        root.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put("interface_name", "hydra-tun")
                put("mtu", mtu)
                // IPv6-адрес — только в режиме Ipv6Mode.ENABLE; тот же, что у VpnService.Builder.
                put("address", JSONArray().put("172.19.0.1/28").apply {
                    if (ipv6) put("${ru.gidravpn.hydra.data.model.Ipv6Mode.TUN_ADDRESS}/${ru.gidravpn.hydra.data.model.Ipv6Mode.TUN_PREFIX}")
                })
                put("auto_route", false)
                put("strict_route", false)
                put("stack", "gvisor")
            })
            // Хотспот-прокси (6f): SOCKS+HTTP на всех интерфейсах, только с логином/
            // паролем. Идёт через те же route.rules, что и tun: sniff, DNS, split, geo.
            if (hotspot != null && hotspot.isUsable) {
                put(JSONObject().apply {
                    put("type", "mixed")
                    put("tag", "hotspot-in")
                    put("listen", "0.0.0.0")
                    put("listen_port", hotspot.port)
                    put("users", JSONArray().put(
                        JSONObject().put("username", hotspot.username).put("password", hotspot.password)
                    ))
                })
            }
        })

        // outbounds: proxy + direct. Спецаутбаунды dns/block (устарели в 1.11,
        // удаляются в 1.13) заменены rule actions — см. sniff/hijack-dns ниже.
        root.put("outbounds", JSONArray().apply {
            put(outbound)
            put(JSONObject().put("type", "direct").put("tag", "direct"))
        })

        // маршрутизация (+ пользовательские правила split tunneling по IP/доменам — Фаза 2)
        val netRules = splitTunnel.netRules.takeIf { splitTunnel.netActive }.orEmpty()
        val geo = geoRouting?.takeIf { it.active }
        // Теги в том же порядке, что и rule_set: geoip-<cc>, затем geosite-<cc>.
        val geoTags = geo?.countries.orEmpty().flatMap { c ->
            listOfNotNull("geoip-${c.code}" to c.geoipPath, c.geositePath?.let { "geosite-${c.code}" to it })
        }
        root.put("route", JSONObject().apply {
            if (geo != null) {
                put("rule_set", JSONArray().apply {
                    geoTags.forEach { (tag, path) ->
                        put(JSONObject().put("type", "local").put("tag", tag)
                            .put("format", "binary").put("path", path))
                    }
                })
            }
            put("rules", JSONArray().apply {
                // sniff обязан идти первым: без него у соединения нет ни протокола,
                // ни домена. Раньше его не было вовсе — `protocol: dns` не срабатывал
                // (DNS уходил в прокси сырым UDP мимо dns-серверов выше), а доменные
                // правила (netRules DOMAIN*, geosite-ru) не матчились никогда.
                put(JSONObject().put("action", "sniff"))
                put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
                if (netRules.isNotEmpty()) {
                    val ruleOutbound = if (splitTunnel.netMode == SplitTunnelMode.EXCLUDE) "direct" else "proxy"
                    netRules.groupBy({ it.type }, { it.value }).forEach { (type, values) ->
                        put(JSONObject().put(type.singBoxKey, JSONArray(values)).put("outbound", ruleOutbound))
                    }
                }
                put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
                // Любой из rule_set выбранных стран (OR внутри одного правила —
                // стандартная семантика sing-box для массива в одном поле): DIRECT
                // ведёт их мимо VPN, VIA_PROXY — наоборот, единственное, что идёт в прокси.
                if (geo != null) {
                    val geoOutbound = if (geo.mode == GeoRoutingMode.DIRECT) "direct" else "proxy"
                    put(JSONObject()
                        .put("rule_set", JSONArray(geoTags.map { it.first }))
                        .put("outbound", geoOutbound))
                }
            })
            // Явный whitelist по IP/доменам (INCLUDE) — сильнее geo-режима: то, что
            // не попало под явные правила, идёт мимо VPN независимо от geoRouting.
            val netIncludeActive = netRules.isNotEmpty() && splitTunnel.netMode == SplitTunnelMode.INCLUDE
            put("final", when {
                netIncludeActive -> "direct"
                geo?.mode == GeoRoutingMode.VIA_PROXY -> "direct"
                else -> "proxy"
            })
            put("auto_detect_interface", true)
            // Домен самого прокси-сервера резолвим платформенным резолвером, мимо
            // туннеля и мимо dns.final ("remote" идёт через этот же прокси —
            // резолвить его адрес через него самого = замкнутый круг). Без этого
            // поля sing-box 1.12 предупреждает, а в 1.14 откажется стартовать.
            put("default_domain_resolver", "local")
        })

        return root
    }

    private fun outboundFor(p: ServerProfile, tlsFragment: TlsFragmentMode = TlsFragmentMode.OFF): JSONObject {
        val o = JSONObject().put("tag", "proxy").put("server", p.address).put("server_port", p.port)
        val extra = runCatching { JSONObject(p.extra) }.getOrDefault(JSONObject())

        when (p.protocol) {
            Protocol.VLESS -> {
                o.put("type", "vless").put("uuid", p.uuidOrPassword)
                if (p.flow.isNotEmpty()) o.put("flow", p.flow)
                o.put("tls", tlsBlock(p, extra, tlsFragment))
                transportBlock(p)?.let { o.put("transport", it) }
            }
            Protocol.VMESS -> {
                o.put("type", "vmess").put("uuid", p.uuidOrPassword)
                    .put("security", "auto").put("alter_id", extra.optInt("aid", 0))
                if (p.security == "tls") o.put("tls", tlsBlock(p, extra, tlsFragment))
                transportBlock(p)?.let { o.put("transport", it) }
            }
            Protocol.TROJAN -> {
                o.put("type", "trojan").put("password", p.uuidOrPassword)
                o.put("tls", tlsBlock(p, extra, tlsFragment))
                transportBlock(p)?.let { o.put("transport", it) }
            }
            Protocol.SHADOWSOCKS -> {
                o.put("type", "shadowsocks")
                    .put("method", extra.optString("method", "aes-256-gcm"))
                    .put("password", p.uuidOrPassword)
            }
            Protocol.HYSTERIA2 -> {
                o.put("type", "hysteria2").put("password", p.uuidOrPassword)
                o.put("tls", tlsBlock(p, extra))
                if (extra.has("obfs")) o.put("obfs", JSONObject()
                    .put("type", "salamander").put("password", extra.optString("obfs_password")))
            }
            Protocol.WIREGUARD -> {
                // Обычный WireGuard через sing-box (для AmneziaWG — отдельный движок amneziawg-go)
                o.put("type", "wireguard")
                    .put("private_key", p.uuidOrPassword)
                    .put("peer_public_key", extra.optString("public_key"))
                    .put("local_address", JSONArray(
                        extra.optString("local_address").ifBlank { "172.19.0.2/32" }
                            .split(",").map { it.trim() }
                    ))
                if (extra.has("preshared_key")) o.put("pre_shared_key", extra.optString("preshared_key"))
                if (extra.has("mtu")) o.put("mtu", extra.optInt("mtu", 1408))
            }
            Protocol.TUIC -> {
                o.put("type", "tuic").put("uuid", p.uuidOrPassword)
                    .put("password", extra.optString("password"))
                    .put("congestion_control", extra.optString("congestion_control", "bbr"))
                o.put("tls", tlsBlock(p, extra))
            }
            else -> o.put("type", "direct")  // SSTP/L2TP/PPTP/AWG обрабатываются отдельными движками, не sing-box
        }
        return o
    }

    private fun tlsBlock(
        p: ServerProfile,
        extra: JSONObject,
        fragment: TlsFragmentMode = TlsFragmentMode.OFF,
    ): JSONObject {
        val tls = JSONObject().put("enabled", true)
            .put("server_name", p.sni.ifBlank { p.address })
        if (p.alpn.isNotBlank()) tls.put("alpn", JSONArray(p.alpn.split(",").map { it.trim() }))
        // uTLS отпечаток
        tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", p.fingerprint))
        // REALITY
        if (p.security == "reality" && extra.has("reality_pbk")) {
            tls.put("reality", JSONObject()
                .put("enabled", true)
                .put("public_key", extra.optString("reality_pbk"))
                .put("short_id", extra.optString("reality_sid")))
        }
        when (fragment) {
            TlsFragmentMode.OFF -> Unit
            TlsFragmentMode.RECORD -> tls.put("record_fragment", true)
            TlsFragmentMode.TCP -> tls.put("fragment", true)
        }
        return tls
    }

    private fun transportBlock(p: ServerProfile): JSONObject? = when (p.transport) {
        "ws" -> JSONObject().put("type", "ws")
            .put("path", p.transportPath.ifBlank { "/" })
        "grpc" -> JSONObject().put("type", "grpc")
            .put("service_name", p.transportPath)
        "http" -> JSONObject().put("type", "http").put("path", p.transportPath.ifBlank { "/" })
        else -> null   // tcp — транспорт не указывается
    }
}
