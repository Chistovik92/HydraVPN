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
