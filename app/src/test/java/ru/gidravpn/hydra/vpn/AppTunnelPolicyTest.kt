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

    @Test fun ruleToSubprocessNodeKeepsAppOutside() {
        val cfg = RouteConfig(rules = listOf(RouteRule(RouteKind.SUFFIX, "ru", RouteTarget.node(7))))
        val lookup = { id: Long -> if (id == 7L) p(Protocol.OPENFLUX, id = 7) else null }
        assertTrue(AppTunnelPolicy.needsAppBypass(p(Protocol.VLESS), cfg, lookup))
        assertFalse(AppTunnelPolicy.needsAppBypass(p(Protocol.VLESS), cfg) { id -> if (id == 7L) p(Protocol.TROJAN, id = 7) else null })
        assertTrue("неизвестный узел — осторожно", AppTunnelPolicy.needsAppBypass(p(Protocol.VLESS), cfg, none))
    }
}
