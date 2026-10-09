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

    // «Замороженные» ссылки из share/compat_test.go ядра OpenFlux 0.4.2: обязаны читаться всегда.
    private val frozen = listOf(
        "openflux://v1/TMpBCsIwEAXQu_y1aTCJm4B4EHEhNehAnAmTaUVK7y6ii771W2B65d5ErSOfF9i7FWSMU-vClbhgh0krMh5mrWfvia3oTOU1fNPwW77SXNwoN-K7P6nI87j_cyGE4GKM0aWUkjtsYL2snwAAAP__",
        "openflux://v1/qlbKS8xNVbJS8k3MzNErKlVIK8pMzUtR0lEqKUrMKy7ILyopVrKKrlYqqSwAKctNzMwpKlXSUSotylGyUnJMck5xTXPPMNT3zPLO8c3zLzBSqo2tBQQAAP__",
        "openflux://v1/rI_BaiwhEEV_pSnonWPb7TweCCFkM-QfQha2FhlJj0pZTkaG_vdgAoHssyw4dc-9d4j2gmDgOV0QBER8SxwsIximigIKOkIGAy4RoePhnKjgsFpmpDYUtnnrjy5FxlsHz8y5mGnyyRXZbPR4k1S_zuka8OOx0vbQ7MGH8n7Idd2CG_XTuJzG5WRXBwKYbCw5ERcwL3fglnvD63cWCKi0_YknU0gUuIGZldrFj8mHvvQX8E8J8MF276K0VHKetfxvjketYX_dPwMAAP__",
        "openflux://v1/JMk7DsIwDAbgu_xz1PB--CqIIdgGhqqJbFdqVPXuDMzfCq6iDMKon8IdCa5sGiDs9ofj6Xy53u7lxaJvJISVyVu1cNBjRfSmIPQyiS5ImG0E4RvRnHKWyj78bbA5L9ie2y8AAP__",
        "openflux://v1/PY0xCwIxDEb_S2a94loQNydHN3Eod1EDvaSkaUWO---2HJox7328BTjMCB4exYoi7MA0cE6ilsHfFrBP6ngsKQtH4q4Uje31MkvZO0dsqJXwPXRp2CwXqeJ-lIn46U4qMh9DuzbeQtdf5UwYJ_CH9f5Hki5YsSUWCJ2sXw"
    )

    @Test fun frozenOfficialLinksRead() {
        val cups = OpenFluxLink.parse(frozen[0])!!
        assertEquals("cupsonline", cups.transport); assertEquals("", cups.uuidOrPassword)
        val mail = OpenFluxLink.parse(frozen[1])!!
        assertEquals("Mail.ru friend", mail.name); assertEquals("AbCdEfGh1/IjKlMnOp2", mail.address)
        val home = OpenFluxLink.parse(frozen[2])!!
        assertEquals("Home", home.name); assertEquals("session", home.transport)
        assertEquals("correct horse battery staple", home.uuidOrPassword)
        val doc = "https://docs.yandex.ru/docs/view?url=ya-disk-public%3A%2F%2Fabc"
        val args = OpenFluxArgs.build(home, 10810, "/k")
        assertEquals(listOf("--role", "client", "--inbound", "socks5", "--socks5", "127.0.0.1:10810", "--negotiate",
            "--transports", "vyandex:100,direct:50", "--vyandex-url", doc,
            "--direct-dial", "203.0.113.7:4433", "--session-context", doc,
            "--encryption-key-file", "/k"), args)
        val legacy = OpenFluxLink.parse(frozen[3])!!
        assertEquals("yandex", legacy.transport)
        assertTrue(OpenFluxArgs.build(legacy, 1, "/k").containsAll(listOf("--codec", "legacy", "--transport", "yandex")))
        assertEquals("future", OpenFluxLink.parse(frozen[4])!!.name)
    }

    @Test fun mangledLinkStillReads() {
        val body = frozen[2].removePrefix("openflux://v1/")
        val std = body.replace('-', '+').replace('_', '/')
        val c = OpenFluxShare.decode("  openflux://v1/" + std.substring(0, 40) + "\r\n " + std.substring(40) + "==\n")
        assertEquals(2, c.transports.size)
    }

    @Test fun errorCodesAreTheCoresCodes() {
        assertEquals("not_link", code { OpenFluxShare.decode("https://example.com") })
        assertEquals("unsupported_version", code { OpenFluxShare.decode("openflux://v2/abc") })
        assertEquals("case_changed", code { OpenFluxShare.decode("OPENFLUX://V1/abc") })
        assertEquals("damaged", code { OpenFluxShare.decode(frozen[0].dropLast(30)) })
        assertEquals("damaged", OpenFluxLink.errorCode(frozen[0].dropLast(30)))
        assertNull(OpenFluxLink.parse(frozen[0].dropLast(30)))
        assertNull(OpenFluxLink.errorCode(frozen[0]))
    }

    @Test fun validationMatchesCore() {
        fun t(type: String, url: String = "", dial: String = "") = OpenFluxShare.Transport(type, url, dial = dial)
        fun enc(c: OpenFluxShare.Config) = OpenFluxShare.encode(c)
        val key = "0123456789abcdef"
        assertEquals("no_transports", code { enc(OpenFluxShare.Config(transports = emptyList())) })
        assertEquals("several_need_session", code { enc(OpenFluxShare.Config(transports = listOf(t("yandex", "u"), t("mailru", "m")))) })
        assertEquals("session_secret", code { enc(OpenFluxShare.Config(negotiate = true, secret = "short", transports = listOf(t("yandex", "u")))) })
        assertEquals("short_secret", code { enc(OpenFluxShare.Config(secret = "short", transports = listOf(t("yandex", "u")))) })
        assertEquals("not_shareable", code { enc(OpenFluxShare.Config(transports = listOf(t("oneme")))) })
        assertEquals("unknown_transport", code { enc(OpenFluxShare.Config(transports = listOf(t("ftp")))) })
        assertEquals("direct_no_dial", code { enc(OpenFluxShare.Config(negotiate = true, secret = key, transports = listOf(t("direct")))) })
        assertEquals("direct_needs_session", code { enc(OpenFluxShare.Config(transports = listOf(t("direct", dial = "h:1")))) })
        assertEquals("unknown_codec", code { enc(OpenFluxShare.Config(codec = "x", transports = listOf(t("yandex", "u")))) })
    }

    @Test fun importDetectorReportsBrokenOfficialLink() {
        val r = ImportDetector.classify(frozen[0].dropLast(30)) as Result.OpenFluxError
        assertEquals("damaged", r.code)
        assertTrue(ImportDetector.classify(frozen[2]) is Result.Servers)
    }

    @Test fun streamModeIsRecognisedButNotSupportedYet() {
        val link = OpenFluxShare.encode(OpenFluxShare.Config(mode = "stream", transports = listOf(OpenFluxShare.Transport("mailru", "AbCd"))))
        assertEquals("stream_unsupported", OpenFluxLink.errorCode(link))
        assertNull(OpenFluxLink.parse(link))
    }

    @Test fun exportIsOfficialLinkAndRoundTrips() {
        val home = OpenFluxLink.parse(frozen[2])!!
        val out = OpenFluxLink.build(home)
        assertTrue(out, out.startsWith("openflux://v1/"))
        val back = OpenFluxLink.parse(out)!!
        assertEquals(home.name, back.name); assertEquals(home.uuidOrPassword, back.uuidOrPassword)
        assertEquals(OpenFluxArgs.build(home, 1, "/k"), OpenFluxArgs.build(back, 1, "/k"))
        // Профиль старого вида с достаточным ключом тоже уходит официальной ссылкой.
        val old = OpenFluxLink.parse("openflux://direct?url=10.0.0.5%3A9000&key=0123456789abcdef#Мой")!!
        val oldOut = OpenFluxLink.build(old)
        assertTrue(oldOut.startsWith("openflux://v1/"))
        assertEquals("10.0.0.5:9000", OpenFluxLink.parse(oldOut)!!.address)
    }

    private fun code(f: () -> Unit): String = try { f(); "ok" } catch (e: OpenFluxShare.ShareException) { e.code }
}
