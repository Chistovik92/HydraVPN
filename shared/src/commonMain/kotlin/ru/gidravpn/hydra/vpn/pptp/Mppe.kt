package ru.gidravpn.hydra.vpn.pptp

import java.security.MessageDigest

/** RC4 (ARCFOUR). Свой, чтобы не зависеть от провайдера JCE: на части сборок Android/JDK "ARCFOUR" отсутствует. */
class Rc4(key: ByteArray) {
    private val s = IntArray(256) { it }
    private var i = 0
    private var j = 0

    init {
        var k = 0
        for (n in 0 until 256) {
            k = (k + s[n] + (key[n % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[n]; s[n] = s[k]; s[k] = t
        }
    }

    fun process(data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        for (n in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
            out[n] = (data[n].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
        return out
    }
}

/**
 * MPPE (RFC 3078, ключи — RFC 3079) для PPTP: 128-битный RC4, режимы stateless и stateful.
 * Мастер-ключ — [ru.gidravpn.hydra.vpn.ppp.MsChapV2.AuthResult.masterKey] (GetMasterKey из MS-CHAPv2).
 *
 * Не проверено на реальном сервере PPTP: сверено по RFC 3078/3079 и поведению pppd/ppp_mppe; тесты — на самосогласованность
 * и вектор RC4 (RFC 6229). 40- и 56-битные ключи не поддерживаются (слабые, современные серверы их не требуют).
 */
object Mppe {
    /** CCP-опция MPPE, тип 18. */
    const val CCP_OPTION = 18
    const val BIT_STATELESS = 0x01000000      // H
    const val BIT_128 = 0x00000040            // S

    private const val KEY_LEN = 16
    private val PAD1 = ByteArray(40)
    private val PAD2 = ByteArray(40) { 0xF2.toByte() }
    private val MAGIC2 = "On the client side, this is the send key; on the server side, it is the receive key.".toByteArray(Charsets.US_ASCII)
    private val MAGIC3 = "On the client side, this is the receive key; on the server side, it is the send key.".toByteArray(Charsets.US_ASCII)

    private fun sha1(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").also { md -> parts.forEach { md.update(it) } }.digest()

    /** RFC 3079 §3.4 GetAsymmetricStartKey (128 бит). [clientSend] — ключ для данных от клиента к серверу. */
    fun startKey(masterKey: ByteArray, clientSend: Boolean): ByteArray =
        sha1(masterKey.copyOf(KEY_LEN), PAD1, if (clientSend) MAGIC2 else MAGIC3, PAD2).copyOf(KEY_LEN)

    /** RFC 3079 §3.5 GetNewKeyFromSHA + шифрование RC4 самим собой (128 бит). */
    fun nextKey(startKey: ByteArray, current: ByteArray): ByteArray {
        val interim = sha1(startKey, PAD1, current, PAD2).copyOf(KEY_LEN)
        return Rc4(interim).process(interim)
    }

    /** Значение опции CCP: 4 байта «поддерживаемых битов». */
    fun optionBytes(bits: Int): ByteArray =
        byteArrayOf((bits ushr 24).toByte(), (bits ushr 16).toByte(), (bits ushr 8).toByte(), bits.toByte())

    fun optionBits(value: ByteArray): Int? =
        if (value.size != 4) null else value.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
}

/** Одно направление MPPE: шифрование ([send]) или расшифровка. Состояние потока RC4 живёт между пакетами (stateful). */
class MppeStream(private val startKey: ByteArray, private val stateless: Boolean) {
    private var key = startKey
    private var rc4 = Rc4(key)
    private var flushed = true
    private var ccount = 0          // следующий номер пакета (12 бит)

    fun flush() { flushed = true }

    /** Зашифровать содержимое PPP-кадра (протокол + данные). Результат: 2 байта заголовка MPPE + шифртекст. */
    fun encrypt(plain: ByteArray): ByteArray {
        if (stateless) { key = Mppe.nextKey(startKey, key); flushed = true }
        if (flushed) { rc4 = Rc4(key); }
        val bits = (if (flushed) A else 0) or D
        val header = byteArrayOf(((bits or ((ccount ushr 8) and 0x0F))).toByte(), (ccount and 0xFF).toByte())
        val body = rc4.process(plain)
        flushed = false
        if (!stateless && (ccount and 0xFF) == 0xFF) { key = Mppe.nextKey(startKey, key); flushed = true }
        ccount = (ccount + 1) and 0xFFF
        return header + body
    }

    /** Расшифровать то, что вернул [encrypt] на другой стороне; null — пакет нарушает последовательность (stateful) или повреждён. */
    fun decrypt(packet: ByteArray): ByteArray? {
        if (packet.size < 2) return null
        val b0 = packet[0].toInt() and 0xFF
        val cc = ((b0 and 0x0F) shl 8) or (packet[1].toInt() and 0xFF)
        if (b0 and D == 0) return null                    // не зашифрован — для MPPE это ошибка
        val body = packet.copyOfRange(2, packet.size)
        if (stateless) {
            // Потеря пакетов допустима: каждый пропущенный номер — ещё один шаг цепочки ключей.
            repeat((cc - ccount) and 0xFFF) { key = Mppe.nextKey(startKey, key) }
            key = Mppe.nextKey(startKey, key)
            rc4 = Rc4(key)
        } else {
            val flush = b0 and A != 0
            if (!flush && cc != ccount) return null       // рассинхронизация потока RC4
            if (flush) rc4 = Rc4(key)                     // новый поток: номер берём из пакета
        }
        val plain = rc4.process(body)
        if (!stateless && (cc and 0xFF) == 0xFF) key = Mppe.nextKey(startKey, key)
        ccount = (cc + 1) and 0xFFF
        return plain
    }

    private companion object {
        const val A = 0x80   // таблица шифрования (пере)инициализирована
        const val D = 0x10   // кадр зашифрован
    }
}

/** Пара потоков клиента: исходящий (ключ «send клиента») и входящий. */
class MppeSession(masterKey: ByteArray, txStateless: Boolean, rxStateless: Boolean = txStateless) {
    private val tx = MppeStream(Mppe.startKey(masterKey, clientSend = true), txStateless)
    private val rx = MppeStream(Mppe.startKey(masterKey, clientSend = false), rxStateless)
    /** После CCP Reset-Request сервера: следующий пакет передачи начинает поток RC4 заново (бит A). */
    fun flushTx() = tx.flush()
    fun encrypt(plain: ByteArray) = tx.encrypt(plain)
    fun decrypt(packet: ByteArray) = rx.decrypt(packet)
}
