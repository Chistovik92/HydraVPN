package ru.gidravpn.hydra.data.subscription

import org.json.JSONArray
import org.json.JSONObject
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile

/**
 * Конфиг Xray-core (JSON): локальный socks-inbound + proxy-outbound. Та же схема, что
 * `XrayConfigBuilder` Android-приложения (app/src/native): Xray поднимается headless,
 * а tun, DNS приложений и вся маршрутизация — на стороне sing-box
 * ([SingBoxConfigBuilder.buildXrayBridge]).
 *
 * Отличие ПК: socks-inbound может требовать логин/пароль ([SocksAuth]) — на компьютере
 * порт 127.0.0.1 видят все процессы всех пользователей, и без пароля любой из них
 * ходил бы через туннель, минуя правила маршрутизации Hydra.
 */
object XrayConfigBuilder {

    data class SocksAuth(val user: String, val pass: String)

    /** RFC 1918/5735/4193 + loopback и link-local — явные CIDR вместо geoip:private (нет geoip.dat). */
    private val PRIVATE_IP_RANGES = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
        "127.0.0.0/8", "169.254.0.0/16",
        "::1/128", "fc00::/7", "fe80::/10",
    )

    fun build(
        p: ServerProfile,
        socksPort: Int,
        dnsUrl: String? = "https://1.1.1.1/dns-query",
        auth: SocksAuth? = null,
        logLevel: String = "warning",
    ): JSONObject {
        require(p.protocol in XRAY_PROTOCOLS) { "Xray не обслуживает ${p.protocol?.displayName ?: p.protocolId}" }
        val extra = runCatching { JSONObject(p.extra) }.getOrDefault(JSONObject())
        val root = JSONObject()

        root.put("log", JSONObject().put("loglevel", logLevel))

        // Внутренний DNS Xray нужен только для имени самого прокси-сервера: DNS приложений
        // перехватывает sing-box-мост. dnsUrl == null (системный резолвер или DoT) — localhost.
        root.put("dns", JSONObject().put("servers", JSONArray().apply {
            if (dnsUrl != null) put(JSONObject().put("address", dnsUrl).put("domains", JSONArray()))
            put("localhost")
        }))

        root.put("inbounds", JSONArray().put(JSONObject().apply {
            put("tag", "socks-in"); put("listen", "127.0.0.1"); put("port", socksPort)
            put("protocol", "socks")
            put("settings", JSONObject().put("udp", true).apply {
                if (auth == null) put("auth", "noauth")
                else put("auth", "password").put("accounts", JSONArray().put(
                    JSONObject().put("user", auth.user).put("pass", auth.pass)))
            })
        }))

        root.put("outbounds", JSONArray().apply {
            put(outboundFor(p, extra))
            put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        })

        root.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", JSONArray().put(JSONObject()
                .put("type", "field").put("ip", JSONArray(PRIVATE_IP_RANGES)).put("outboundTag", "direct")))
        })
        return root
    }

    /** Ровно то, что умеет этот генератор (см. EngineToggles.XRAY_CAPABLE). */
    val XRAY_PROTOCOLS = setOf(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS)

    private fun outboundFor(p: ServerProfile, extra: JSONObject): JSONObject {
        val outbound = JSONObject().put("tag", "proxy")
        when (p.protocol) {
            Protocol.VMESS -> {
                outbound.put("protocol", "vmess")
                val vnext = JSONObject().put("address", p.address).put("port", p.port)
                vnext.put("users", JSONArray().put(JSONObject()
                    .put("id", p.uuidOrPassword).put("security", "auto")
                    .put("alterId", extra.optInt("aid", 0))))
                outbound.put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            }
            Protocol.TROJAN -> {
                outbound.put("protocol", "trojan")
                outbound.put("settings", JSONObject().put("servers", JSONArray().put(JSONObject()
                    .put("address", p.address).put("port", p.port).put("password", p.uuidOrPassword))))
            }
            Protocol.SHADOWSOCKS -> {
                outbound.put("protocol", "shadowsocks")
                outbound.put("settings", JSONObject().put("servers", JSONArray().put(JSONObject()
                    .put("address", p.address).put("port", p.port)
                    .put("method", extra.optString("method", "aes-256-gcm"))
                    .put("password", p.uuidOrPassword))))
            }
            else -> {
                outbound.put("protocol", "vless")
                val user = JSONObject().put("id", p.uuidOrPassword).put("encryption", "none")
                if (p.flow.isNotEmpty()) user.put("flow", p.flow)
                val vnext = JSONObject().put("address", p.address).put("port", p.port)
                    .put("users", JSONArray().put(user))
                outbound.put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            }
        }
        // У Shadowsocks нет TLS/транспорта — streamSettings только для остальных.
        if (p.protocol != Protocol.SHADOWSOCKS) outbound.put("streamSettings", streamSettings(p, extra))
        return outbound
    }

    private fun streamSettings(p: ServerProfile, extra: JSONObject): JSONObject {
        val stream = JSONObject()
        val network = transportName(p.transport)
        stream.put("network", network)
        val security = when {
            p.security == "reality" -> "reality"
            p.security == "tls" -> "tls"
            p.protocol == Protocol.TROJAN -> "tls"   // trojan подразумевает TLS
            else -> "none"
        }
        stream.put("security", security)

        when (security) {
            "tls" -> stream.put("tlsSettings", JSONObject().apply {
                put("serverName", p.sni.ifBlank { p.address })
                put("allowInsecure", extra.optBoolean("allow_insecure", false))
                if (p.alpn.isNotBlank()) put("alpn", JSONArray(p.alpn.split(",").map { it.trim() }))
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

        when (network) {
            "ws" -> stream.put("wsSettings", JSONObject().apply {
                put("path", p.transportPath.ifBlank { "/" })
                if (p.sni.isNotBlank()) put("host", p.sni)
            })
            "grpc" -> stream.put("grpcSettings", JSONObject()
                .put("serviceName", p.transportPath)
                .put("multiMode", extra.optBoolean("grpc_multi", false)))
            "httpupgrade" -> stream.put("httpupgradeSettings", JSONObject().apply {
                put("path", p.transportPath.ifBlank { "/" })
                if (p.sni.isNotBlank()) put("host", p.sni)
            })
            "xhttp" -> stream.put("xhttpSettings", JSONObject().apply {
                put("path", p.transportPath.ifBlank { "/" })
                if (p.sni.isNotBlank()) put("host", p.sni)
            })
            "tcp" -> extra.optString("tcp_header_type").takeIf { it.isNotBlank() }?.let { type ->
                stream.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", type)))
            }
        }
        return stream
    }

    /**
     * Транспорт в терминах Xray. «http»/«h2» в новых Xray удалён (с 24.x) — ближайшая
     * замена с тем же поведением на сервере-посреднике — xhttp.
     */
    private fun transportName(t: String): String = when (t.lowercase()) {
        "ws", "grpc", "httpupgrade", "xhttp" -> t.lowercase()
        "http", "h2", "splithttp" -> "xhttp"
        else -> "tcp"
    }
}
