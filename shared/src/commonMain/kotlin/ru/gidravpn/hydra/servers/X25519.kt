package ru.gidravpn.hydra.servers

import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

/**
 * X25519 (RFC 7748) на BigInteger: на Android 8 (minSdk 26) нет встроенного X25519, а ключи WireGuard/AmneziaWG
 * нужно создавать в приложении (0.8.0). Не для горячего пути — один вызов на ключ.
 */
object X25519 {
    private val P = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
    private val A24 = BigInteger.valueOf(121665)

    fun clamp(k: ByteArray): ByteArray = k.copyOf().also {
        it[0] = (it[0].toInt() and 248).toByte()
        it[31] = ((it[31].toInt() and 127) or 64).toByte()
    }

    private fun decode(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())
    private fun encode(n: BigInteger): ByteArray {
        val be = n.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        val out = ByteArray(32)
        for (i in be.indices) out[i] = be[be.size - 1 - i]
        return out
    }

    fun scalarMult(scalar: ByteArray, u: ByteArray): ByteArray {
        require(scalar.size == 32 && u.size == 32)
        val k = decode(clamp(scalar))
        val x1 = decode(u.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }).mod(P)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = false
        for (t in 254 downTo 0) {
            val bit = k.testBit(t)
            if (swap != bit) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }
            swap = bit
            val a = (x2 + z2).mod(P)
            val aa = (a * a).mod(P)
            val b = (x2 - z2).mod(P)
            val bb = (b * b).mod(P)
            val e = (aa - bb).mod(P)
            val c = (x3 + z3).mod(P)
            val d = (x3 - z3).mod(P)
            val da = (d * a).mod(P)
            val cb = (c * b).mod(P)
            x3 = (da + cb).mod(P).pow(2).mod(P)
            z3 = (x1 * (da - cb).mod(P).pow(2)).mod(P)
            x2 = (aa * bb).mod(P)
            z2 = (e * (aa + A24 * e).mod(P)).mod(P)
        }
        if (swap) {
            var tmp = x2; x2 = x3; x3 = tmp
            tmp = z2; z2 = z3; z3 = tmp
        }
        return encode((x2 * z2.modPow(P - BigInteger.TWO, P)).mod(P))
    }

    private val BASE = ByteArray(32).also { it[0] = 9 }
    fun publicKey(priv: ByteArray): ByteArray = scalarMult(priv, BASE)

    class KeyPair(val privateKey: String, val publicKey: String)

    /** Пара ключей WireGuard: base64 от 32 байт. */
    fun generate(rnd: SecureRandom = SecureRandom()): KeyPair {
        val priv = clamp(ByteArray(32).also { rnd.nextBytes(it) })
        val enc = Base64.getEncoder()
        return KeyPair(enc.encodeToString(priv), enc.encodeToString(publicKey(priv)))
    }

    fun publicFromPrivateB64(priv: String): String =
        Base64.getEncoder().encodeToString(publicKey(Base64.getDecoder().decode(priv)))

    fun randomKeyB64(rnd: SecureRandom = SecureRandom()): String =
        Base64.getEncoder().encodeToString(ByteArray(32).also { rnd.nextBytes(it) })
}
