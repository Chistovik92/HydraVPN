package ru.gidravpn.hydra.desktop

import ru.gidravpn.hydra.vpn.pptp.GreFrame
import ru.gidravpn.hydra.vpn.pptp.GreSequencer
import ru.gidravpn.hydra.vpn.pptp.GreTransport
import ru.gidravpn.hydra.vpn.pptp.Mppe
import ru.gidravpn.hydra.vpn.pptp.MppeStream
import ru.gidravpn.hydra.vpn.pptp.Pptp
import ru.gidravpn.hydra.vpn.pptp.PptpClient
import ru.gidravpn.hydra.vpn.pptp.Rc4
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PptpTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `RC4 matches RFC 6229 vector`() {
        val ks = Rc4(hex("0102030405")).process(ByteArray(16))
        assertContentEquals(hex("b2396305f03dc027ccc3524a0a1118a8"), ks)
    }

    private val master = ByteArray(16) { (it * 7 + 3).toByte() }

    private fun pair(stateless: Boolean): Pair<MppeStream, MppeStream> {
        val k = Mppe.startKey(master, clientSend = true)
        return MppeStream(k, stateless) to MppeStream(k, stateless)
    }

    @Test
    fun `MPPE stateful survives rekey every 256 packets`() {
        val (tx, rx) = pair(false)
        repeat(1200) { n ->
            val plain = ByteArray(40 + n % 50) { (it + n).toByte() }
            assertContentEquals(plain, rx.decrypt(tx.encrypt(plain)), "пакет $n")
        }
    }

    @Test
    fun `MPPE stateless tolerates packet loss`() {
        val (tx, rx) = pair(true)
        repeat(300) { n ->
            val plain = ByteArray(60) { (it xor n).toByte() }
            val enc = tx.encrypt(plain)
            if (n % 7 == 3) return@repeat           // «потерянный» пакет — приёмник его не увидит
            assertContentEquals(plain, rx.decrypt(enc), "пакет $n")
        }
    }

    @Test
    fun `MPPE stateful detects a gap and keys differ by direction`() {
        val (tx, rx) = pair(false)
        tx.encrypt(ByteArray(10))                       // потерян
        assertNull(rx.decrypt(tx.encrypt(ByteArray(10))))
        assertTrue(!Mppe.startKey(master, true).contentEquals(Mppe.startKey(master, false)))
    }

    @Test
    fun `MPPE ciphertext is not plaintext and header carries the encrypted bit`() {
        val (tx, _) = pair(true)
        val plain = ByteArray(32) { 0x41 }
        val enc = tx.encrypt(plain)
        assertEquals(34, enc.size)
        assertTrue(enc[0].toInt() and 0x10 != 0)
        assertTrue(!enc.copyOfRange(2, 34).contentEquals(plain))
    }

    @Test
    fun `control messages have the right sizes and parse back`() {
        assertEquals(156, Pptp.sccrq().size)
        assertEquals(168, Pptp.ocrq(7).size)
        assertEquals(Pptp.SCCRQ, Pptp.parse(Pptp.sccrq())!!.type)
        assertEquals(42, Pptp.parse(Pptp.echoRequest(42))!!.echoId)
        assertNull(Pptp.parse(ByteArray(10)))
        val bad = Pptp.sccrq().also { it[4] = 0 }        // испорчен magic cookie
        assertNull(Pptp.parse(bad))
    }

    @Test
    fun `GRE header roundtrip with seq and ack`() {
        val f = GreFrame.decode(GreFrame.encode(0x1234, 5L, 9L, byteArrayOf(1, 2, 3)))!!
        assertEquals(0x1234, f.callId); assertEquals(5L, f.seq); assertEquals(9L, f.ack)
        assertContentEquals(byteArrayOf(1, 2, 3), f.payload)
        val ackOnly = GreFrame.decode(GreFrame.encode(1, null, 77L, ByteArray(0)))!!
        assertNull(ackOnly.seq); assertEquals(77L, ackOnly.ack)
    }

    @Test
    fun `GRE inside an IPv4 packet is extracted`() {
        val gre = GreFrame.encode(1, 0L, null, byteArrayOf(9))
        val ip = ByteArray(20 + gre.size)
        ip[0] = 0x45; ip[9] = 47; ip[12] = 10; ip[13] = 0; ip[14] = 0; ip[15] = 1
        gre.copyInto(ip, 20)
        val (src, body) = GreFrame.fromIpPacket(ip)!!
        assertEquals("10.0.0.1", src)
        assertContentEquals(gre, body)
    }

    @Test
    fun `sequencer drops duplicates and stale frames but accepts gaps`() {
        val s = GreSequencer(5)
        fun rx(seq: Long) = s.receive(GreFrame(1, seq, null, byteArrayOf(seq.toByte())), 1)
        assertNotNull(rx(0)); assertNull(rx(0)); assertNotNull(rx(3)); assertNull(rx(2))
        assertNotNull(rx(4))
        assertNotNull(s.ackOnly()); assertNull(s.ackOnly())
        assertNull(s.receive(GreFrame(99, 10, null, byteArrayOf(1)), 1))   // чужой вызов
    }

    /** Сквозная проверка клиента с поддельным сервером PPTP на локальном TCP и GRE в памяти. */
    @Test
    fun `client completes the control handshake and moves PPP frames over GRE`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverCall = 0x4242
        val toClient = LinkedBlockingQueue<ByteArray>()      // GRE сервер → клиент
        val toServer = LinkedBlockingQueue<ByteArray>()      // GRE клиент → сервер
        thread(isDaemon = true) {
            server.accept().use { c ->
                val i = c.getInputStream(); val o = c.getOutputStream()
                assertEquals(Pptp.SCCRQ, Pptp.parse(Pptp.read(i)!!)!!.type)
                o.write(Pptp.sccrq().also { it[8] = 0; it[9] = Pptp.SCCRP.toByte(); it[14] = 1 }); o.flush()
                val ocrq = Pptp.parse(Pptp.read(i)!!)!!
                val ourId = ((ocrq.raw[12].toInt() and 0xFF) shl 8) or (ocrq.raw[13].toInt() and 0xFF)
                val ocrp = ByteArray(32)
                Pptp.sccrq().copyInto(ocrp, 0, 0, 12)
                ocrp[0] = 0; ocrp[1] = 32; ocrp[9] = Pptp.OCRP.toByte()
                ocrp[12] = (serverCall ushr 8).toByte(); ocrp[13] = serverCall.toByte()
                ocrp[14] = (ourId ushr 8).toByte(); ocrp[15] = ourId.toByte(); ocrp[16] = 1
                o.write(ocrp); o.flush()
                // Ответ на первый кадр клиента: эхо PPP-данных с подтверждением.
                val first = GreFrame.decode(toServer.poll(5, TimeUnit.SECONDS)!!)!!
                assertEquals(serverCall, first.callId)
                toClient.put(GreFrame.encode(ourId, 0L, first.seq, first.payload.reversedArray()))
                Thread.sleep(500)
            }
        }
        val got = LinkedBlockingQueue<ByteArray>()
        val gotFrame = CountDownLatch(1)
        val client = PptpClient(
            host = "127.0.0.1", port = server.localPort,
            openGre = { onGre ->
                thread(isDaemon = true) { while (true) onGre(toClient.take()) }
                object : GreTransport {
                    override fun send(gre: ByteArray) { toServer.put(gre) }
                    override fun close() {}
                }
            },
            onFrame = { got.put(it); gotFrame.countDown() },
            onDown = {},
        )
        client.connect()
        client.sendFrame(byteArrayOf(1, 2, 3, 4))
        assertTrue(gotFrame.await(5, TimeUnit.SECONDS), "кадр от сервера не дошёл")
        assertContentEquals(byteArrayOf(4, 3, 2, 1), got.poll())
        client.close()
        server.close()
    }
}
