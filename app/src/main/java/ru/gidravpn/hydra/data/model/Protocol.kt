package ru.gidravpn.hydra.data.model

/**
 * Семейства протоколов, поддерживаемые клиентом.
 *
 * SSTP и L2TP — userspace-реализации на Kotlin (PPP-стек vpn/ppp),
 * не требуют нативных .aar. PPTP честно недоступен (GRE → root;
 * стек удалён из Android 12/13) — см. docs/PROTOCOLS.md.
 *
 * Ознакомительные/экспериментальные протоколы ([WDTT], [OLCRTC])
 * помечены [beta] = true и отображаются в UI с плашкой BETA.
 */
enum class Protocol(
    val id: String,
    val displayName: String,
    val engine: Engine,
    val beta: Boolean = false,
    val shortCode: String = id.take(3).uppercase(),
) {
    SSTP      ("sstp",   "SSTP (TLS/PPP)",           Engine.USERSPACE),
    L2TP      ("l2tp",   "L2TP (PPP/UDP)",           Engine.USERSPACE),
    PPTP      ("pptp",   "PPTP (недоступно)",        Engine.UNAVAILABLE),
    VLESS     ("vless",  "VLESS (Xray/sing-box)",    Engine.SINGBOX, shortCode = "VL"),
    VMESS     ("vmess",  "VMess",                    Engine.SINGBOX, shortCode = "VM"),
    TROJAN    ("trojan", "Trojan",                   Engine.SINGBOX, shortCode = "TR"),
    SHADOWSOCKS("ss",    "Shadowsocks",              Engine.SINGBOX, shortCode = "SS"),
    HYSTERIA2 ("hysteria2", "Hysteria2",             Engine.SINGBOX, shortCode = "HY2"),
    TUIC      ("tuic",   "TUIC v5",                  Engine.SINGBOX, shortCode = "TUIC"),
    WIREGUARD ("wireguard", "WireGuard",             Engine.SINGBOX, shortCode = "WG"),
    AMNEZIAWG ("awg",    "AmneziaWG",                Engine.AWG,     shortCode = "AWG"),
    WDTT      ("wdtt",   "WDTT (WG over TURN ВК)",   Engine.WDTT,  beta = true),
    OLCRTC    ("olcrtc", "olcRTC (TCP over WebRTC)", Engine.OLCRTC, beta = true);

    companion object {
        fun fromId(id: String): Protocol? = entries.firstOrNull { it.id == id }
        fun fromScheme(scheme: String): Protocol? = when (scheme.lowercase()) {
            "sstp" -> SSTP
            "l2tp" -> L2TP
            "pptp" -> PPTP
            "vless" -> VLESS
            "vmess" -> VMESS
            "trojan" -> TROJAN
            "ss", "shadowsocks" -> SHADOWSOCKS
            "hysteria2", "hy2" -> HYSTERIA2
            "tuic" -> TUIC
            "wireguard", "wg" -> WIREGUARD
            "awg", "amnezia" -> AMNEZIAWG
            "wdtt" -> WDTT
            "olcrtc" -> OLCRTC
            else -> null
        }
    }
}

/**
 * Нативный движок, который будет обслуживать соединение.
 *  - [SINGBOX] — sing-box (libbox.aar): proxy-протоколы + WireGuard;
 *  - [XRAY] — Xray-core (libXray.aar + tun2socks);
 *  - [AWG] — amneziawg-go.aar (обфусцированный WireGuard);
 *  - [USERSPACE] — чистый Kotlin (PPP-стек): SSTP, L2TP;
 *  - [WDTT] — нативный libclient.so (WG через TURN, VK-auth) — beta;
 *  - [OLCRTC] — gomobile olcrtc.aar + tun2socks (TCP over WebRTC) — beta;
 *  - [UNAVAILABLE] — протокол невозможен на Android (PPTP/GRE).
 */
enum class Engine { SINGBOX, XRAY, AWG, USERSPACE, WDTT, OLCRTC, UNAVAILABLE }
