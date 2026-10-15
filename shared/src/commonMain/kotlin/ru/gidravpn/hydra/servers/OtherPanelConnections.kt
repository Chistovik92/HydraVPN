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
     * Что Hydra создаёт в x-ui v1.14.0 — проверено трафиком на стенде (0.8.2-a2). AmneziaWG и TUIC в x-ui нет; Hysteria 2 x-ui
     * принимает, но на стенде клиент не дождался рукопожатия (своя версия Xray) — пока не предлагается, есть в 3x-ui.
     */
    val XUI = listOf(XuiConnection.VLESS_GRPC_REALITY, XuiConnection.VLESS_XHTTP_REALITY, XuiConnection.VLESS_TCP_REALITY, XuiConnection.TROJAN_REALITY,
        XuiConnection.VMESS_WS, XuiConnection.SHADOWSOCKS_2022, XuiConnection.WIREGUARD)

    /** Какие подключения Hydra умеет создавать в панели этого вида (пусто — только на сайте панели). */
    fun typesFor(kind: PanelKind): List<XuiConnection> = when (kind) {
        PanelKind.XUI3 -> XuiConnection.entries
        PanelKind.XUI -> XUI
        PanelKind.SUI -> emptyList()
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
