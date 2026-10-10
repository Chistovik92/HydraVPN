package ru.gidravpn.hydra.desktop

import ru.gidravpn.hydra.data.dpi.CustomSiteList
import ru.gidravpn.hydra.data.dpi.DpiArgs
import ru.gidravpn.hydra.data.dpi.DpiProbeSettings
import ru.gidravpn.hydra.data.dpi.DpiSettings
import ru.gidravpn.hydra.data.dpi.DpiStrategies
import ru.gidravpn.hydra.data.routing.RouteConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DpiProbeSettingsTest {
    @Test fun `settings survive a json round trip`() {
        val ps = DpiProbeSettings(
            delaySec = 3, requests = 3, parallel = 8, timeoutSec = 6, groups = setOf("discord", "mine"),
            custom = listOf(CustomSiteList("mine", listOf("a.example", "b.example"))),
            customStrategiesOn = true, customStrategies = "-o1 -a1\n# comment\n-d1 -a1\n-o1 -a1",
        )
        val cfg = RouteConfig(dpi = DpiSettings(enabled = true, fakeSni = "ya.ru", probe = ps))
        val back = RouteConfig.fromJson(cfg.toJson()).dpi
        assertEquals("ya.ru", back.fakeSni)
        assertEquals(ps, back.probe)
        assertEquals(listOf("-o1 -a1", "-d1 -a1"), back.probe.strategyLines())
    }

    @Test fun `old config without probe keeps the defaults`() {
        val back = RouteConfig.fromJson("""{"dpi":{"enabled":true,"strategy":"-o 2","port":10880}}""").dpi
        assertEquals(DpiProbeSettings(), back.probe)
        assertEquals(DpiStrategies.FAKE_SNI, back.fakeSni)
    }

    @Test fun `values are clamped to sane ranges`() {
        val back = RouteConfig.fromJson("""{"dpi":{"probe":{"delay":999,"requests":0,"parallel":-4,"timeout":0}}}""").dpi.probe
        assertEquals(30, back.delaySec); assertEquals(1, back.requests); assertEquals(1, back.parallel); assertEquals(1, back.timeoutSec)
    }

    @Test fun `selected sites follow the built-in order then custom lists`() {
        val ps = DpiProbeSettings(groups = setOf("youtube", "cloudflare", "mine", "gone"), custom = listOf(CustomSiteList("mine", listOf("x.y"))))
        assertEquals(listOf("cloudflare", "youtube", "mine"), ps.selectedSites().keys.toList())
        assertTrue(DpiStrategies.SITES.keys.containsAll(DpiStrategies.GROUP_ORDER))
    }

    @Test fun `fake sni is substituted into strategies`() {
        assertTrue("ya.ru" in DpiArgs.build("-n {sni} -Qr", 1080, sni = "ya.ru"))
        assertTrue(DpiStrategies.FAKE_SNI in DpiArgs.build("-n {sni} -Qr", 1080))
        assertTrue(DpiStrategies.FAKE_SNI in DpiArgs.build("-n {sni} -Qr", 1080, sni = "  "))
    }
}
