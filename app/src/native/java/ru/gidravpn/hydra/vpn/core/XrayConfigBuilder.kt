package ru.gidravpn.hydra.vpn.core

import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Строит конфиг Xray-core (JSON) с локальным socks-inbound и proxy-outbound.
 *
 * streamSettings покрывается полностью (docs/PROTOCOLS.md, раздел Xray):
 *  - security: none | tls | reality;
 *  - tls: serverName, alpn, fingerprint (uTLS), allowInsecure;
 *  - reality: publicKey, shortId, spiderX;
 *  - transport: tcp (header.type), ws (path/host), grpc (serviceName/multiMode),
 *    http (path/host), httpupgrade (path/host).
 *
 * ВАЖНО: Xray-core не управляет tun-интерфейсом — схема запуска
 * «tun → tun2socks (hev-socks5-tunnel) → socks-inbound Xray», см. XrayCore.
 */
object XrayConfigBuilder {

    /** RFC 1918/5735/4193 + IPv4/IPv6 loopback и link-local — без geoip.dat. */
    private val PRIVATE_IP_RANGES = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
        "127.0.0.0/8", "169.254.0.0/16",
        "::1/128", "fc00::/7", "fe80::/10",
    )

    fun build(p: ServerProfile, socksPort: Int = 10808, dnsAddress: String? = "1.1.1.1"): String {
        val extra = runCatching { JSONObject(p.extra) }.getOrDefault(JSONObject())
        val root = JSONObject()

        root.put("log", JSONObject().put("loglevel", "warning"))

        // DNS: через прокси, иначе локально. dnsAddress == null (пресет
        // "Системный резолвер") — DoH-сервер не добавляется, остаётся localhost.
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                if (dnsAddress != null) {
                    put(JSONObject().put("address", "https://$dnsAddress/dns-query").put("domains", JSONArray()))
                }
                put("localhost")
            })
        })

        // inbounds: локальный SOCKS5 для tun2socks
        root.put("inbounds", JSONArray().put(JSONObject().apply {
            put("tag", "socks-in"); put("listen", "127.0.0.1"); put("port", socksPort)
            put("protocol", "socks")
            put("settings", JSONObject().put("udp", true).put("auth", "noauth"))
        }))

        // outbounds
        val proxy = outboundFor(p, extra)
        root.put("outbounds", JSONArray().apply {
            put(proxy)
            put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        })

        // маршрутизация: приватные адреса — напрямую, остальное — в прокси.
        // Явные CIDR вместо "geoip:private" — тому нужен файл geoip.dat,
        // которого нет (embedded-сборка без ассетов), с ним Xray падает:
        // "failed to open geoip.dat: stat ...: no such file or directory".
        root.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray(PRIVATE_IP_RANGES))
                    put("outboundTag", "direct")
                })
            })
        })

        return root.toString(2)
    }

    private fun outboundFor(p: ServerProfile, extra: JSONObject): JSONObject {
        val outbound = JSONObject().put("tag", "proxy")
        val stream = streamSettings(p, extra)

        when (p.protocol) {
            Protocol.VMESS -> {
                outbound.put("protocol", "vmess")
                val vnext = JSONObject().put("address", p.address).put("port", p.port)
                vnext.put("users", JSONArray().put(JSONObject().apply {
                    put("id", p.uuidOrPassword)
                    put("security", "auto")
                    put("alterId", extra.optInt("aid", 0))
                    put("encryption", "auto")
                }))
                outbound.put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            }
            Protocol.TROJAN -> {
                outbound.put("protocol", "trojan")
                outbound.put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().apply {
                    put("address", p.address)
                    put("port", p.port)
                    put("password", p.uuidOrPassword)
                })))
            }
            Protocol.SHADOWSOCKS -> {
                outbound.put("protocol", "shadowsocks")
                outbound.put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().apply {
                    put("address", p.address)
                    put("port", p.port)
                    put("method", extra.optString("method", "aes-256-gcm"))
                    put("password", p.uuidOrPassword)
                })))
            }
            else -> {
                // VLESS по умолчанию
                outbound.put("protocol", "vless")
                val vnext = JSONObject().put("address", p.address).put("port", p.port)
                val user = JSONObject().put("id", p.uuidOrPassword).put("encryption", "none")
                if (p.flow.isNotEmpty()) user.put("flow", p.flow)
                vnext.put("users", JSONArray().put(user))
                outbound.put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            }
        }

        outbound.put("streamSettings", stream)
        return outbound
    }

    /** Полный streamSettings: security (tls/reality) + транспорт. */
    private fun streamSettings(p: ServerProfile, extra: JSONObject): JSONObject {
        val stream = JSONObject()
        stream.put("network", transportName(p.transport))
        val security = when {
            p.security == "reality" -> "reality"
            p.security == "tls" -> "tls"
            p.security.isBlank() && p.protocol == Protocol.TROJAN -> "tls"  // trojan подразумевает TLS
            else -> p.security.ifBlank { "none" }
        }
        stream.put("security", security)

        // --- tls / reality ---
        when (stream.optString("security")) {
            "tls" -> stream.put("tlsSettings", JSONObject().apply {
                put("serverName", p.sni.ifBlank { p.address })
                put("allowInsecure", extra.optBoolean("allow_insecure", false))
                if (p.alpn.isNotBlank()) put("alpn", JSONArray(p.alpn.split(",").map { it.trim() }))
                // uTLS-отпечаток
                if (p.fingerprint.isNotBlank()) put("fingerprint", p.fingerprint)
            })
            "reality" -> stream.put("realitySettings", JSONObject().apply {
                put("serverName", p.sni.ifBlank { p.address })
                put("publicKey", extra.optString("reality_pbk"))
                put("shortId", extra.optString("reality_sid"))
                put("spiderX", extra.optString("reality_spx", "/"))
                if (p.fingerprint.isNotBlank()) put("fingerprint", p.fingerprint)
            })
        }

        // --- транспорт ---
        when (transportName(p.transport)) {
            "ws" -> stream.put("wsSettings", JSONObject().apply {
                put("path", p.transportPath.ifBlank { "/" })
                if (p.sni.isNotBlank()) put("host", p.sni)
            })
            "grpc" -> stream.put("grpcSettings", JSONObject().apply {
                put("serviceName", p.transportPath)
                put("multiMode", extra.optBoolean("grpc_multi", false))
            })
            "http" -> stream.put("httpSettings", JSONObject().apply {
                put("path", p.transportPath.ifBlank { "/" })
                if (p.sni.isNotBlank()) put("host", JSONArray().put(p.sni))
            })
            "httpupgrade" -> stream.put("httpupgradeSettings", JSONObject().apply {
                put("path", p.transportPath.ifBlank { "/" })
                if (p.sni.isNotBlank()) put("host", p.sni)
            })
            "tcp" -> {
                // xtls-rprx-vision предполагает прозрачный TCP без заголовков
                if (extra.optString("tcp_header_type").isNotBlank()) {
                    stream.put("tcpSettings", JSONObject().apply {
                        put("header", JSONObject().put("type", extra.optString("tcp_header_type")))
                    })
                }
            }
        }
        return stream
    }

    private fun transportName(t: String): String = when (t.lowercase()) {
        "ws", "grpc", "http", "httpupgrade", "h2" -> if (t == "h2") "http" else t
        else -> "tcp"
    }
}
