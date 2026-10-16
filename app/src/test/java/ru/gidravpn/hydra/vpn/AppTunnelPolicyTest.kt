package ru.gidravpn.hydra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.gidravpn.hydra.data.dpi.DpiSettings
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.routing.RouteConfig
import ru.gidravpn.hydra.data.routing.RouteKind
import ru.gidravpn.hydra.data.routing.RouteRule
import ru.gidravpn.hydra.data.routing.RouteTarget
import ru.gidravpn.hydra.vpn.core.AppTunnelPolicy

class AppTunnelPolicyTest {

    private fun p(protocol: Protocol, id: Long = 1, extra: String = "{}") =
        ServerProfile(id = id, name = "n", protocolId = protocol.id, address = "203.0.113.1", port = 443, extra = extra)

    private val none: (Long) -> ServerProfile? = { null }

    @Test fun singBoxServerGoesThroughTunnel() {
        assertFalse(AppTunnelPolicy.needsAppBypass(p(Protocol.VLESS), RouteConfig(), none))
        assertFalse(AppTunnelPolicy.needsAppBypass(p(Protocol.SSTP), RouteConfig(), none))
    }

    /** 0.8.0-rc4: сокет amneziawg-go защищается только после старта и не защищается после смены сети — приложение вне туннеля. */
    @Test fun amneziaKeepsAppOutside() {
        assertTrue(AppTunnelPolicy.needsAppBypass(p(Protocol.AMNEZIAWG), RouteConfig(), none))
    }

    @Test fun subprocessEnginesKeepAppOutside() {
        assertTrue(AppTunnelPolicy.needsAppBypass(p(Protocol.OPENFLUX), RouteConfig(), none))
        assertTrue(AppTunnelPolicy.needsAppBypass(p(Protocol.OLCRTC), RouteConfig(), none))
    }

    @Test fun dpiAndTelegramWsKeepAppOutside() {
        assertTrue(AppTunnelPolicy.needsAppBypass(p(Protocol.VLESS), RouteConfig(dpi = DpiSettings(enabled = true)), none))
        val tg = RouteConfig(rules = listOf(RouteRule(RouteKind.CIDR, "91.108.4.0/22", RouteTarget.TGWS)))
        assertTrue(AppTunnelPolicy.needsAppBypass(p(Protocol.VLESS), tg, none))
        assertTrue(AppTunnelPolicy.needsAppBypass(p(Protocol.VLESS, extra = "{\"via\":\"dpi\"}"), RouteConfig(), none))
    }

    /**
     * Б0 (0.8.2-b): VLESS по протоколу — sing-box, но REALITY (или «Xray вместо sing-box») обслуживает процесс `:xray`.
     * Его системный DNS из туннеля попадал в тот же Xray — после смены сети подключение вставало. Такие профили — вне туннеля.
     */
    @Test fun xrayServedProfileKeepsAppOutside() {
        val toggles = ru.gidravpn.hydra.data.model.EngineToggles()
        val byXray = { s: ServerProfile -> toggles.engineFor(s, true) == ru.gidravpn.hydra.data.model.EngineToggles.Kind.XRAY }
        val reality = p(Protocol.VLESS).copy(security = "reality")
        assertTrue(AppTunnelPolicy.needsAppBypass(reality, RouteConfig(), none, byXray))
        assertFalse(AppTunnelPolicy.needsAppBypass(p(Protocol.VLESS).copy(security = "tls"), RouteConfig(), none, byXray))
        // Xray выключен — REALITY идёт через sing-box (libbox с правкой, protect) — можно в туннель.
        val sbOnly = ru.gidravpn.hydra.data.model.EngineToggles(xray = false)
        assertFalse(AppTunnelPolicy.needsAppBypass(reality, RouteConfig(), none) { s -> sbOnly.engineFor(s, true) == ru.gidravpn.hydra.data.model.EngineToggles.Kind.XRAY })
        // «Использовать Xray вместо sing-box» — любой VLESS через Xray.
        val prefer = ru.gidravpn.hydra.data.model.EngineToggles(preferXray = true)
        assertTrue(AppTunnelPolicy.needsAppBypass(p(Protocol.VLESS), RouteConfig(), none) { s -> prefer.engineFor(s, true) == ru.gidravpn.hydra.data.model.EngineToggles.Kind.XRAY })
        // Узел правила маршрутизации, который обслужит Xray, — тоже.
        val cfg = RouteConfig(rules = listOf(RouteRule(RouteKind.SUFFIX, "ru", RouteTarget.node(7))))
        assertTrue(AppTunnelPolicy.needsAppBypass(p(Protocol.TROJAN), cfg, { id -> if (id == 7L) reality.copy(id = 7) else null }, byXray))
    }

    /** 0.8.2-b: обычный WireGuard через amneziawg-go — его сокеты не защищены (урок rc4) — приложение вне туннеля. */
    @Test fun wireGuardViaAmneziaWgKeepsAppOutside() {
        val t = ru.gidravpn.hydra.data.model.EngineToggles()
        val unprotected = { s: ServerProfile -> t.engineFor(s, true) in setOf(ru.gidravpn.hydra.data.model.EngineToggles.Kind.XRAY, ru.gidravpn.hydra.data.model.EngineToggles.Kind.AWG) }
        assertTrue(AppTunnelPolicy.needsAppBypass(p(Protocol.WIREGUARD), RouteConfig(), none, unprotected))
        val sb = ru.gidravpn.hydra.data.model.EngineToggles(amneziaWg = false)
        assertFalse(AppTunnelPolicy.needsAppBypass(p(Protocol.WIREGUARD), RouteConfig(), none) { s -> sb.engineFor(s, true) in setOf(ru.gidravpn.hydra.data.model.EngineToggles.Kind.XRAY, ru.gidravpn.hydra.data.model.EngineToggles.Kind.AWG) })
    }

    @Test fun ruleToSubprocessNodeKeepsAppOutside() {
        val cfg = RouteConfig(rules = listOf(RouteRule(RouteKind.SUFFIX, "ru", RouteTarget.node(7))))
        val lookup = { id: Long -> if (id == 7L) p(Protocol.OPENFLUX, id = 7) else null }
        assertTrue(AppTunnelPolicy.needsAppBypass(p(Protocol.VLESS), cfg, lookup))
        assertFalse(AppTunnelPolicy.needsAppBypass(p(Protocol.VLESS), cfg, { id -> if (id == 7L) p(Protocol.TROJAN, id = 7) else null }))
        assertTrue("неизвестный узел — осторожно", AppTunnelPolicy.needsAppBypass(p(Protocol.VLESS), cfg, none))
    }
}
