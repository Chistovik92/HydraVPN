package ru.gidravpn.hydra.data.subscription

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.gidravpn.hydra.data.subscription.ImportDetector.Result

class OpenFluxLinkTest {

    @Test fun parsesUrlTransport() {
        val p = OpenFluxLink.parse("openflux://yandex?url=https%3A%2F%2Fdocs.yandex.ru%2Fd%2Fabc&key=s3cret&codec=legacy#My%20node")!!
        assertEquals("My node", p.name)
        assertEquals("openflux", p.protocolId)
        assertEquals("yandex", p.transport)
        assertEquals("https://docs.yandex.ru/d/abc", p.address)
        assertEquals("s3cret", p.uuidOrPassword)
        assertEquals("legacy", JSONObject(p.extra).getString("codec"))
    }

    @Test fun onemeNeedsTokenAndUid() {
        assertNull(OpenFluxLink.parse("openflux://oneme?maxToken=t"))
        val p = OpenFluxLink.parse("openflux://oneme?maxToken=t&maxUid=42")!!
        assertEquals("42", JSONObject(p.extra).getString("maxUid"))
        assertEquals("batched", JSONObject(p.extra).getString("codec"))
    }

    @Test fun rejectsUnknownTransportAndMissingUrl() {
        assertNull(OpenFluxLink.parse("openflux://ftp?url=x"))
        assertNull(OpenFluxLink.parse("openflux://mailru"))
        assertTrue(OpenFluxLink.parse("openflux://cupsonline") != null)   // комнаты выдаёт exit-узел сам
        assertNull(OpenFluxLink.parse("vless://a@b:1"))
    }

    @Test fun roundTrip() {
        val src = "openflux://mailru?url=https%3A%2F%2Fcloud.mail.ru%2Fpublic%2FAb%2FCd&key=k#Node"
        assertEquals(OpenFluxLink.parse(src), OpenFluxLink.parse(OpenFluxLink.build(OpenFluxLink.parse(src)!!)))
    }

    @Test fun olcboxDeepLinkIsSubscription() {
        val r = ImportDetector.classify("olcbox://add?url=https%3A%2F%2Fsub.example.com%2Fx%3Ft%3D1") as Result.SubscriptionUrl
        assertEquals("https://sub.example.com/x?t=1", r.url)
    }
}
