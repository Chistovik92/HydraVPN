package ru.gidravpn.hydra.vpn.core

import ru.gidravpn.hydra.vpn.SocketGuard
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * L2TPv2-транспорт (RFC 2661): UDP, контрольный канал с Ns/Nr и ZLB-подтверждениями,
 * канал данных с PPP-кадрами. Точные номера AVP и обязательные поля сообщений —
 * по RFC 2661 §3.1–6.12.
 *
 * Контрольное сообщение: [T=1|L=1|S=1|Ver=2][Len16][TunnelId16][SessionId16][Ns16][Nr16] + AVP'ы.
 * Сообщение данных: минимальный заголовок 6 байт + кадр PPP (без HDLC/FCS, §5.3).
 *
 * Надёжность: ретрансмиссия запросов при установке (5×1с, эксп. назад),
 * ZLB-ack по входящим контрольным сообщениям. Требует on-device теста.
 */
class L2tpTransport(
    private val host: String,
    private val port: Int,
    private val onLog: (String) -> Unit,
) {
    // AVP (RFC 2661 §4.4)
    private object Avp {
        const val MESSAGE_TYPE = 0
        const val RESULT_CODE = 1
        const val PROTOCOL_VERSION = 2
        const val FRAMING_CAPABILITIES = 3
        const val BEARER_CAPABILITIES = 4
        const val FIRMWARE_REVISION = 6
        const val HOST_NAME = 7
        const val VENDOR_NAME = 8
        const val ASSIGNED_TUNNEL_ID = 9
        const val CHALLENGE = 11
        const val CHALLENGE_RESPONSE = 13
        const val ASSIGNED_SESSION_ID = 14
        const val CALL_SERIAL_NUMBER = 15
        const val BEARER_TYPE = 18
        const val FRAMING_TYPE = 19
        const val CALLING_NUMBER = 22
        const val TX_CONNECT_SPEED = 24
    }

    // Control message types (RFC 2661 §3.2)
    object Ctrl {
        const val SCCRQ = 1
        const val SCCRP = 2
        const val SCCCN = 3
        const val STOP_CCN = 4
        const val HELLO = 6
        const val ICRQ = 10
        const val ICRP = 11
        const val ICCN = 12
        const val CDN = 14
    }

    private val random = SecureRandom()
    val socket = DatagramSocket().also {
        SocketGuard.protect(it) // иначе UDP-трафик уйдёт в собственный tun
    }

    /** Наши ID (ненулевые, выдаём сами); пировы приходят в AVP. */
    val ourTunnelId = 1 + random.nextInt(0xFFFE)
    val ourSessionId = 1 + random.nextInt(0xFFFE)
    @Volatile var peerTunnelId = 0
        private set
    @Volatile var peerSessionId = 0
        private set

    /** Входящие контрольные сообщения (тип → AVP-карта), кроме Hello/StopCCN/CDN. */
    private val controlQueue = LinkedBlockingDeque<Pair<Int, Map<Int, ByteArray>>>()

    /** Входящие PPP-кадры (канал данных). */
    @Volatile var onData: ((ByteArray) -> Unit)? = null

    /** Туннель разорван сервером (StopCCN/CDN). */
    @Volatile var onTunnelDown: ((String) -> Unit)? = null

    private var ns = 0    // следующий исходящий номер
    private var nr = 0    // следующий ожидаемый входящий
    @Volatile private var running = false
    private val sendLock = Any()

    fun start() {
        if (running) return
        running = true
        socket.soTimeout = 1000
        thread(name = "l2tp-reader") {
            val buf = ByteArray(65536)
            while (running) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(pkt)
                    if (pkt.length > 0) handleDatagram(buf.copyOf(pkt.length))
                } catch (_: java.net.SocketTimeoutException) {
                } catch (t: Throwable) {
                    if (running) onLog("L2TP: чтение прервано: ${t.message}")
                }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { socket.close() }
    }

    // ----- приём -----

    private fun handleDatagram(p: ByteArray) {
        val t = (p[0].toInt() and 0x80) != 0
        val ver = p[1].toInt() and 0x0F
        if (ver != 2) return
        if (t) {
            if (p.size < 12) return
            synchronized(this) {
                val nsPeer = be16(p, 8)
                if (nsPeer == nr) {
                    nr = (nr + 1) and 0xFFFF
                    processControl(p)
                } // дубликаты пропускаем, ack ниже всё равно уходит
            }
            sendZlbAck()
        } else {
            if (p.size < 6) return
            onData?.invoke(p.copyOfRange(6, p.size))
        }
    }

    private fun processControl(p: ByteArray) {
        val avps = parseAvps(p.copyOfRange(12, p.size))
        val msgType = avps[Avp.MESSAGE_TYPE]?.let { be16(it, 0) } ?: return
        when (msgType) {
            Ctrl.HELLO -> Unit // ack уже отправлен (ZLB)
            Ctrl.STOP_CCN -> {
                val result = avps[Avp.RESULT_CODE]
                onTunnelDown?.invoke("StopCCN (код ${result?.let { be16(it, 0) } ?: "?"})")
            }
            Ctrl.CDN -> onTunnelDown?.invoke("Call-Disconnect-Notify")
            else -> controlQueue.add(msgType to avps)
        }
    }

    // ----- отправка -----

    /** Контрольное сообщение; инкрементирует Ns. */
    fun sendControl(msgType: Int, avps: List<Pair<Int, ByteArray>>) {
        val payload = ByteArrayOutputStream()
        payload.write(avp(Avp.MESSAGE_TYPE, be16Bytes(msgType)))
        avps.forEach { (t, v) -> payload.write(avp(t, v)) }
        val body = payload.toByteArray()
        val out = ByteArray(12 + body.size)
        out[0] = 0xC0.toByte() // T|L|S
        out[1] = 0x02
        writeBe16(out, 2, out.size)
        writeBe16(out, 4, peerTunnelId)
        writeBe16(out, 6, 0) // sessionId = 0 для туннельных сообщений
        synchronized(this) {
            writeBe16(out, 8, ns)
            writeBe16(out, 10, nr)
            ns = (ns + 1) and 0xFFFF
        }
        body.copyInto(out, 12)
        send(out)
    }

    /** ZLB-ack: только заголовок, Ns не инкрементируется (RFC 2661 §5.8). */
    private fun sendZlbAck() {
        val out = ByteArray(12)
        out[0] = 0xC0.toByte()
        out[1] = 0x02
        writeBe16(out, 2, 12)
        writeBe16(out, 4, peerTunnelId)
        writeBe16(out, 6, 0)
        synchronized(this) {
            writeBe16(out, 8, ns)
            writeBe16(out, 10, nr)
        }
        send(out)
    }

    /** Кадр PPP в канал данных (заголовок 6 байт, L=0, S=0). */
    fun sendData(pppFrame: ByteArray) {
        val out = ByteArray(6 + pppFrame.size)
        out[0] = 0x00 // T=0, L=0, S=0
        out[1] = 0x02
        writeBe16(out, 2, peerTunnelId)
        writeBe16(out, 4, peerSessionId)
        pppFrame.copyInto(out, 6)
        send(out)
    }

    /**
     * Обмен «запрос → ответ»: ретрансмиссия с экспоненциальной паузой.
     * @param expectMsgType тип ожидаемого ответа (null — без ожидания)
     */
    fun exchange(
        msgType: Int,
        avps: List<Pair<Int, ByteArray>>,
        expectMsgType: Int?,
        timeoutMs: Long = 5000,
    ): Map<Int, ByteArray>? {
        var attempt = 0
        var delay = 1000L
        val deadline = System.currentTimeMillis() + timeoutMs * 3
        while (System.currentTimeMillis() < deadline && attempt < 5) {
            // очистить накопленное
            controlQueue.clear()
            sendControl(msgType, avps)
            if (expectMsgType == null) return null
            val waitEnd = System.currentTimeMillis() + timeoutMs.coerceAtMost(delay + 2000)
            while (System.currentTimeMillis() < waitEnd) {
                val received = controlQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (received.first == expectMsgType) {
                    val avpMap = received.second
                    // сразу запоминаем выданные пиру идентификаторы
                    avpMap[Avp.ASSIGNED_TUNNEL_ID]?.let { peerTunnelId = be16(it, 0) }
                    avpMap[Avp.ASSIGNED_SESSION_ID]?.let { peerSessionId = be16(it, 0) }
                    return avpMap
                }
            }
            attempt++
            delay *= 2
            onLog("L2TP: повтор $attempt для сообщения $msgType")
        }
        return null
    }

    // ----- AVP-кодек -----

    private fun avp(type: Int, value: ByteArray): ByteArray {
        val out = ByteArray(6 + value.size)
        val len = out.size
        out[0] = (0x80 or ((len shr 8) and 0x03)).toByte() // M=1
        out[1] = (len and 0xFF).toByte()
        out[2] = 0; out[3] = 0                            // VendorID = IETF
        out[4] = ((type shr 8) and 0xFF).toByte()
        out[5] = (type and 0xFF).toByte()
        value.copyInto(out, 6)
        return out
    }

    private fun parseAvps(data: ByteArray): Map<Int, ByteArray> {
        val out = mutableMapOf<Int, ByteArray>()
        var i = 0
        while (i + 6 <= data.size) {
            val len = ((data[i].toInt() and 0x03) shl 8) or (data[i + 1].toInt() and 0xFF)
            if (len < 6 || i + len > data.size) break
            val type = ((data[i + 4].toInt() and 0xFF) shl 8) or (data[i + 5].toInt() and 0xFF)
            out[type] = data.copyOfRange(i + 6, i + len)
            i += len
        }
        return out
    }

    /** Tunnel-auth (RFC 2661 §4.4.3/§5.1.1): MD5(msgTypeId || secret || challenge). */
    fun challengeResponse(msgTypeId: Int, secret: ByteArray, challenge: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        md.update(msgTypeId.toByte())
        md.update(secret)
        md.update(challenge)
        return md.digest() // 16 октетов
    }

    // ----- утилиты -----

    private fun send(packet: ByteArray) {
        synchronized(sendLock) {
            runCatching {
                socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(host), port))
            }
        }
    }

    private fun be16(p: ByteArray, off: Int): Int =
        ((p[off].toInt() and 0xFF) shl 8) or (p[off + 1].toInt() and 0xFF)

    private fun writeBe16(p: ByteArray, off: Int, v: Int) {
        p[off] = ((v shr 8) and 0xFF).toByte()
        p[off + 1] = (v and 0xFF).toByte()
    }

    private fun be16Bytes(v: Int) = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    companion object {
        fun be32Bytes(v: Int) = byteArrayOf(
            ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
        )
    }
}
