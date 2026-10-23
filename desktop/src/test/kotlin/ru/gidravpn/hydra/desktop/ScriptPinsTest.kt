package ru.gidravpn.hydra.desktop

import ru.gidravpn.hydra.servers.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.8.2-rc2 (R1): установщики панелей закреплены «тегом + SHA-256»; смена тега без нового хеша ловится здесь. */
class ScriptPinsTest {
    @Test fun everyPinnedTagHasItsHash() {
        assertNotNull(ScriptPins.sha256("MHSanaei/3x-ui", XuiPanel.latestTag(), "install.sh"), "3x-ui ${XuiPanel.latestTag()}")
        assertNotNull(ScriptPins.sha256("alireza0/s-ui", OtherPanels.latestTag(OtherPanels.SUI), "install.sh"), "s-ui")
        assertNotNull(ScriptPins.sha256("alireza0/x-ui", OtherPanels.latestTag(OtherPanels.XUI), "install.sh"), "x-ui")
        for (f in listOf("pasarguard.sh", "pg-node.sh")) assertNotNull(ScriptPins.sha256("PasarGuard/scripts", PasarGuardPanel.SCRIPTS_TAG, f), f)
        for (f in listOf("docker-compose-prod.yml", ".env.sample")) assertNotNull(ScriptPins.sha256("remnawave/backend", RemnawavePanel.TAG, f), f)
    }

    @Test fun installStepsCheckTheHashBeforeRunning() {
        val x = XuiPanel.installSteps(XuiPanel.InstallParams(XuiPanel.latestTag()), false, "none")
        val fetch = x.first { it.id == "xui-fetch" }.command!!
        assertTrue("sha256sum -c" in fetch && ScriptPins.sha256("MHSanaei/3x-ui", XuiPanel.latestTag(), "install.sh")!! in fetch)
        val s = OtherPanels.installSteps(OtherPanels.SUI, OtherPanels.Params(OtherPanels.latestTag(OtherPanels.SUI)), false, "none")
        assertTrue("sha256sum -c" in s.first { it.id == "panel-fetch" }.command!!)
        assertEquals(3, PasarGuardPanel.installSteps("key").first { it.id == "pg-fetch" }.command!!.split("sha256sum -c").size)
        assertEquals(3, RemnawavePanel.installSteps("203.0.113.5").first { it.id == "rw-fetch" }.command!!.split("sha256sum -c").size)
    }

    @Test fun unknownTagHasNoPinAndNoCheck() {
        assertNull(ScriptPins.sha256("MHSanaei/3x-ui", "v0.0.1", "install.sh"))
        assertEquals("", ScriptPins.verify("MHSanaei/3x-ui", "v0.0.1", "install.sh", "/tmp/x"))
    }
}
