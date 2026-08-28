package ru.gidravpn.hydra.vpn.ppp

import java.io.ByteArrayOutputStream

/**
 * PPP (RFC 1661): константы, кодирование/разбор кадров без HDLC-обрамления.
 *
 * SSTP (MS-SSTP) и L2TP (RFC 2661 §5.3) переносят кадры PPP без флагов,
 * Address/Control и FCS: протокол (2 байта) + информное поле.
 */
object Ppp {

    // Протоколы
    const val PROTO_IP = 0x0021
    const val PROTO_LCP = 0xC021
    const val PROTO_PAP = 0xC023
    const val PROTO_CHAP = 0xC223
    const val PROTO_IPCP = 0x8021
    const val PROTO_CCP = 0x80FD

    // Коды LCP/IPCP (RFC 1661)
    const val CODE_CONF_REQ = 1
    const val CODE_CONF_ACK = 2
    const val CODE_CONF_NAK = 3
    const val CODE_CONF_REJ = 4
    const val CODE_TERM_REQ = 5
    const val CODE_TERM_ACK = 6
    const val CODE_CODE_REJ = 7
    const val CODE_PROTO_REJ = 8
    const val CODE_ECHO_REQ = 9
    const val CODE_ECHO_REP = 10
    const val CODE_DISC_REQ = 11

    // Опции LCP
    const val LCP_OPT_MRU = 1
    const val LCP_OPT_AUTH = 3
    const val LCP_OPT_MAGIC = 5
    const val LCP_OPT_PFC = 7
    const val LCP_OPT_ACFC = 8

    // Опции IPCP
    const val IPCP_OPT_ADDR = 3
    const val IPCP_OPT_PRIMARY_DNS = 129
    const val IPCP_OPT_SECONDARY_DNS = 131

    // Аутентификация
    const val AUTH_PAP = 0xC023
    const val AUTH_CHAP_MS2 = 0xC223
    const val CHAP_ALG_MSCHAPV2 = 0x81
    const val CHAP_CODE_CHALLENGE = 1
    const val CHAP_CODE_RESPONSE = 2
    const val CHAP_CODE_SUCCESS = 3
    const val CHAP_CODE_FAILURE = 4

    const val DEFAULT_MRU = 1400

    /** Один PPP-пакет: код+id+длина — заголовок информного поля. */
    data class PppControl(
        val code: Int,
        val id: Int,
        val data: ByteArray,   // опции (после 4-байтового заголовка)
    ) {
        override fun equals(other: Any?) = other is PppControl &&
                code == other.code && id == other.id && data.contentEquals(other.data)
        override fun hashCode() = code * 31 + id
    }

    /** Разбор информного поля LCP/IPCP. */
    fun parseControl(info: ByteArray): PppControl {
        require(info.size >= 4) { "PPP control frame too short" }
        val code = info[0].toInt() and 0xFF
        val id = info[1].toInt() and 0xFF
        val len = ((info[2].toInt() and 0xFF) shl 8) or (info[3].toInt() and 0xFF)
        val data = if (len >= 4 && len <= info.size) info.copyOfRange(4, len) else ByteArray(0)
        return PppControl(code, id, data)
    }

    /** Сборка информного поля LCP/IPCP (код, id, опции). */
    fun buildControl(code: Int, id: Int, options: ByteArray): ByteArray {
        val out = ByteArray(4 + options.size)
        out[0] = code.toByte()
        out[1] = id.toByte()
        out[2] = ((out.size ushr 8) and 0xFF).toByte()
        out[3] = (out.size and 0xFF).toByte()
        options.copyInto(out, 4)
        return out
    }

    /** Кадр PPP: протокол + информное поле. */
    fun frame(proto: Int, info: ByteArray): ByteArray {
        val out = ByteArray(2 + info.size)
        out[0] = ((proto ushr 8) and 0xFF).toByte()
        out[1] = (proto and 0xFF).toByte()
        info.copyInto(out, 2)
        return out
    }

    /** Кадр PPP с управляющим пакетом внутри. */
    fun controlFrame(proto: Int, code: Int, id: Int, options: ByteArray): ByteArray =
        frame(proto, buildControl(code, id, options))

    /** Разбор кадра: протокол + информное поле (PFC не используется). */
    fun parseFrame(frame: ByteArray): Pair<Int, ByteArray>? {
        if (frame.size < 2) return null
        val proto = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        return proto to frame.copyOfRange(2, frame.size)
    }

    // ----- опции -----

    /** Опция: тип, длина, значение. */
    data class Option(val type: Int, val value: ByteArray) {
        override fun equals(other: Any?) = other is Option &&
                type == other.type && value.contentEquals(other.value)
        override fun hashCode() = type

        /** Значение как big-endian целое (MRU, magic и т.п.). */
        fun intValue(): Int {
            var v = 0
            for (b in value) v = (v shl 8) or (b.toInt() and 0xFF)
            return v
        }

        /** Значение как IPv4-строка. */
        fun ipValue(): String =
            value.joinToString(".") { (it.toInt() and 0xFF).toString() }
    }

    fun encodeOptions(opts: List<Option>): ByteArray {
        val bos = ByteArrayOutputStream()
        opts.forEach { o ->
            bos.write(o.type)
            bos.write(o.value.size + 2)
            bos.write(o.value)
        }
        return bos.toByteArray()
    }

    fun parseOptions(data: ByteArray): List<Option> {
        val out = mutableListOf<Option>()
        var i = 0
        while (i + 1 < data.size) {
            val type = data[i].toInt() and 0xFF
            val len = data[i + 1].toInt() and 0xFF
            if (len < 2 || i + len > data.size) break
            out += Option(type, data.copyOfRange(i + 2, i + len))
            i += len
        }
        return out
    }

    fun optInt(type: Int, value: Int): Option = Option(type, byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(), ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(), (value and 0xFF).toByte(),
    ))

    fun optBytes(type: Int, value: ByteArray): Option = Option(type, value)

    fun optIp(type: Int, ip: String): Option {
        val parts = ip.split(".").map { it.toInt() }
        return Option(type, byteArrayOf(parts[0].toByte(), parts[1].toByte(), parts[2].toByte(), parts[3].toByte()))
    }

    fun int32(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )
}
