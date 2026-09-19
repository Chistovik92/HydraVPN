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
}
