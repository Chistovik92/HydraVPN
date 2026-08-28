package ru.gidravpn.hydra.data.subscription

import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Преобразует [ServerProfile] в полноценный конфиг sing-box (JSON), который
 * скармливается движку libbox. Собирает tun-inbound + один proxy-outbound +
 * маршрутизацию. Формат — схема sing-box 1.12 (новый формат DNS-серверов
 * с полем type; tun без auto_route — маршруты задаёт VpnService.Builder).
 */
object SingBoxConfigBuilder {

    fun build(profile: ServerProfile, socksPort: Int = 0): JSONObject {
        val outbound = outboundFor(profile)
        val root = JSONObject()

        root.put("log", JSONObject().put("level", "info").put("timestamp", true))

        // DNS (схема 1.12: серверы с явным type)
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().put("type", "https").put("tag", "remote")
                    .put("server", "1.1.1.1").put("detour", "proxy"))
                put(JSONObject().put("type", "local").put("tag", "local"))
            })
            put("strategy", "prefer_ipv4")
        })

        // inbound: tun (пакеты берёт наш VpnService через файловый дескриптор;
        // auto_route/strict_route выключены — маршрутизацией владеет VpnService.Builder)
        root.put("inbounds", JSONArray().put(JSONObject().apply {
            put("type", "tun")
            put("tag", "tun-in")
            put("interface_name", "hydra-tun")
            put("mtu", 9000)
            put("address", JSONArray().put("172.19.0.1/28"))
            put("auto_route", false)
            put("strict_route", false)
            put("stack", "gvisor")
        }))

        // outbounds: proxy + direct + dns + block
        root.put("outbounds", JSONArray().apply {
            put(outbound)
            put(JSONObject().put("type", "direct").put("tag", "direct"))
            put(JSONObject().put("type", "dns").put("tag", "dns-out"))
            put(JSONObject().put("type", "block").put("tag", "block"))
        })

        // маршрутизация
        root.put("route", JSONObject().apply {
            put("rules", JSONArray().apply {
                put(JSONObject().put("protocol", "dns").put("outbound", "dns-out"))
                put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
            })
            put("final", "proxy")
            put("auto_detect_interface", true)
        })

        return root
    }

    private fun outboundFor(p: ServerProfile): JSONObject {
        val o = JSONObject().put("tag", "proxy").put("server", p.address).put("server_port", p.port)
        val extra = runCatching { JSONObject(p.extra) }.getOrDefault(JSONObject())

        when (p.protocol) {
            Protocol.VLESS -> {
                o.put("type", "vless").put("uuid", p.uuidOrPassword)
                if (p.flow.isNotEmpty()) o.put("flow", p.flow)
                o.put("tls", tlsBlock(p, extra))
                transportBlock(p)?.let { o.put("transport", it) }
            }
            Protocol.VMESS -> {
                o.put("type", "vmess").put("uuid", p.uuidOrPassword)
                    .put("security", "auto").put("alter_id", extra.optInt("aid", 0))
                if (p.security == "tls") o.put("tls", tlsBlock(p, extra))
                transportBlock(p)?.let { o.put("transport", it) }
            }
            Protocol.TROJAN -> {
                o.put("type", "trojan").put("password", p.uuidOrPassword)
                o.put("tls", tlsBlock(p, extra))
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

    private fun tlsBlock(p: ServerProfile, extra: JSONObject): JSONObject {
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
