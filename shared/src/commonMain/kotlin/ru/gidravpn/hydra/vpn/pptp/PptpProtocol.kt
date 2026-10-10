package ru.gidravpn.hydra.vpn.pptp

import java.io.EOFException
import java.io.InputStream

/**
 * PPTP (RFC 2637): управляющий канал TCP 1723 и «улучшенный» GRE (IP-протокол 47) для данных PPP.
 * Здесь только кодеки — без сокетов, поэтому одинаково работают на Android и ПК и проверяются юнит-тестами.
 */
object Pptp {
    const val PORT = 1723
    const val GRE_PROTOCOL = 47
    const val COOKIE = 0x1A2B3C4D

    // Типы управляющих сообщений (§2).
    const val SCCRQ = 1; const val SCCRP = 2; const val STOP_CCRQ = 3; const val STOP_CCRP = 4
    const val ECHO_REQ = 5; const val ECHO_REP = 6; const val OCRQ = 7; const val OCRP = 8
    const val ICRQ = 9; const val CALL_CLEAR = 12; const val CALL_DISCONNECT = 13
    const val WAN_ERROR = 14; const val SET_LINK_INFO = 15

    private const val HEADER = 12

    private fun ByteArray.put16(o: Int, v: Int) { this[o] = (v ushr 8).toByte(); this[o + 1] = v.toByte() }
    private fun ByteArray.put32(o: Int, v: Int) { put16(o, v ushr 16); put16(o + 2, v) }
    private fun ByteArray.u16(o: Int) = ((this[o].toInt() and 0xFF) shl 8) or (this[o + 1].toInt() and 0xFF)
    private fun ByteArray.u32(o: Int) = (u16(o) shl 16) or u16(o + 2)

    private fun message(type: Int, length: Int): ByteArray = ByteArray(length).also {
        it.put16(0, length); it.put16(2, 1); it.put32(4, COOKIE); it.put16(8, type)
    }

    private fun ByteArray.putAscii(o: Int, s: String, max: Int) {
        val b = s.toByteArray(Charsets.US_ASCII)
        b.copyInto(this, o, 0, minOf(b.size, max - 1))
    }

    /** Start-Control-Connection-Request (156 байт). */
    fun sccrq(host: String = "hydra", vendor: String = "Hydra VPN"): ByteArray = message(SCCRQ, 156).also {
        it.put16(12, 0x0100)          // версия протокола 1.0
        it.put32(16, 3)               // framing: async + sync
        it.put32(20, 3)               // bearer: analog + digital
        it.put16(24, 0)               // максимум каналов (клиент: 0)
        it.put16(26, 1)               // версия прошивки
        it.putAscii(28, host, 64)
        it.putAscii(92, vendor, 64)
    }

    /** Outgoing-Call-Request (168 байт): [callId] — наш идентификатор вызова (ключ GRE от сервера к нам). */
    fun ocrq(callId: Int, serial: Int = 1): ByteArray = message(OCRQ, 168).also {
        it.put16(12, callId)
        it.put16(14, serial)
        it.put32(16, 300)             // минимальная скорость, бит/с
        it.put32(20, 100_000_000)     // максимальная
        it.put32(24, 3)               // bearer: любой
        it.put32(28, 3)               // framing: любой
        it.put16(32, 64)              // окно приёма GRE
        it.put16(34, 0)               // задержка обработки
    }

    fun echoRequest(id: Int): ByteArray = message(ECHO_REQ, 16).also { it.put32(12, id) }

    fun echoReply(id: Int): ByteArray = message(ECHO_REP, 20).also { it.put32(12, id); it[16] = 1 }

    fun stopCcrq(): ByteArray = message(STOP_CCRQ, 16).also { it[12] = 3 }   // причина: Stop-Local-Shutdown

    fun callClear(callId: Int): ByteArray = message(CALL_CLEAR, 16).also { it.put16(12, callId) }

