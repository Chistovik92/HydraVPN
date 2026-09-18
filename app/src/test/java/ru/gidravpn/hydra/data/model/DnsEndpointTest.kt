package ru.gidravpn.hydra.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsEndpointTest {

    @Test fun bareIpBecomesDoh() {
        val e = DnsEndpoint.parse(" 94.140.14.14 ")!!
        assertEquals(DnsEndpoint("https", "94.140.14.14"), e)
        assertTrue(e.isIp)
        assertEquals("https://94.140.14.14/dns-query", e.toXrayAddress())
    }

    @Test fun bareHostBecomesDoh() {
        val e = DnsEndpoint.parse("dns.example.com")!!
        assertEquals(DnsEndpoint("https", "dns.example.com"), e)
        assertFalse(e.isIp)
    }

    @Test fun dohUrlKeepsPathAndToken() {
        val e = DnsEndpoint.parse("https://dns.example.net/dns-query/7652f1b8235e")!!
        assertEquals(DnsEndpoint("https", "dns.example.net", null, "/dns-query/7652f1b8235e"), e)
        assertEquals("https://dns.example.net/dns-query/7652f1b8235e", e.toXrayAddress())
    }

    @Test fun dohUrlWithPort() {
        assertEquals(
            DnsEndpoint("https", "dns.example.net", 8443, "/q"),
            DnsEndpoint.parse("HTTPS://dns.example.net:8443/q"),
        )
    }

    @Test fun dotHasNoPathAndNoXrayEquivalent() {
        val e = DnsEndpoint.parse("tls://1.1.1.1")!!
        assertEquals(DnsEndpoint("tls", "1.1.1.1"), e)
        assertNull(e.toXrayAddress())
    }

    @Test fun ipv6() {
        assertEquals(DnsEndpoint("https", "2606:4700:4700::1111"), DnsEndpoint.parse("2606:4700:4700::1111"))
        val e = DnsEndpoint.parse("https://[2606:4700:4700::1111]/dns-query")!!
        assertEquals("2606:4700:4700::1111", e.host)
        assertTrue(e.isIp)
    }

    @Test fun garbageIsRejected() {
        listOf("", "   ", "ftp://x.y", "https://", "not a host", "host:port", "https:///path").forEach {
            assertNull("должно быть отклонено: '$it'", DnsEndpoint.parse(it))
        }
    }
}
