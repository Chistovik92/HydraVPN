package ru.gidravpn.hydra.servers

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Все типы подключений, которые Hydra создаёт в панели 3x-ui (0.8.2-a2, запрос владельца: «создавать все типы подключений
 * из приложения в панелях, которые они поддерживают»). Настройки — по фабрикам самой панели (`frontend/src/lib/xray/
 * inbound-defaults.ts` 3x-ui v3.10.2): учётные данные клиента панель заполняет сама (`fillProtocolDefaults`), ключи и
 * обфускацию AmneziaWG — тоже (`defaultAmneziaWGServer`). Ссылку клиента строит панель (`/panel/api/clients/links`).
 *
 * [needsCert] — нужен настоящий сертификат: для Hysteria 2 и TUIC панель кладёт в ссылку только `pinSHA256` (не «insecure»),
 * поэтому с самоподписанным сертификатом клиенты не подключатся. Hydra создаёт их, когда у сервера есть сертификат
 * Let's Encrypt ([XuiPanel.CERT_DIR], «Включить HTTPS»). [udp] — порт открывается в файрволе как UDP.
 */
enum class XuiConnection(val protocol: String, val title: String, val needsCert: Boolean = false, val udp: Boolean = false) {
    VLESS_GRPC_REALITY("vless", "VLESS + gRPC + REALITY"),
    VLESS_XHTTP_REALITY("vless", "VLESS + XHTTP + REALITY"),
    VLESS_TCP_REALITY("vless", "VLESS + TCP + REALITY"),
    TROJAN_REALITY("trojan", "Trojan + REALITY"),
    VMESS_WS("vmess", "VMess + WebSocket"),
    SHADOWSOCKS_2022("shadowsocks", "Shadowsocks 2022"),
    WIREGUARD("wireguard", "WireGuard", udp = true),
    AMNEZIAWG("amneziawg", "AmneziaWG", udp = true),
    HYSTERIA2("hysteria", "Hysteria 2", needsCert = true, udp = true),
    TUIC("tuic", "TUIC v5", needsCert = true, udp = true),
    ;

    /** Нужны ли ключи REALITY от панели ([XuiApi.newRealityKeys]). */
    val reality get() = this in setOf(VLESS_GRPC_REALITY, VLESS_XHTTP_REALITY, VLESS_TCP_REALITY, TROJAN_REALITY)

