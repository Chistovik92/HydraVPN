package ru.gidravpn.hydra.data.subscription

import org.json.JSONObject
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile

/**
 * Ручное добавление сервера (0.8.0-rc3). До этого форма знала только имя, адрес и порт — для любого протокола, которому нужен
 * ключ, пароль или конфиг (то есть почти для всех), получался профиль без учётных данных, который не подключался.
 * Теперь у каждого протокола свои поля; значения те же, что получаются из ссылок ([LinkParser]).
 */
object ManualServer {

    /** Как показывать форму для протокола. */
    enum class Kind {
        /** Адрес, порт и поля секрета (UUID / пароль / пользователь). */
        CREDENTIALS,
        /** Вставить `.conf` целиком (WireGuard, AmneziaWG). */
        CONF,
        /** Нет ручной формы — только ссылка/подписка через «Импорт». */
        LINK_ONLY,
    }

    fun kind(p: Protocol): Kind = when (p) {
        Protocol.WIREGUARD, Protocol.AMNEZIAWG -> Kind.CONF
        Protocol.OLCRTC, Protocol.OPENFLUX, Protocol.BYEDPI, Protocol.WDTT -> Kind.LINK_ONLY
        else -> Kind.CREDENTIALS
    }

    fun defaultPort(p: Protocol): Int = when (p) {
        Protocol.SHADOWSOCKS -> 8388
        Protocol.SSTP -> 443
        Protocol.L2TP -> 1701
        Protocol.PPTP -> 1723
        else -> 443
    }

    /** Нужен ли пользователь (PPP-семейство) / SNI / метод шифрования / второй секрет (пароль TUIC). */
    fun needsUser(p: Protocol) = p == Protocol.SSTP || p == Protocol.L2TP || p == Protocol.PPTP
    fun needsSni(p: Protocol) = p == Protocol.VLESS || p == Protocol.VMESS || p == Protocol.TROJAN || p == Protocol.HYSTERIA2 || p == Protocol.TUIC
    fun needsMethod(p: Protocol) = p == Protocol.SHADOWSOCKS
    fun needsSecret2(p: Protocol) = p == Protocol.TUIC

    const val DEFAULT_METHOD = "aes-256-gcm"

    /**
     * Профиль из полей формы или null, если не хватает обязательного (адрес; секрет — всем, кроме тех, у кого его нет).
     * [secret] — UUID (VLESS/VMess/TUIC), пароль (остальные) или пароль пользователя (SSTP/L2TP/PPTP); [secret2] — пароль TUIC;
     * [user] — имя пользователя PPP; [method] — шифр Shadowsocks.
     */
    fun build(
        protocol: Protocol, name: String, address: String, port: Int,
        secret: String = "", secret2: String = "", user: String = "", sni: String = "", method: String = "",
    ): ServerProfile? {
        val host = address.trim()
        if (host.isEmpty() || kind(protocol) != Kind.CREDENTIALS) return null
        val s = secret.trim()
        if (s.isEmpty() && protocol != Protocol.SSTP && protocol != Protocol.L2TP && protocol != Protocol.PPTP) return null
        val tls = sni.trim().isNotEmpty()
        val extra = JSONObject()
        var security = "none"
        when (protocol) {
            Protocol.VLESS, Protocol.VMESS -> security = if (tls) "tls" else "none"
            Protocol.TROJAN, Protocol.HYSTERIA2, Protocol.TUIC -> security = "tls"
            Protocol.SHADOWSOCKS -> extra.put("method", method.trim().ifEmpty { DEFAULT_METHOD })
            Protocol.SSTP, Protocol.L2TP, Protocol.PPTP -> extra.put("username", user.trim())
            else -> Unit
        }
        if (protocol == Protocol.VMESS) extra.put("aid", 0)
        if (protocol == Protocol.TUIC) { extra.put("password", secret2); extra.put("congestion_control", "bbr") }
        return ServerProfile(
            name = name.ifBlank { "${protocol.displayName.substringBefore(" (")} $host" },
            protocolId = protocol.id,
            address = host,
            port = port.takeIf { it in 1..65535 } ?: defaultPort(protocol),
            uuidOrPassword = s,
            sni = sni.trim(),
            security = security,
            alpn = if (protocol == Protocol.TUIC) "h3" else "",
            extra = extra.toString(),
        )
    }
}
