package ru.gidravpn.hydra.desktop

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.gidravpn.hydra.desktop.core.Updates
import ru.gidravpn.hydra.router.RouterSection
import ru.gidravpn.hydra.update.UpdateFeed

class UpdateAndRouterTest {
    private val sha = "a".repeat(64)
    private val json = """
        {"tag_name":"v0.7.1","html_url":"https://github.com/Chistovik92/HydraVPN/releases/tag/v0.7.1","assets":[
          {"name":"Hydra-full-0.7.1.apk","size":50,"digest":"sha256:$sha","browser_download_url":"https://github.com/Chistovik92/HydraVPN/releases/download/v0.7.1/Hydra-full-0.7.1.apk"},
          {"name":"Hydra-stub-0.7.1.apk","size":5,"browser_download_url":"https://github.com/x/Hydra-stub-0.7.1.apk"},
          {"name":"Hydra-desktop-0.7.1-windows-x64.msi","size":9,"digest":"sha256:$sha","browser_download_url":"https://github.com/x/w.msi"},
          {"name":"Hydra-desktop-0.7.1-windows-x64.exe","size":9,"browser_download_url":"https://github.com/x/w.exe"},
          {"name":"Hydra-desktop-0.7.1-windows-x64-portable.zip","size":9,"browser_download_url":"https://github.com/x/w.zip"},
          {"name":"Hydra-desktop-0.7.1-linux-arm64.deb","size":9,"browser_download_url":"https://github.com/x/l.deb"},
          {"name":"Hydra-desktop-0.7.1-macos-arm64.dmg","size":9,"browser_download_url":"https://github.com/x/m.dmg"},
          {"name":"evil.msi","size":9,"browser_download_url":"https://evil.example/evil.msi"}]}
    """.trimIndent()

    @Test fun parsesReleaseAndDigest() {
        val info = UpdateFeed.parse(json)
        assertEquals("0.7.1", info.version)
        assertEquals(7, info.assets.size)                       // чужой хост отброшен
        assertEquals(sha, UpdateFeed.pickAndroid(info, stub = false)?.sha256)
        assertEquals("Hydra-stub-0.7.1.apk", UpdateFeed.pickAndroid(info, stub = true)?.name)
        assertNull(UpdateFeed.pickAndroid(info, stub = true)?.sha256)
    }

    @Test fun picksDesktopFilePerPlatform() {
        val info = UpdateFeed.parse(json)
        assertEquals("Hydra-desktop-0.7.1-windows-x64.msi", Updates.pick(info, Os.WINDOWS, "x64", Updates.InstallKind.INSTALLED)?.name)
        assertEquals("Hydra-desktop-0.7.1-windows-x64-portable.zip", Updates.pick(info, Os.WINDOWS, "x64", Updates.InstallKind.PORTABLE)?.name)
        assertEquals("Hydra-desktop-0.7.1-linux-arm64.deb", Updates.pick(info, Os.LINUX, "arm64", Updates.InstallKind.DEB)?.name)
        assertEquals("Hydra-desktop-0.7.1-macos-arm64.dmg", Updates.pick(info, Os.MACOS, "arm64", Updates.InstallKind.INSTALLED)?.name)
        assertNull(Updates.pick(info, Os.MACOS, "x64", Updates.InstallKind.INSTALLED))
    }

    @Test fun versionsAndTrustedHosts() {
        assertTrue(UpdateFeed.isNewer("0.7.1", "0.7.0"))
        assertTrue(UpdateFeed.isNewer("0.7.0.1", "0.7.0"))
        assertFalse(UpdateFeed.isNewer("0.7.0", "0.7.0-stub"))
        assertTrue(UpdateFeed.trustedUrl("https://release-assets.githubusercontent.com/x"))
        assertFalse(UpdateFeed.trustedUrl("http://github.com/x"))
        assertFalse(UpdateFeed.trustedUrl("https://github.com.evil.example/x"))
    }

    @Test fun maskedSectionIsNotEditable() {
        fun sec(extra: String) = JSONObject("""{"name":"main","label":"Main","enabled":true,"action":"connection","provider":"singbox"$extra}""")
            .let { RouterSection("main", "Main", true, "connection", "singbox", it) }
        assertTrue(sec("").editable)
        assertTrue(sec(""","selector_proxy_links":[]""").editable)
        assertFalse(sec(""","selector_proxy_links":["********"]""").editable)
        assertFalse(sec(""","outbound_jsons":["********"]""").editable)
    }
}