    /** Set-Link-Info (24 байта): ACCM — все управляющие символы не экранируются (GRE передаёт кадры без HDLC). */
    fun setLinkInfo(peerCallId: Int): ByteArray = message(SET_LINK_INFO, 24).also {
        it.put16(12, peerCallId); it.put32(16, 0); it.put32(20, 0)
    }

    /** Разобранное управляющее сообщение; поля [result]/[error] есть у ответов. */
    class Control(val type: Int, val raw: ByteArray) {
        val length get() = raw.size
        /** Код результата ответа (1 — успех). Смещения разные: SCCRP — 14, StopCCRP — 12, OCRP и Echo-Reply — 16. */
        val result: Int get() = when (type) {
            SCCRP -> raw[14].toInt() and 0xFF
            STOP_CCRP -> raw[12].toInt() and 0xFF
            OCRP, ECHO_REP -> raw[16].toInt() and 0xFF
            else -> 0
        }
        val error: Int get() = when (type) {
            SCCRP -> raw[15].toInt() and 0xFF
            STOP_CCRP -> raw[13].toInt() and 0xFF
            OCRP, ECHO_REP -> raw[17].toInt() and 0xFF
            else -> 0
        }
        /** OCRP: идентификатор вызова на стороне сервера (его ставим в ключ GRE) и наш, который он подтверждает. */
        val peerCallId: Int get() = if (type == OCRP) raw.u16(12) else 0
        val ourCallId: Int get() = if (type == OCRP) raw.u16(14) else 0
        /** Echo-Request / Echo-Reply: идентификатор. */
        val echoId: Int get() = raw.u32(12)
    }

    fun parse(raw: ByteArray): Control? {
        if (raw.size < HEADER + 4 || raw.u16(0) != raw.size || raw.u16(2) != 1 || raw.u32(4) != COOKIE) return null
        val type = raw.u16(8)
        val min = when (type) {
            SCCRQ, SCCRP -> 156
            OCRP -> 32
            ECHO_REP -> 20
            else -> 16
        }
        if (raw.size < min) return null
        return Control(type, raw)
    }

    /** Читает одно сообщение из потока TCP: сначала длина, потом остаток. null — поток закрыт на границе сообщения. */
    fun read(input: InputStream): ByteArray? {
        val lenBytes = ByteArray(2)
        var n = 0
        while (n < 2) { val r = input.read(lenBytes, n, 2 - n); if (r < 0) { if (n == 0) return null else throw EOFException() }; n += r }
        val len = lenBytes.u16(0)
        if (len < HEADER + 4 || len > 1024) throw java.io.IOException("PPTP: недопустимая длина сообщения $len")
        val msg = ByteArray(len)
        lenBytes.copyInto(msg)
        n = 2
        while (n < len) { val r = input.read(msg, n, len - n); if (r < 0) throw EOFException(); n += r }
        return msg
    }
}

/**
 * Улучшенный GRE (RFC 2637 §4.1). Заголовок: K=1 всегда, S — если есть данные, A — если есть подтверждение.
 * Ключ = длина полезной нагрузки (2) + Call ID получателя (2).
 */
