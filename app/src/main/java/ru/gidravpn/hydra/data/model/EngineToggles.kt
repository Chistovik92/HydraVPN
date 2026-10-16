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
    enum class Kind { SINGBOX, XRAY, AWG, PPP, OLCRTC, OPENFLUX, BYEDPI }

    /** Кто обслужит протокол; null — ни одно подходящее ядро не включено (или протокол недоступен). */
    fun engineFor(protocol: Protocol?, xrayAvailable: Boolean): Kind? {
        val p = protocol ?: return null
        return when (p.engine) {
            Engine.SINGBOX -> {
                val canXray = p in XRAY_CAPABLE && xray && xrayAvailable
                when {
                    // 0.8.2-b (Б1): обычный WireGuard на Android — ядром amneziawg-go (оно понимает и обычные конфиги); sing-box —
                    // запасной, если тумблер AmneziaWG выключен. Через sing-box WG на Android качал ~5 КБ/с (эмулятор, x-ui и 3x-ui),
                    // тот же сервер через amneziawg-go — 10 МБ за минуту.
                    p == Protocol.WIREGUARD && amneziaWg -> Kind.AWG
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
            Engine.BYEDPI -> Kind.BYEDPI  // отдельного тумблера нет: это обход DPI, а не VPN-протокол
            Engine.WDTT, Engine.UNAVAILABLE -> null
        }
    }

    /**
     * Кто обслужит этот профиль (0.8.1-rc2). Как [engineFor] по протоколу, плюс: **REALITY — через Xray**, если он включён и
     * есть в сборке. Сервер Xray-core ≥ 26.9.8 (его ставит 3x-ui v3.10, проверено на стенде: Xray 26.10.10) требует в ClientHello
     * гибридный ключ X25519MLKEM768, а sing-box (все версии, в т. ч. 1.12.25 приложения) его не шлёт — сервер молча отдаёт сайт
     * маскировки, и подключение «подключено, 12 КБ и тишина» (sing-box: «reality verification failed»; SagerNet/sing-box#4520).
     * Xray-core Hydra (26.9.30, с 0.8.2-b — 26.10.10) этот ключ шлёт — на стенде через тот же инбаунд 1,9 МБ за 1,3 с.
     */
    fun engineFor(profile: ServerProfile, xrayAvailable: Boolean): Kind? {
        val k = engineFor(profile.protocol, xrayAvailable)
        val reality = profile.security.equals("reality", true) && profile.protocol in XRAY_CAPABLE
        return if (k == Kind.SINGBOX && reality && xray && xrayAvailable) Kind.XRAY else k
    }

    /**
     * A5 (0.8.2-a4): Xray выбран именно из-за REALITY (без REALITY профиль ушёл бы в sing-box) — на экране «Xray (REALITY)»,
     * чтобы было видно, каким ядром и почему идёт подключение.
     */
    fun isRealityViaXray(profile: ServerProfile, xrayAvailable: Boolean): Boolean =
        isReality(profile) && engineFor(profile.protocol, xrayAvailable) == Kind.SINGBOX && engineFor(profile, xrayAvailable) == Kind.XRAY

    /**
     * A5: профиль с REALITY пойдёт через sing-box (Xray выключен или его нет в сборке) — новые серверы Xray (3x-ui v3.10 и
     * свежие панели) такое подключение молча не пустят (SagerNet/sing-box#4520). Показывается предупреждение.
     */
    fun realityWithoutXray(profile: ServerProfile, xrayAvailable: Boolean): Boolean =
        isReality(profile) && engineFor(profile, xrayAvailable) == Kind.SINGBOX

    private fun isReality(profile: ServerProfile) =
        profile.security.equals("reality", true) && profile.protocol in XRAY_CAPABLE

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
