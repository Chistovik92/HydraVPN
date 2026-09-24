package ru.gidravpn.hydra.vpn.ppp

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun ByteArray.hex(): String = joinToString("") { "%02X".format(it) }

/** Тест-векторы RFC 1320, RFC 2759 §9.2, RFC 3079 §3.5. */
class MsChapV2Test {
    private val authCh = hex("5B5D7C7D7B3F2F3E3C2C602132262628")
    private val peerCh = hex("21402324255E262A28295F2B3A337C7E")

    @Test fun md4() {
        assertEquals("A448017AAF21D8525FC10AE87AA6729D", Md4.digest("abc".toByteArray()).hex())
        assertEquals("31D6CFE0D16AE931B73C59D7E0C089C0", Md4.digest(ByteArray(0)).hex())
    }

    @Test fun rfc2759() {
        assertEquals("44EBBA8D5312B8D611474411F56989AE", MsChapV2.ntPasswordHash("clientPass").hex())
        assertEquals("D02E4386BCE91226", MsChapV2.challengeHash(peerCh, authCh, "User").hex())
        val a = MsChapV2.authenticate("User", "clientPass", authCh, peerCh)
        assertEquals("82309ECD8D708B5EA08FAA3981CD83544233114A3D85D6DF", a.ntResponse.hex())
        assertEquals("S=407A5589115FD0D6209F510FE9C04566932CDA56", a.authenticatorResponse)
        assertEquals("FDECE3717A8C838CB388E527AE3CDD31", a.masterKey.hex())
    }
}

/**
 * «Разговор» с сервером: кадры клиента по RFC. До 0.6.24 здесь было пять ошибок, из-за которых
 * PPP не согласовался бы ни с одним реальным сервером (MRU 4 байта, петля Nak на MS-CHAPv2,
 * пустой Configure-Ack, Response без Value-Size и имени, Echo-Reply без Magic-Number).
 */
class PppSessionTest {
    private val sent = mutableListOf<ByteArray>()
    private var up: String? = null
    private var down: String? = null
    private val session = PppSession(
        userName = "User", password = "clientPass",
        sendFrame = { synchronized(sent) { sent += it } }, onLog = {},
        onUp = { ip, _, _ -> up = ip }, onDown = { down = it },
    )

    @After fun tearDown() = session.close()

    private fun last(): Pair<Int, Ppp.PppControl> {
        val (proto, info) = Ppp.parseFrame(synchronized(sent) { sent.last() })!!
        return proto to Ppp.parseControl(info)
    }

    @Test fun mruIsTwoBytes() {
        session.start()
        val (_, req) = Ppp.parseFrame(sent.first())!!.let { it.first to Ppp.parseControl(it.second) }
        val mru = Ppp.parseOptions(req.data).first { it.type == Ppp.LCP_OPT_MRU }
        assertEquals(2, mru.value.size)
        assertEquals(1400, mru.intValue())
    }

    @Test fun fullNegotiation() {
        session.start()
        val serverOpts = Ppp.encodeOptions(listOf(
            Ppp.optBytes(Ppp.LCP_OPT_AUTH, byteArrayOf(0xC2.toByte(), 0x23, 0x81.toByte())),
            Ppp.optBytes(Ppp.LCP_OPT_MAGIC, byteArrayOf(1, 2, 3, 4)),
        ))
        session.onFrame(Ppp.controlFrame(Ppp.PROTO_LCP, Ppp.CODE_CONF_REQ, 7, serverOpts))
        val (_, ack) = last()
        assertEquals("MS-CHAPv2 не должен получать Nak", Ppp.CODE_CONF_ACK, ack.code)
        assertArrayEquals("Configure-Ack повторяет опции дословно", serverOpts, ack.data)
        session.onFrame(Ppp.controlFrame(Ppp.PROTO_LCP, Ppp.CODE_CONF_ACK, 0, ByteArray(0)))
        assertEquals(PppSession.Phase.AUTH, session.phase)

        session.onFrame(Ppp.controlFrame(Ppp.PROTO_CHAP, Ppp.CHAP_CODE_CHALLENGE, 9,
            byteArrayOf(16) + ByteArray(16) { 0xAB.toByte() } + "srv".toByteArray()))
        val (proto, resp) = last()
        assertEquals(Ppp.PROTO_CHAP, proto)
        assertEquals(49, resp.data[0].toInt())
        assertEquals(1 + 49 + 4, resp.data.size)
        assertArrayEquals("User".toByteArray(), resp.data.copyOfRange(50, 54))

        val s = session.lastAuth!!.authenticatorResponse
        session.onFrame(Ppp.controlFrame(Ppp.PROTO_CHAP, Ppp.CHAP_CODE_SUCCESS, 9, "$s M=ok".toByteArray()))
        assertEquals(PppSession.Phase.IPCP, session.phase)

        session.onFrame(Ppp.controlFrame(Ppp.PROTO_IPCP, Ppp.CODE_CONF_REQ, 1,
            Ppp.encodeOptions(listOf(Ppp.optIp(Ppp.IPCP_OPT_ADDR, "10.0.0.1")))))
        session.onFrame(Ppp.controlFrame(Ppp.PROTO_IPCP, Ppp.CODE_CONF_NAK, 1, Ppp.encodeOptions(listOf(
            Ppp.optIp(Ppp.IPCP_OPT_ADDR, "10.0.0.7"), Ppp.optIp(Ppp.IPCP_OPT_PRIMARY_DNS, "1.1.1.1")))))
        session.onFrame(Ppp.controlFrame(Ppp.PROTO_IPCP, Ppp.CODE_CONF_ACK, 2, ByteArray(0)))
        assertEquals("10.0.0.7", up)
    }

    @Test fun echoReplyCarriesMagic() {
        session.start()
        session.onFrame(Ppp.controlFrame(Ppp.PROTO_LCP, Ppp.CODE_ECHO_REQ, 5, byteArrayOf(9, 9, 9, 9)))
        val (_, rep) = last()
        assertEquals(Ppp.CODE_ECHO_REP, rep.code)
        assertEquals(4, rep.data.size)
        assertNotEquals(0L, rep.data.fold(0L) { a, b -> a or (b.toLong() and 0xFF) })
    }

    @Test fun authenticatorMismatchTerminates() {
        session.start()
        session.onFrame(Ppp.controlFrame(Ppp.PROTO_LCP, Ppp.CODE_CONF_REQ, 1, Ppp.encodeOptions(listOf(
            Ppp.optBytes(Ppp.LCP_OPT_AUTH, byteArrayOf(0xC2.toByte(), 0x23, 0x81.toByte()))))))
        session.onFrame(Ppp.controlFrame(Ppp.PROTO_LCP, Ppp.CODE_CONF_ACK, 0, ByteArray(0)))
        session.onFrame(Ppp.controlFrame(Ppp.PROTO_CHAP, Ppp.CHAP_CODE_CHALLENGE, 2, byteArrayOf(16) + ByteArray(16) { 1 }))
        session.onFrame(Ppp.controlFrame(Ppp.PROTO_CHAP, Ppp.CHAP_CODE_SUCCESS, 2,
            "S=0000000000000000000000000000000000000000".toByteArray()))
        assertNotNull(down)
    }
}
