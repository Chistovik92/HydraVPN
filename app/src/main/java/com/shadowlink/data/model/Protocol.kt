package com.shadowlink.data.model

/**
 * Семейства протоколов, поддерживаемые клиентом.
 *
 * Важно: пункт [L2TP_IPSEC] в интерфейсе оставлен ради совместимости с макетом,
 * но фактически обслуживается движком IKEv2/IPsec (VpnManager), т.к. Android 13+
 * полностью удалил нативный стек L2TP (см. docs/PROTOCOLS.md).
 */
enum class Protocol(val id: String, val displayName: String, val engine: Engine) {
    L2TP_IPSEC("l2tp",   "L2TP/IPsec (PSK)",         Engine.IKEV2),
    VLESS     ("vless",  "VLESS (Xray/sing-box)",    Engine.SINGBOX),
    VMESS     ("vmess",  "VMess",                    Engine.SINGBOX),
    TROJAN    ("trojan", "Trojan",                   Engine.SINGBOX),
    SHADOWSOCKS("ss",    "Shadowsocks",              Engine.SINGBOX),
    HYSTERIA2 ("hysteria2", "Hysteria2",             Engine.SINGBOX),
    TUIC      ("tuic",   "TUIC v5",                  Engine.SINGBOX);

    companion object {
        fun fromId(id: String): Protocol? = entries.firstOrNull { it.id == id }
        fun fromScheme(scheme: String): Protocol? = when (scheme.lowercase()) {
            "vless" -> VLESS
            "vmess" -> VMESS
            "trojan" -> TROJAN
            "ss", "shadowsocks" -> SHADOWSOCKS
            "hysteria2", "hy2" -> HYSTERIA2
            "tuic" -> TUIC
            else -> null
        }
    }
}

/** Нативный движок, который будет обслуживать соединение. */
enum class Engine { SINGBOX, XRAY, IKEV2 }
