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

class AndroidAbiPickTest {
    private fun info(vararg names: String) = ru.gidravpn.hydra.update.ReleaseInfo(
        "0.7.2", "https://github.com/x",
        names.map { ru.gidravpn.hydra.update.ReleaseAsset(it, "https://github.com/x/$it", 1, null) },
    )

    @Test fun picksFirstSupportedAbi() {
        val i = info("Hydra-full-0.7.2-arm64-v8a.apk", "Hydra-full-0.7.2-armeabi-v7a.apk", "Hydra-full-0.7.2-x86_64.apk", "Hydra-stub-0.7.2.apk")
        assertEquals("Hydra-full-0.7.2-arm64-v8a.apk", UpdateFeed.pickAndroid(i, false, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))?.name)
        assertEquals("Hydra-full-0.7.2-armeabi-v7a.apk", UpdateFeed.pickAndroid(i, false, listOf("armeabi-v7a", "armeabi"))?.name)
        assertEquals("Hydra-full-0.7.2-x86_64.apk", UpdateFeed.pickAndroid(i, false, listOf("x86_64", "x86"))?.name)
        assertEquals("Hydra-stub-0.7.2.apk", UpdateFeed.pickAndroid(i, true, listOf("arm64-v8a"))?.name)
    }

    @Test fun oldUniversalReleaseStillWorks() {
        val i = info("Hydra-full-0.7.1.apk", "Hydra-stub-0.7.1.apk")
        assertEquals("Hydra-full-0.7.1.apk", UpdateFeed.pickAndroid(i, false, listOf("arm64-v8a"))?.name)
        assertNull(UpdateFeed.pickAndroid(info("Hydra-full-0.7.2-x86_64.apk"), false, listOf("arm64-v8a")))
    }
}

/** 0.7.5: 32-битные системы — x86-APK и сборки Classic выбираются, не путаясь с 64-битными. */
class Legacy32PickTest {
    private fun info(vararg names: String) = ru.gidravpn.hydra.update.ReleaseInfo(
        "0.7.5", "https://github.com/x",
        names.map { ru.gidravpn.hydra.update.ReleaseAsset(it, "https://github.com/x/$it", 1, null) },
    )

    @Test fun x86ApkIsNotConfusedWithX86_64() {
        val i = info("Hydra-full-0.7.5-x86_64.apk", "Hydra-full-0.7.5-x86.apk", "Hydra-full-0.7.5-armeabi-v7a.apk", "Hydra-stub-0.7.5.apk")
        assertEquals("Hydra-full-0.7.5-x86.apk", UpdateFeed.pickAndroid(i, false, listOf("x86"))?.name)
        assertEquals("Hydra-full-0.7.5-x86_64.apk", UpdateFeed.pickAndroid(i, false, listOf("x86_64", "x86"))?.name)
        // 64-битное Intel-устройство без x86_64-файла получает 32-битный: оно умеет оба
        assertEquals("Hydra-full-0.7.5-x86.apk", UpdateFeed.pickAndroid(info("Hydra-full-0.7.5-x86.apk"), false, listOf("x86_64", "x86"))?.name)
    }

    @Test fun classicAndRegularDesktopFilesDoNotMix() {
        val i = info(
            "Hydra-desktop-0.7.5-windows-x64-portable.zip", "Hydra-desktop-0.7.5-windows-x64-classic.zip",
            "Hydra-desktop-0.7.5-windows-x86-classic.zip", "Hydra-desktop-0.7.5-linux-armv7-classic.tar.gz",
            "Hydra-desktop-0.7.5-linux-x64.tar.gz",
        )
        assertEquals("Hydra-desktop-0.7.5-windows-x64-portable.zip", Updates.pick(i, Os.WINDOWS, "x64", Updates.InstallKind.PORTABLE)?.name)
        assertEquals("Hydra-desktop-0.7.5-windows-x64-classic.zip", Updates.pick(i, Os.WINDOWS, "x64", Updates.InstallKind.PORTABLE, classic = true)?.name)
        assertEquals("Hydra-desktop-0.7.5-windows-x86-classic.zip", Updates.pick(i, Os.WINDOWS, "x86", Updates.InstallKind.PORTABLE, classic = true)?.name)
        assertEquals("Hydra-desktop-0.7.5-linux-armv7-classic.tar.gz", Updates.pick(i, Os.LINUX, "armv7", Updates.InstallKind.ARCHIVE, classic = true)?.name)
        assertNull(Updates.pick(i, Os.WINDOWS, "x86", Updates.InstallKind.PORTABLE))          // обычной сборки для x86 нет
        assertNull(Updates.pick(i, Os.LINUX, "x64", Updates.InstallKind.ARCHIVE, classic = true))
    }

    @Test fun archNames() {
        assertEquals("x86", Platform.archOf("x86")); assertEquals("x86", Platform.archOf("i386")); assertEquals("x86", Platform.archOf("i686"))
        assertEquals("armv7", Platform.archOf("arm")); assertEquals("armv7", Platform.archOf("armv7l"))
        assertEquals("arm64", Platform.archOf("aarch64")); assertEquals("x64", Platform.archOf("amd64")); assertEquals("x64", Platform.archOf("x86_64"))
    }
}
