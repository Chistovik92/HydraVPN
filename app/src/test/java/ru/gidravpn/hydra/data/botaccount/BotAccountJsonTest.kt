package ru.gidravpn.hydra.data.botaccount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotAccountJsonTest {
    @Test fun tokenIsTakenFromLinkReply() =
        assertEquals("abc", BotAccountJson.token("""{"token":"abc","device_id":"a1b2c3d4"}"""))

    @Test fun errorTextFromBot() {
        assertEquals("код не подошёл или устарел", BotAccountJson.errorText("""{"error":"код не подошёл или устарел"}"""))
        assertEquals("", BotAccountJson.errorText("не json"))
    }

    @Test fun profileParsed() {
        val p = BotAccountJson.profile(
            """{"user_id":"42","username":"ivan","blocked":false,"vpn":{"enabled":true,"state":"active","panels":2}}"""
        )
        assertEquals("ivan", p.username)
        assertEquals("active", p.vpnState)
        assertEquals(2, p.panels)
    }

    @Test fun subscriptionsParsedAndOnlyWorkingOnesImportable() {
        val list = BotAccountJson.subscriptions(
            """{"subscriptions":[
              {"panel":"1","title":"Главный","kind":"3xui","link_kind":"subscription","state":"ok","enabled":true,
               "expire":1900000000,"traffic_limit":10737418240,"traffic_used":500,"url":"https://sub.example/xyz"},
              {"panel":"2","title":"Запасной","link_kind":"subscription","state":"panel_error","enabled":false,"url":""},
              {"panel":"3","title":"Outline","link_kind":"key","state":"ok","enabled":true,"url":"ss://abc"},
              {"panel":"4","title":"Выключена","link_kind":"subscription","state":"ok","enabled":false,"url":"https://x/y"}
            ]}"""
        )
        assertEquals(4, list.size)
        assertEquals(1900000000L, list[0].expire)
        assertEquals(10737418240L, list[0].trafficLimit)
        assertEquals(listOf(true, false, false, false), list.map { it.importable })
    }

    @Test fun emptyOrMissingListIsEmpty() {
        assertTrue(BotAccountJson.subscriptions("""{"subscriptions":[]}""").isEmpty())
        assertTrue(BotAccountJson.subscriptions("{}").isEmpty())
    }

    @Test fun serverAddressIsNormalised() {
        assertEquals("https://radar.example.org", BotAccountJson.normalizeServer(" radar.example.org/ "))
        assertEquals("https://radar.example.org:8443", BotAccountJson.normalizeServer("https://radar.example.org:8443/panel"))
    }

    @Test fun plainHttpOnlyInLocalNetwork() {
        assertNull(BotAccountJson.normalizeServer("http://radar.example.org"))
        assertEquals("http://192.168.1.5:8080", BotAccountJson.normalizeServer("http://192.168.1.5:8080"))
        assertEquals("http://localhost:8080", BotAccountJson.normalizeServer("http://localhost:8080"))
        assertNull(BotAccountJson.normalizeServer("http://localhost:8080", allowHttp = false))
        assertFalse(BotAccountJson.isLocalHost("8.8.8.8"))
        assertTrue(BotAccountJson.isLocalHost("172.20.0.1"))
        assertFalse(BotAccountJson.isLocalHost("172.40.0.1"))
    }

    @Test fun badAddressesAreRejected() {
        assertNull(BotAccountJson.normalizeServer(""))
        assertNull(BotAccountJson.normalizeServer("ftp://radar.example.org"))
        assertNull(BotAccountJson.normalizeServer("https://user:pass@radar.example.org"))
        assertNull(BotAccountJson.normalizeServer("radar example.org"))
    }
}
