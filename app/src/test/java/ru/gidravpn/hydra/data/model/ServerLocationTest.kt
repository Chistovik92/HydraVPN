package ru.gidravpn.hydra.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerLocationTest {
    private fun p(name: String, flag: String = "🌐") =
        ServerProfile(name = name, protocolId = "vless", address = "1.2.3.4", port = 443, flag = flag)

    private val names = mapOf("de" to "Германия", "nl" to "Нидерланды")
    private fun label(s: ServerProfile) = ServerLocation.label(s) { names[it] ?: it }

    @Test fun flagFromNameGivesCountryAndName() =
        assertEquals("🇩🇪 Германия · Frankfurt-1", label(p("🇩🇪 Frankfurt-1")))

    @Test fun nameEqualToCountryIsNotRepeated() =
        assertEquals("🇳🇱 Нидерланды", label(p("🇳🇱 Нидерланды")))

    @Test fun flagFieldIsFallback() =
        assertEquals("🇩🇪 Германия · DE-1", label(p("DE-1", flag = "🇩🇪")))

    @Test fun noFlagMeansPlainName() = assertEquals("My server", label(p("My server")))

    @Test fun blankNameFallsBackToAddress() = assertEquals("🇩🇪 Германия · 1.2.3.4", label(p("🇩🇪 ")))

    @Test fun isoCode() {
        assertEquals("de", ServerLocation.isoCode("🇩🇪"))
        assertNull(ServerLocation.isoCode("🌐"))
    }
}
