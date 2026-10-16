package ru.gidravpn.hydra.vpn.core

import ru.gidravpn.hydra.data.model.Engine
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.routing.RouteConfig
import ru.gidravpn.hydra.data.routing.RouteTarget
import ru.gidravpn.hydra.data.routing.viaOf

/**
 * Должен ли собственный трафик Hydra (SSH «Моих серверов», загрузка подписок, обновления) идти ВНУТРИ туннеля (0.8.0-rc3).
 *
 * Раньше приложение всегда исключалось из tun (`addDisallowedApplication`), и сервер, недоступный без VPN
 * (SSH, подписка), не открывался и при включённом VPN. Теперь исключение остаётся только там, где без него
 * получится петля: сокеты подпроцессов (ByeDPI, клиенты OpenFlux/olcRTC) и Xray-процесса принадлежат тому же UID,
 * и `protect()` на них не повесить — их соединения вернулись бы в tun.
 */
object AppTunnelPolicy {

    /**
     * Движки, чьи сокеты защищаются через `VpnService.protect()` ДО первого пакета и при каждой смене сети: sing-box (libbox)
     * и SSTP/L2TP/PPTP. AmneziaWG сюда не входит (0.8.0-rc4): его сокет защищается только после старта ядра — первая попытка
     * рукопожатия уходила в собственный tun (5 с задержки), а после смены сети amneziawg-go создаёт сокеты заново без защиты,
     * и туннель замыкался сам на себя. С AmneziaWG приложение остаётся вне туннеля, как до rc3.
     */
    private val PROTECTED_ENGINES = setOf(Engine.SINGBOX, Engine.USERSPACE)

    /**
     * @param servedByXray обслужит ли профиль на деле ядро без `protect()` своих сокетов — Xray (REALITY, «Xray вместо sing-box»)
     *   или, с 0.8.2-b, amneziawg-go для обычного WireGuard, — хотя по протоколу это sing-box. **Б0 (0.8.2-b):** процесс `:xray` — тот же UID; его сокеты защищены (`protectFd`), но
     *   системный DNS (адрес сервера-домена, DoT «Частного DNS») уходил в собственный туннель → в тот же Xray, которому этот
     *   адрес и нужен: после смены сети (и сразу — с «Частным DNS») подключение вставало. С 0.8.0-rc3 по 0.8.2-a4 приложение
     *   с такими профилями было в туннеле — отсюда «интернет пропадает при переходе между сетями» на Xray.
     * @param lookup профиль по id — чтобы проверить узлы, на которые ссылаются правила маршрутизации.
     * @return true — приложение надо исключить из tun (старое поведение), false — пустить его в туннель.
     */
    fun needsAppBypass(
        profile: ServerProfile,
        cfg: RouteConfig,
        lookup: (Long) -> ServerProfile?,
        servedByXray: (ServerProfile) -> Boolean = { false },
    ): Boolean {
        if (profile.protocol?.engine !in PROTECTED_ENGINES) return true
        if (servedByXray(profile)) return true
        if (cfg.dpi.enabled || viaOf(profile) != null) return true
        val targets = cfg.rules.map { it.target } + cfg.groups.flatMap { it.members }
        if (targets.any { it == RouteTarget.DPI || it == RouteTarget.TGWS }) return true
        // Узлы-подпроцессы (OpenFlux, olcRTC, ByeDPI); неизвестный узел считаем опасным.
        return targets.mapNotNull(RouteTarget::nodeId).any { id ->
            lookup(id)?.let { n -> n.protocol?.engine !in PROTECTED_ENGINES || servedByXray(n) } ?: true
        }
    }
}
