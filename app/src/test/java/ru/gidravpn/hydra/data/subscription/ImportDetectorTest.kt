package ru.gidravpn.hydra.data.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.gidravpn.hydra.data.subscription.ImportDetector.Reason
import ru.gidravpn.hydra.data.subscription.ImportDetector.Result

/** Ветки, которые не трогают android.net.Uri (разбор самих ссылок серверов тестируется на устройстве). */
class ImportDetectorTest {

    @Test fun bareHttpsIsSubscription() {
        val r = ImportDetector.classify("  https://sub.example.com/sub/abc123  \n") as Result.SubscriptionUrl
        assertEquals("https://sub.example.com/sub/abc123", r.url)
        assertEquals("sub.example.com", r.nameHint)
    }

    @Test fun fragmentBecomesName() {
        val r = ImportDetector.classify("https://p.example.com/s/x#My%20VPN") as Result.SubscriptionUrl
        assertEquals("https://p.example.com/s/x", r.url)
        assertEquals("My VPN", r.nameHint)
    }

    @Test fun wrapperDeepLinksAreUnwrapped() {
        listOf(
            "sing-box://import-remote-profile?url=https%3A%2F%2Fsub.example.com%2Fa%3Ftoken%3D1#Name",
            "clash://install-config?url=https%3A%2F%2Fsub.example.com%2Fa%3Ftoken%3D1",
            "hiddify://import/https://sub.example.com/a?token=1",
        ).forEach {
            val r = ImportDetector.classify(it) as Result.SubscriptionUrl
            assertEquals(it, "https://sub.example.com/a?token=1", r.url)
        }
    }

    @Test fun jsonIsHonestlyUnsupported() {
        assertEquals(Result.Unsupported(Reason.JSON_CONFIG), ImportDetector.classify("{\"outbounds\": []}"))
    }

    /** 0.8.0-rc3: «[Interface]» начинается с «[» — раньше такой .conf принимали за JSON-массив и отказывали. */
    @Test fun wholeConfIsNotJson() {
        val conf = "[Interface]\nPrivateKey = cAcYDmj/60j51takU8V8aFtdOx1qyOfMKXuF2xyCeEs=\nAddress = 10.8.1.2/32\nJc = 7\n\n" +
            "[Peer]\nPublicKey = abc=\nEndpoint = 203.0.113.5:51820\nAllowedIPs = 0.0.0.0/0\n"
        val r = ImportDetector.classify(conf)
        assertTrue(r.toString(), r != Result.Unsupported(Reason.JSON_CONFIG))
    }

    @Test fun garbageAndEmpty() {
        assertEquals(Result.Empty, ImportDetector.classify("  \n "))
        assertTrue(ImportDetector.classify("hello world") is Result.Unsupported)
    }

    @Test fun urlWithSpacesIsNotASubscription() {
        assertTrue(ImportDetector.classify("https://a.com/x and text") !is Result.SubscriptionUrl)
    }
}