    companion object {
        /** Сертификат сервера: пути к файлам (как их кладёт acme.sh в 0.8.1 — [XuiPanel.CERT_DIR]). */
        data class Cert(val certFile: String, val keyFile: String, val serverName: String = "")

        private fun rnd(n: Int, r: SecureRandom) = XuiPanel.random(n, r).lowercase()

        private fun b64(bytes: Int, r: SecureRandom) = java.util.Base64.getEncoder().encodeToString(ByteArray(bytes).also { r.nextBytes(it) })

        /**
         * Инбаунд для [XuiApi.addInbound]. [priv]/[pub] — ключи REALITY (для [reality]), [cert] — для [needsCert].
         * Клиентов в инбаунде нет: пользователь подключается потом ([XuiApi.attachClient]), панель сама выдаёт ему ключи.
         */
        fun inbound(type: XuiConnection, port: Int, remark: String, mask: XuiPanel.RealityMask = XuiPanel.RealityMask.VK,
                    priv: String = "", pub: String = "", cert: Cert? = null, r: SecureRandom = SecureRandom(), nodeId: Int = 0,
                    customDomain: String = ""): JSONObject {
            require(!type.reality || (priv.isNotBlank() && pub.isNotBlank())) { "Для ${type.title} нужны ключи REALITY" }
            require(!type.needsCert || cert != null) { "Для ${type.title} нужен сертификат сервера" }
            val base = when (type) {
                VLESS_GRPC_REALITY -> XuiPanel.defaultInbound(port, priv, pub, mask, remark, r, XuiPanel.Transport.GRPC, customDomain)
                VLESS_XHTTP_REALITY -> XuiPanel.defaultInbound(port, priv, pub, mask, remark, r, XuiPanel.Transport.XHTTP, customDomain)
                VLESS_TCP_REALITY -> XuiPanel.defaultInbound(port, priv, pub, mask, remark, r, XuiPanel.Transport.GRPC, customDomain).also { ib ->
                    ib.getJSONObject("streamSettings").apply { remove("grpcSettings"); put("network", "tcp")
                        put("tcpSettings", JSONObject().put("acceptProxyProtocol", false).put("header", JSONObject().put("type", "none"))) }
                }
                TROJAN_REALITY -> XuiPanel.defaultInbound(port, priv, pub, mask, remark, r, XuiPanel.Transport.GRPC, customDomain).also { ib ->
                    ib.put("protocol", "trojan").put("settings", JSONObject().put("clients", JSONArray()).put("fallbacks", JSONArray()))
                    ib.getJSONObject("streamSettings").apply { remove("grpcSettings"); put("network", "tcp")
                        put("tcpSettings", JSONObject().put("acceptProxyProtocol", false).put("header", JSONObject().put("type", "none"))) }
                }
                VMESS_WS -> JSONObject().put("protocol", "vmess").put("settings", JSONObject().put("clients", JSONArray()))
                    .put("streamSettings", JSONObject().put("network", "ws").put("security", "none").put("externalProxy", JSONArray())
                        .put("wsSettings", JSONObject().put("path", "/" + rnd(10, r)).put("host", "").put("headers", JSONObject()).put("acceptProxyProtocol", false)))
                SHADOWSOCKS_2022 -> JSONObject().put("protocol", "shadowsocks")
                    .put("settings", JSONObject().put("method", "2022-blake3-aes-256-gcm").put("password", b64(32, r)).put("network", "tcp,udp")
                        .put("clients", JSONArray()).put("ivCheck", false))
                    .put("streamSettings", JSONObject().put("network", "tcp").put("security", "none").put("externalProxy", JSONArray())
                        .put("tcpSettings", JSONObject().put("acceptProxyProtocol", false).put("header", JSONObject().put("type", "none"))))
                WIREGUARD -> JSONObject().put("protocol", "wireguard")
                    .put("settings", JSONObject().put("mtu", 1420).put("secretKey", X25519.generate(r).privateKey).put("peers", JSONArray())
                        .put("clients", JSONArray()).put("noKernelTun", false).put("subnetIp", "10.0.0.0").put("subnetCidr", 24))
                AMNEZIAWG -> JSONObject().put("protocol", "amneziawg").put("settings", JSONObject())   // панель заполнит всё сама
                HYSTERIA2 -> JSONObject().put("protocol", "hysteria").put("settings", JSONObject().put("version", 2).put("clients", JSONArray()))
                    .put("streamSettings", JSONObject().put("network", "hysteria").put("security", "tls").put("externalProxy", JSONArray())
                        .put("tlsSettings", tls(cert!!, listOf("h3")))
                        .put("hysteriaSettings", JSONObject().put("version", 2)))
                TUIC -> JSONObject().put("protocol", "tuic").put("settings", JSONObject().put("clients", JSONArray()).put("server", JSONObject()
                    .put("certificate", cert!!.certFile).put("private_key", cert.keyFile).put("congestion_control", "bbr")
                    .put("alpn", JSONArray(listOf("h3"))).put("udp_relay_mode", "native").put("zero_rtt_handshake", false)
                    .put("max_idle_time", 15).put("authentication_timeout", 3).put("max_udp_relay_packet_size", 1500).put("sni", cert.serverName)))
            }
            val sniffing = JSONObject().put("enabled", true).put("destOverride", JSONArray(listOf("http", "tls", "quic"))).put("metadataOnly", false).put("routeOnly", false)
            return base.put("enable", true).put("remark", remark).put("listen", "").put("port", port).put("expiryTime", 0).put("total", 0)
                .apply { if (nodeId > 0) put("nodeId", nodeId); if (!has("sniffing")) put("sniffing", sniffing) }
        }

        private fun tls(c: Cert, alpn: List<String>) = JSONObject().put("serverName", c.serverName).put("minVersion", "1.2").put("maxVersion", "1.3")
            .put("alpn", JSONArray(alpn)).put("certificates", JSONArray().put(JSONObject().put("certificateFile", c.certFile).put("keyFile", c.keyFile)
                .put("ocspStapling", 3600).put("oneTimeLoading", false).put("usage", "encipherment").put("buildChain", false)))
            .put("settings", JSONObject().put("fingerprint", "chrome"))
    }
}
