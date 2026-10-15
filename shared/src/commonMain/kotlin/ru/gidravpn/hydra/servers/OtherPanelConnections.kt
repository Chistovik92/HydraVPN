package ru.gidravpn.hydra.servers

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Подключения в панелях без API-токена (0.8.2-a2): x-ui (alireza0) и s-ui. В отличие от 3x-ui, учётные данные клиента
 * Hydra придумывает сама и кладёт в инбаунд сразу. Все подключения Hydra в одной панели — у одного пользователя-подписки
 * ([subId]): подписку достаточно один раз добавить в «Серверы».
 */
object OtherPanelConnections {
    /**
     * Что Hydra создаёт в x-ui v1.14.0 — проверено трафиком на стенде (0.8.2-a2; UDP — клиентом на самом стенде). AmneziaWG и
     * TUIC в x-ui нет.
     */
    val XUI = listOf(XuiConnection.VLESS_GRPC_REALITY, XuiConnection.VLESS_XHTTP_REALITY, XuiConnection.VLESS_TCP_REALITY, XuiConnection.TROJAN_REALITY,
        XuiConnection.VMESS_WS, XuiConnection.SHADOWSOCKS_2022, XuiConnection.WIREGUARD, XuiConnection.HYSTERIA2)

    /**
     * Что Hydra создаёт в s-ui 1.6.4 (sing-box). XHTTP в sing-box нет; WireGuard в s-ui — «эндпоинт», а не инбаунд, и
     * AmneziaWG нет вовсе — для них есть 3x-ui и AmneziaWG от самой Hydra.
     */
    val SUI = listOf(XuiConnection.VLESS_TCP_REALITY, XuiConnection.VLESS_GRPC_REALITY, XuiConnection.TROJAN_REALITY, XuiConnection.VMESS_WS,
        XuiConnection.SHADOWSOCKS_2022, XuiConnection.HYSTERIA2, XuiConnection.TUIC)

    /** Какие подключения Hydra умеет создавать в панели этого вида (пусто — только на сайте панели). */
    fun typesFor(kind: PanelKind): List<XuiConnection> = when (kind) {
        PanelKind.XUI3 -> XuiConnection.entries
        PanelKind.XUI -> XUI
        PanelKind.SUI -> SUI
        PanelKind.PASARGUARD -> PasarGuardPanel.TYPES
        PanelKind.REMNAWAVE -> RemnawavePanel.TYPES
    }

    // ------------------------------------------------------------------------------------------ s-ui (sing-box)

    /** Объект TLS s-ui с REALITY ([sni] — сайт маскировки, ключи — от `api/keypairs?k=reality`). */
    fun suiRealityTls(name: String, sni: String, priv: String, pub: String, sid: String): JSONObject = JSONObject().put("name", name)
        .put("server", JSONObject().put("enabled", true).put("server_name", sni)
            .put("reality", JSONObject().put("enabled", true).put("handshake", JSONObject().put("server", sni).put("server_port", 443))
                .put("private_key", priv).put("short_id", JSONArray(listOf(sid)))))
        .put("client", JSONObject().put("utls", JSONObject().put("enabled", true).put("fingerprint", "chrome"))
            .put("reality", JSONObject().put("enabled", true).put("public_key", pub).put("short_id", sid)))

    /** Объект TLS s-ui с сертификатом сервера (Hysteria 2, TUIC). */
    fun suiCertTls(name: String, cert: XuiConnection.Companion.Cert, alpn: List<String>): JSONObject = JSONObject().put("name", name)
        .put("server", JSONObject().put("enabled", true).put("server_name", cert.serverName).put("alpn", JSONArray(alpn))
            .put("certificate_path", cert.certFile).put("key_path", cert.keyFile))
        .put("client", JSONObject().put("alpn", JSONArray(alpn)))

    /** Инбаунд s-ui (формат sing-box) для [type]; [tlsId] — объект TLS (0 — без TLS). */
    fun suiInbound(type: XuiConnection, tag: String, port: Int, tlsId: Int, ssServerKey: String, r: SecureRandom = SecureRandom()): JSONObject {
        require(type in SUI) { "${type.title} в s-ui нет" }
        val o = JSONObject().put("tag", tag).put("listen", "::").put("listen_port", port)
        when (type) {
            XuiConnection.VLESS_TCP_REALITY -> o.put("type", "vless").put("tls_id", tlsId).put("transport", JSONObject())
            XuiConnection.VLESS_GRPC_REALITY -> o.put("type", "vless").put("tls_id", tlsId)
                .put("transport", JSONObject().put("type", "grpc").put("service_name", XuiPanel.random(10, r).lowercase()))
            XuiConnection.TROJAN_REALITY -> o.put("type", "trojan").put("tls_id", tlsId).put("transport", JSONObject())
            XuiConnection.VMESS_WS -> o.put("type", "vmess").put("tls_id", 0)
                .put("transport", JSONObject().put("type", "ws").put("path", "/" + XuiPanel.random(10, r).lowercase()))
            XuiConnection.SHADOWSOCKS_2022 -> o.put("type", "shadowsocks").put("method", "2022-blake3-aes-256-gcm").put("password", ssServerKey)
            XuiConnection.HYSTERIA2 -> o.put("type", "hysteria2").put("tls_id", tlsId)
            XuiConnection.TUIC -> o.put("type", "tuic").put("tls_id", tlsId).put("congestion_control", "bbr")
            else -> error("${type.title} в s-ui нет")
        }
        return o
    }

