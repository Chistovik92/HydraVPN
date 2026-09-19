package ru.gidravpn.hydra.data.model

/**
 * Какие ядра включены пользователем (Настройки → Туннель, 0.6.22) и какое из них реально
 * обслужит протокол. Чистая логика — проверяется юнит-тестом.
 *
 * sing-box здесь — ядро для «своих» протоколов (VLESS/VMess/Trojan/SS/Hysteria2/TUIC/WireGuard).
 * Как внутренний мост к tun для Xray, olcRTC и OpenFlux он работает и при выключенном тумблере:
 * это деталь реализации, а не выбор протокола.
 */
data class EngineToggles(
    val singBox: Boolean = true,
    val xray: Boolean = true,
    val amneziaWg: Boolean = true,
    val ppp: Boolean = true,
    val olcRtc: Boolean = true,
    val openFlux: Boolean = true,
    val preferXray: Boolean = false,
) {
    enum class Kind { SINGBOX, XRAY, AWG, PPP, OLCRTC, OPENFLUX }

    /** Кто обслужит протокол; null — ни одно подходящее ядро не включено (или протокол недоступен). */
    fun engineFor(protocol: Protocol?, xrayAvailable: Boolean): Kind? {
        val p = protocol ?: return null
        return when (p.engine) {
            Engine.SINGBOX -> {
                val canXray = p in XRAY_CAPABLE && xray && xrayAvailable
                when {
                    canXray && (preferXray || !singBox) -> Kind.XRAY
                    singBox -> Kind.SINGBOX
                    else -> null
                }
            }
            Engine.XRAY -> if (xray && xrayAvailable) Kind.XRAY else null
            Engine.AWG -> if (amneziaWg) Kind.AWG else null
            Engine.USERSPACE -> if (ppp) Kind.PPP else null
            Engine.OLCRTC -> if (olcRtc) Kind.OLCRTC else null
            Engine.OPENFLUX -> if (openFlux) Kind.OPENFLUX else null
            Engine.WDTT, Engine.UNAVAILABLE -> null
        }
    }

    /** Протокол заблокирован именно выключенным тумблером (а не тем, что он в принципе недоступен). */
    fun isDisabled(protocol: Protocol?, xrayAvailable: Boolean): Boolean {
        val e = protocol?.engine ?: return false
        if (e == Engine.WDTT || e == Engine.UNAVAILABLE) return false
        return engineFor(protocol, xrayAvailable) == null
    }

    companion object {
        /** Ровно то, что умеет XrayConfigBuilder — не Hysteria2/TUIC/WireGuard. */
        val XRAY_CAPABLE = setOf(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS)
    }
}
