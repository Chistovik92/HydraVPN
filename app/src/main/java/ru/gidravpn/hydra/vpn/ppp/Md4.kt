package ru.gidravpn.hydra.vpn.ppp

/**
 * MD4 (RFC 1320). В Android/Java отсутствует в провайдерах JCE,
 * а нужен для MS-CHAPv2 (NtPasswordHash, NtPasswordHashHash).
 * Реализация без внешних зависимостей; используется только для
 * вычисления хэшей аутентификации.
 */
object Md4 {

    fun digest(message: ByteArray): ByteArray {
        var a = 0x67452301
        var b = 0xEFCDAB89.toInt()
        var c = 0x98BADCFE.toInt()
        var d = 0x10325476

        val len = message.size
        val paddingLen = if (len % 64 < 56) 56 - len % 64 else 120 - len % 64
        val padded = ByteArray(len + paddingLen + 8)
        message.copyInto(padded)
        padded[len] = 0x80.toByte()
        val bitLen = len.toLong() * 8
        for (i in 0 until 8) padded[padded.size - 8 + i] = (bitLen ushr (8 * i)).toByte()

        val x = IntArray(16)
        var i = 0
        while (i < padded.size) {
            for (j in 0 until 16) {
                val off = i + j * 4
                x[j] = (padded[off].toInt() and 0xFF) or
                        ((padded[off + 1].toInt() and 0xFF) shl 8) or
                        ((padded[off + 2].toInt() and 0xFF) shl 16) or
                        ((padded[off + 3].toInt() and 0xFF) shl 24)
            }

            val aa = a; val bb = b; val cc = c; val dd = d

            fun rotl(v: Int, s: Int) = (v shl s) or (v ushr (32 - s))

            // Раунд 1: F = (x&y)|(~x&z); порядок X: 0..15; сдвиги 3,7,11,19
            for (s in 0 until 16) {
                val f = (b and c) or (b.inv() and d)
                val tmp = rotl(a + f + x[s], R1_SHIFTS[s % 4])
                a = d; d = c; c = b; b = tmp
            }
            // Раунд 2: G = (x&y)|(x&z)|(y&z); порядок X: 0,4,8,12,1,5,9,13,…; сдвиги 3,5,9,13
            for (s in 0 until 16) {
                val g = (b and c) or (b and d) or (c and d)
                val tmp = rotl(a + g + x[R2_ORDER[s]] + 0x5A827999.toInt(), R2_SHIFTS[s % 4])
                a = d; d = c; c = b; b = tmp
            }
            // Раунд 3: H = x^y^z; порядок X: 0,8,4,12,2,10,6,14,…; сдвиги 3,9,11,15
            for (s in 0 until 16) {
                val h = b xor c xor d
                val tmp = rotl(a + h + x[R3_ORDER[s]] + 0x6ED9EBA1.toInt(), R3_SHIFTS[s % 4])
                a = d; d = c; c = b; b = tmp
            }

            a += aa; b += bb; c += cc; d += dd
            i += 64
        }

        val out = ByteArray(16)
        listOf(a, b, c, d).forEachIndexed { j, v ->
            out[j * 4] = (v and 0xFF).toByte()
            out[j * 4 + 1] = ((v ushr 8) and 0xFF).toByte()
            out[j * 4 + 2] = ((v ushr 16) and 0xFF).toByte()
            out[j * 4 + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        return out
    }

    private val R1_SHIFTS = intArrayOf(3, 7, 11, 19)
    private val R2_SHIFTS = intArrayOf(3, 5, 9, 13)
    private val R3_SHIFTS = intArrayOf(3, 9, 11, 15)

    private val R2_ORDER = intArrayOf(0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15)
    private val R3_ORDER = intArrayOf(0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15)
}
