package ru.gidravpn.hydra.data.model

/**
 * MTU tun-интерфейса (Фаза 6c) — и в VpnService.Builder, и в tun-инбаунде
 * sing-box. 9000 — прежнее жёсткое значение: внутри устройства пакеты не
 * режутся, а наружу прокси всё равно шлёт TCP/QUIC со своим MSS. Меньшие
 * значения — для сетей, где крупные пакеты теряются (часть мобильных
 * операторов, туннель поверх туннеля).
 */
enum class MtuPreset(val value: Int, @androidx.annotation.StringRes val labelRes: Int) {
    AUTO(9000, ru.gidravpn.hydra.R.string.mtu_auto),
    MTU_1500(1500, ru.gidravpn.hydra.R.string.mtu_1500),
    MTU_1400(1400, ru.gidravpn.hydra.R.string.mtu_1400),
    MTU_1280(1280, ru.gidravpn.hydra.R.string.mtu_1280);

    companion object {
        fun fromId(id: String?): MtuPreset = entries.firstOrNull { it.name == id } ?: AUTO
    }
}

/**
 * Фрагментация TLS-рукопожатия с прокси-сервером (sing-box 1.12, outbound
 * `tls.record_fragment` / `tls.fragment`) — против DPI, который ищет SNI в
 * одном пакете. Только движок sing-box и только TCP-протоколы с TLS
 * (VLESS/VMess/Trojan): у Hysteria2/TUIC рукопожатие внутри QUIC, а в режиме
 * Xray Core рукопожатие делает сам Xray.
 */
enum class TlsFragmentMode(@androidx.annotation.StringRes val labelRes: Int, @androidx.annotation.StringRes val descriptionRes: Int) {
    OFF(ru.gidravpn.hydra.R.string.geo_off, ru.gidravpn.hydra.R.string.frag_off_desc),
    RECORD(
        ru.gidravpn.hydra.R.string.frag_record,
        ru.gidravpn.hydra.R.string.frag_record_desc
    ),
    TCP(
        ru.gidravpn.hydra.R.string.frag_tcp,
        ru.gidravpn.hydra.R.string.frag_tcp_desc
    );

    companion object {
        fun fromId(id: String?): TlsFragmentMode = entries.firstOrNull { it.name == id } ?: OFF
    }
}

/**
 * IPv6 внутри туннеля (Фаза 8). [BLOCK] — как было всегда: маршрут `::/0` уходит в tun, но
 * IPv6-адреса у tun нет, поэтому приложения не могут отправить IPv6 и откатываются на IPv4 —
 * IPv6 не утекает мимо VPN. [ENABLE] — tun получает ULA-адрес, и IPv6-трафик идёт через
 * прокси (нужен сервер с IPv6-выходом; иначе такие соединения просто не установятся).
 * SSTP/L2TP и AmneziaWG режим не затрагивает: у PPP-моста только IPv4, у AWG адреса задаёт
 * сам конфиг.
 */
enum class Ipv6Mode(@androidx.annotation.StringRes val labelRes: Int, @androidx.annotation.StringRes val descriptionRes: Int) {
    BLOCK(ru.gidravpn.hydra.R.string.ipv6_block, ru.gidravpn.hydra.R.string.ipv6_block_desc),
    ENABLE(ru.gidravpn.hydra.R.string.ipv6_enable, ru.gidravpn.hydra.R.string.ipv6_enable_desc);

    companion object {
        /** ULA-адрес tun под IPv6 — общий для VpnService.Builder и tun-инбаунда sing-box. */
        const val TUN_ADDRESS = "fdfe:dcba:9876::1"
        const val TUN_PREFIX = 126

        fun fromId(id: String?): Ipv6Mode = entries.firstOrNull { it.name == id } ?: BLOCK
    }
}