class GreFrame(val callId: Int, val seq: Long?, val ack: Long?, val payload: ByteArray) {
    companion object {
        private const val PROTO_PPP = 0x880B

        fun encode(callId: Int, seq: Long?, ack: Long?, payload: ByteArray): ByteArray {
            val len = 8 + (if (seq != null) 4 else 0) + (if (ack != null) 4 else 0)
            val out = ByteArray(len + payload.size)
            out[0] = (0x20 or (if (seq != null) 0x10 else 0)).toByte()      // K, S
            out[1] = (0x01 or (if (ack != null) 0x80 else 0)).toByte()      // A, версия 1
            out[2] = (PROTO_PPP ushr 8).toByte(); out[3] = PROTO_PPP.toByte()
            out[4] = (payload.size ushr 8).toByte(); out[5] = payload.size.toByte()
            out[6] = (callId ushr 8).toByte(); out[7] = callId.toByte()
            var o = 8
            if (seq != null) { put32(out, o, seq); o += 4 }
            if (ack != null) { put32(out, o, ack); o += 4 }
            payload.copyInto(out, o)
            return out
        }

        /** [gre] — GRE без IP-заголовка. null — не наш формат. */
        fun decode(gre: ByteArray): GreFrame? {
            if (gre.size < 8) return null
            val b0 = gre[0].toInt() and 0xFF; val b1 = gre[1].toInt() and 0xFF
            if (b0 and 0x80 != 0 || b0 and 0x40 != 0) return null           // C и R не используются
            if (b0 and 0x20 == 0 || b1 and 0x07 != 1) return null           // нужен ключ и версия 1
            if (((gre[2].toInt() and 0xFF) shl 8 or (gre[3].toInt() and 0xFF)) != PROTO_PPP) return null
            val plen = ((gre[4].toInt() and 0xFF) shl 8) or (gre[5].toInt() and 0xFF)
            val call = ((gre[6].toInt() and 0xFF) shl 8) or (gre[7].toInt() and 0xFF)
            var o = 8
            var seq: Long? = null; var ack: Long? = null
            if (b0 and 0x10 != 0) { if (gre.size < o + 4) return null; seq = get32(gre, o); o += 4 }
            if (b1 and 0x80 != 0) { if (gre.size < o + 4) return null; ack = get32(gre, o); o += 4 }
            if (gre.size < o + plen) return null
            return GreFrame(call, seq, ack, gre.copyOfRange(o, o + plen))
        }

        /** Из сырого IPv4-пакета: (адрес источника, GRE). null — не GRE или не IPv4. */
        fun fromIpPacket(ip: ByteArray): Pair<String, ByteArray>? {
            if (ip.size < 20 || (ip[0].toInt() shr 4) != 4 || (ip[9].toInt() and 0xFF) != Pptp.GRE_PROTOCOL) return null
            val ihl = (ip[0].toInt() and 0x0F) * 4
            if (ihl < 20 || ip.size < ihl) return null
            val src = (12..15).joinToString(".") { (ip[it].toInt() and 0xFF).toString() }
            return src to ip.copyOfRange(ihl, ip.size)
        }

        private fun put32(b: ByteArray, o: Int, v: Long) {
            b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
        }
        private fun get32(b: ByteArray, o: Int): Long =
            ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
    }
}

/** Порядковые номера GRE одного вызова: исходящие, последний принятый, отсев повторов и устаревших кадров. */
class GreSequencer(private val peerCallId: Int) {
    private var nextSeq = 0L
    private var lastRx = -1L
    private var ackedUpTo = -1L

    /** Кадр с данными PPP для сервера (с подтверждением последнего принятого). */
    @Synchronized fun data(payload: ByteArray): ByteArray {
        val ack = lastRx.takeIf { it >= 0 }
        if (ack != null) ackedUpTo = ack
        return GreFrame.encode(peerCallId, nextSeq++ and 0xFFFFFFFFL, ack, payload)
    }

    /** Кадр-подтверждение без данных; null, если подтверждать нечего. */
    @Synchronized fun ackOnly(): ByteArray? {
        if (lastRx < 0 || lastRx == ackedUpTo) return null
        ackedUpTo = lastRx
        return GreFrame.encode(peerCallId, null, lastRx, ByteArray(0))
    }

    /** Принять кадр от сервера: PPP-данные или null (подтверждение, повтор, чужой вызов). */
    @Synchronized fun receive(frame: GreFrame, ourCallId: Int): ByteArray? {
        if (frame.callId != ourCallId) return null
        val seq = frame.seq ?: return null
        // Сравнение по модулю 2^32: «новее» — в пределах половины пространства.
        if (lastRx >= 0 && ((seq - lastRx) and 0xFFFFFFFFL) - 1 >= 0x7FFFFFFFL) return null
        if (lastRx >= 0 && seq == lastRx) return null
        lastRx = seq
        return frame.payload.takeIf { it.isNotEmpty() }
    }
}
