package ru.gidravpn.hydra.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test fun versionOrdering() {
        assertTrue(UpdateChecker.isNewer("0.6.23", "0.6.22.2"))
        assertTrue(UpdateChecker.isNewer("0.7.0", "0.6.23"))
        assertTrue(UpdateChecker.isNewer("0.6.22.2", "0.6.22"))
        assertFalse(UpdateChecker.isNewer("0.6.22", "0.6.22.0"))
        assertFalse(UpdateChecker.isNewer("0.6.22.1", "0.6.22.2"))
        assertFalse(UpdateChecker.isNewer("0.6.23", "0.6.23-stub"))
    }

    @Test fun parsesGithubRelease() {
        val r = UpdateChecker.parse(
            """{"tag_name":"v0.6.23","html_url":"https://github.com/x/y/releases/tag/v0.6.23",
               "assets":[{"name":"Hydra-stub-0.6.23.apk","browser_download_url":"https://s"},
                         {"name":"Hydra-full-0.6.23.apk","browser_download_url":"https://f"}]}"""
        )
        assertEquals("0.6.23", r.version)
        assertEquals("https://f", r.apkUrl)
    }
}
