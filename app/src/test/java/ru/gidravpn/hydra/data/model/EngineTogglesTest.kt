package ru.gidravpn.hydra.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.gidravpn.hydra.data.model.EngineToggles.Kind

class EngineTogglesTest {
    @Test fun defaultsAllOnSingBoxServesVless() {
        val t = EngineToggles()
        assertEquals(Kind.SINGBOX, t.engineFor(Protocol.VLESS, xrayAvailable = true))
        assertEquals(Kind.AWG, t.engineFor(Protocol.AMNEZIAWG, true))
        assertEquals(Kind.PPP, t.engineFor(Protocol.SSTP, true))
        assertFalse(t.isDisabled(Protocol.VLESS, true))
    }

    @Test fun preferXrayOrSingBoxOff() {
        assertEquals(Kind.XRAY, EngineToggles(preferXray = true).engineFor(Protocol.VLESS, true))
        assertEquals(Kind.XRAY, EngineToggles(singBox = false).engineFor(Protocol.TROJAN, true))
        // Hysteria2 Xray не умеет — только sing-box
        assertNull(EngineToggles(singBox = false).engineFor(Protocol.HYSTERIA2, true))
        // Xray не собран — prefer не действует
        assertEquals(Kind.SINGBOX, EngineToggles(preferXray = true).engineFor(Protocol.VLESS, false))
        assertTrue(EngineToggles(singBox = false).isDisabled(Protocol.VLESS, false))
    }

    @Test fun eachToggleBlocksItsProtocols() {
        assertTrue(EngineToggles(amneziaWg = false).isDisabled(Protocol.AMNEZIAWG, true))
        assertTrue(EngineToggles(ppp = false).isDisabled(Protocol.L2TP, true))
        assertTrue(EngineToggles(olcRtc = false).isDisabled(Protocol.OLCRTC, true))
        assertTrue(EngineToggles(openFlux = false).isDisabled(Protocol.OPENFLUX, true))
        // PPTP недоступен в принципе — это не «выключенный тумблер»
        assertFalse(EngineToggles().isDisabled(Protocol.PPTP, true))
    }

    /** 0.8.1-rc2: REALITY — через Xray (Xray-core ≥ 26.9.8 на сервере не пускает sing-box). */
    @Test fun realityGoesToXray() {
        val r = ServerProfile(name = "r", protocolId = Protocol.VLESS.id, address = "203.0.113.5", port = 443, security = "reality", transport = "grpc")
        val tls = r.copy(security = "tls")
        assertEquals(Kind.XRAY, EngineToggles().engineFor(r, true))
        assertEquals(Kind.SINGBOX, EngineToggles().engineFor(tls, true))          // обычный TLS — как раньше, sing-box
        assertEquals(Kind.SINGBOX, EngineToggles().engineFor(r, false))           // Xray не собран (stub) — sing-box
        assertEquals(Kind.SINGBOX, EngineToggles(xray = false).engineFor(r, true)) // Xray выключен пользователем — sing-box
        assertNull(EngineToggles(singBox = false, xray = false).engineFor(r, true))
        assertEquals(Kind.XRAY, EngineToggles(preferXray = true).engineFor(tls, true))
    }

    /** 0.8.2-a4 (A5): видно, почему Xray, и предупреждение, когда REALITY пойдёт через sing-box. */
    @Test fun realityReasonAndWarning() {
        val r = ServerProfile(name = "r", protocolId = Protocol.VLESS.id, address = "203.0.113.5", port = 443, security = "reality", transport = "grpc")
        val tls = r.copy(security = "tls")
        val hy = r.copy(protocolId = Protocol.HYSTERIA2.id)
        assertTrue(EngineToggles().isRealityViaXray(r, true))
        assertFalse(EngineToggles().realityWithoutXray(r, true))
        assertFalse(EngineToggles(preferXray = true).isRealityViaXray(r, true))   // Xray выбран и без REALITY — причина не REALITY
        assertFalse(EngineToggles(singBox = false).isRealityViaXray(r, true))     // sing-box выключен — Xray и так единственный
        assertFalse(EngineToggles().isRealityViaXray(tls, true))
        assertTrue(EngineToggles(xray = false).realityWithoutXray(r, true))       // Xray выключен — предупреждение
        assertTrue(EngineToggles().realityWithoutXray(r, false))                  // Xray нет в сборке
        assertFalse(EngineToggles(xray = false).realityWithoutXray(tls, true))
        assertFalse(EngineToggles(xray = false, singBox = false).realityWithoutXray(r, true)) // ядро выключено — об этом своё сообщение
        assertFalse(EngineToggles().realityWithoutXray(hy, false))                // не Xray-протокол
    }
}