    /** Клиент s-ui: учётные данные под каждый протокол (как `randomConfigs` фронтенда s-ui), подключён к [inboundId]. */
    fun suiClient(c: Creds, inboundId: Int): JSONObject = JSONObject().put("enable", true).put("name", c.email)
        .put("config", JSONObject()
            .put("vless", JSONObject().put("name", c.email).put("uuid", c.uuid).put("flow", ""))
            .put("vmess", JSONObject().put("name", c.email).put("uuid", c.uuid).put("alterId", 0))
            .put("trojan", JSONObject().put("name", c.email).put("password", c.password))
            .put("shadowsocks", JSONObject().put("name", c.email).put("password", c.ssKey))
            .put("tuic", JSONObject().put("name", c.email).put("uuid", c.uuid).put("password", c.password))
            .put("hysteria2", JSONObject().put("name", c.email).put("password", c.password)))
        .put("inbounds", JSONArray(listOf(inboundId))).put("links", JSONArray()).put("volume", 0).put("expiry", 0)
        .put("up", 0).put("down", 0).put("desc", "Hydra").put("group", "").put("remark", "")

    /** Ссылки клиента s-ui из ответа `api/load`: массив строк или объектов с `uri`. */
    fun suiLinks(client: JSONObject): List<String> {
        val a = client.optJSONArray("links") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.opt(i).let { v -> (v as? JSONObject)?.optString("uri") ?: (v as? String) } }.filter { "://" in it }
    }

    /** Ссылка ведёт на [port]: `host:port` в ссылке или `port` в base64-теле `vmess://` / `vpn://`. */
    fun linkHasPort(l: String, port: Int): Boolean {
        if (":$port" in l.substringBefore("#").substringBefore("?").let { it.substringAfterLast('@', it) }) return true
        if (!l.startsWith("vmess://") && !l.startsWith("vpn://")) return false
        val b = l.substringAfter("://").substringBefore("#").replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
        val t = runCatching { String(java.util.Base64.getDecoder().decode(b)) }.getOrDefault("")
        return ":$port" in t || Regex("\"port\"\\s*:\\s*\"?$port\"?").containsMatchIn(t)
    }

    /** Можно ли создать подключение из приложения: 3x-ui — по токену API, остальные — по SSH. */
    fun canCreate(s: MyServer): Boolean {
        val p = s.panel ?: return false
        return typesFor(p.kind).isNotEmpty() && (if (p.kind == PanelKind.XUI3) p.token.isNotBlank() else s.fingerprint.isNotBlank())
    }

    /** Общий `subId` подключений Hydra в этой панели (стабилен для панели: от адреса и логина). */
    fun subId(p: PanelInstance): String = "hydra" + Installer.sha256((p.baseUrl + "|" + p.username).toByteArray()).take(11)

    /** Учётные данные нового клиента. */
    class Creds(val email: String, val subId: String, val uuid: String, val password: String, val ssKey: String, val wgPriv: String, val wgPub: String)

    fun newCreds(type: XuiConnection, subId: String, r: SecureRandom = SecureRandom()): Creds {
        val kp = X25519.generate(r)
        return Creds("hydra-${type.name.lowercase().replace('_', '-')}-${XuiPanel.random(4, r).lowercase()}", subId, java.util.UUID.randomUUID().toString(),
            XuiPanel.random(20, r), java.util.Base64.getEncoder().encodeToString(ByteArray(32).also { r.nextBytes(it) }), kp.privateKey, kp.publicKey)
    }

    /** Клиент в формате старой 3x-ui (x-ui alireza0). */
    private fun xuiClient(type: XuiConnection, c: Creds): JSONObject {
        val o = JSONObject().put("email", c.email).put("enable", true).put("limitIp", 0).put("totalGB", 0).put("expiryTime", 0)
            .put("tgId", "").put("subId", c.subId).put("reset", 0)
        when (type.protocol) {
            "vless" -> o.put("id", c.uuid).put("flow", "")
            "vmess" -> o.put("id", c.uuid).put("security", "auto")
            "trojan" -> o.put("password", c.password)
            "shadowsocks" -> o.put("method", "").put("password", c.ssKey)
            "hysteria" -> o.put("auth", c.password)
        }
        return o
    }

    /** Инбаунд x-ui сразу с клиентом [c] (у WireGuard — пир с ключами Hydra). */
    fun xuiInbound(type: XuiConnection, port: Int, c: Creds, mask: XuiPanel.RealityMask = XuiPanel.RealityMask.VK,
                   priv: String = "", pub: String = "", cert: XuiConnection.Companion.Cert? = null): JSONObject {
        require(type in XUI) { "${type.title} в x-ui нет" }
        val ib = XuiConnection.inbound(type, port, "Hydra ${type.title}", mask, priv, pub, cert)
        val st = ib.getJSONObject("settings")
        if (type == XuiConnection.WIREGUARD) {
            st.remove("clients"); st.remove("subnetIp"); st.remove("subnetCidr")
            st.put("peers", JSONArray().put(JSONObject().put("privateKey", c.wgPriv).put("publicKey", c.wgPub).put("psk", "")
                .put("allowedIPs", JSONArray(listOf("10.0.0.2/32"))).put("keepAlive", 0)))
        } else st.put("clients", JSONArray().put(xuiClient(type, c)))
        return ib.put("up", 0).put("down", 0)
    }

    /** Конфиг клиента WireGuard x-ui (в подписку x-ui WireGuard не кладёт — конфиг собирает Hydra). */
    fun wireguardConf(serverSecretKey: String, host: String, port: Int, c: Creds): String {
        val serverPub = X25519.publicFromPrivateB64(serverSecretKey) ?: error("Неверный ключ сервера WireGuard")
        return "[Interface]\nPrivateKey = ${c.wgPriv}\nAddress = 10.0.0.2/32\nDNS = 1.1.1.1\nMTU = 1420\n\n" +
            "[Peer]\nPublicKey = $serverPub\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = ${if (':' in host) "[$host]" else host}:$port\nPersistentKeepalive = 25\n"
    }
}
