package ru.gidravpn.hydra.vpn.ppp

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * MS-CHAPv2 (RFC 2759) + master key RFC 3079 (GetMasterKey) — CMK
 * для SSTP crypto-binding.
 *
 * Константы и тест-векторы сверены с RFC 2759 §8.7/§9.2 и RFC 3079 §3.4/§3.5.
 * Используется фазой CHAP аутентификации PPP (SSTP и L2TP).
 */
object MsChapV2 {

    /** Результат аутентификации: NtResponse, CMK, ответ аутентикатора. */
    class AuthResult(
        val ntResponse: ByteArray,          // 24 байта
        val masterKey: ByteArray,           // 16 байтов — CMK (RFC 3079 GetMasterKey)
        val authenticatorResponse: String,  // "S=<40 hex>"
    )

    fun authenticate(
        userName: String,
        password: String,
        authenticatorChallenge: ByteArray,   // 16 байт от сервера
        peerChallenge: ByteArray,            // 16 байт, генерируем мы
    ): AuthResult {
        require(authenticatorChallenge.size == 16) { "authenticator challenge must be 16 bytes" }
        require(peerChallenge.size == 16) { "peer challenge must be 16 bytes" }

        val ntHash = ntPasswordHash(password)
        val ntHashHash = ntPasswordHashHash(ntHash)
        val challenge8 = challengeHash(peerChallenge, authenticatorChallenge, userName)
        val ntResponse = challengeResponse(challenge8, ntHash)
        val masterKey = getMasterKey(ntHashHash, ntResponse)
        val authResp = generateAuthenticatorResponse(
            password, userName, authenticatorChallenge, peerChallenge, ntResponse
        )
        return AuthResult(ntResponse, masterKey, authResp)
    }

    // ----- RFC 2759 §8 -----

    fun ntPasswordHash(password: String): ByteArray =
        Md4.digest(password.toByteArray(Charsets.UTF_16LE))

    fun ntPasswordHashHash(ntHash: ByteArray): ByteArray = Md4.digest(ntHash)

    /** SHA1(PeerChallenge[16] || AuthenticatorChallenge[16] || UserName)[0..7]. */
    fun challengeHash(
        peerChallenge: ByteArray, authenticatorChallenge: ByteArray, userName: String
    ): ByteArray {
        val md = sha1()
        md.update(peerChallenge)              // 16 байт
        md.update(authenticatorChallenge)     // 16 байт
        md.update(userName.toByteArray(Charsets.UTF_8))
        return md.digest().copyOfRange(0, 8)
    }

    /** DES×3: PasswordHash → zero-pad до 21 байта → три 7-байтовых ключа. */
    fun challengeResponse(challenge8: ByteArray, ntHash: ByteArray): ByteArray {
        val zHash = ByteArray(21)
        ntHash.copyInto(zHash)
        return des7(zHash.copyOfRange(0, 7), challenge8) +
                des7(zHash.copyOfRange(7, 14), challenge8) +
                des7(zHash.copyOfRange(14, 21), challenge8)
    }

    /** RFC 2759 §8.7: "S=" + 40 hex. */
    fun generateAuthenticatorResponse(
        password: String, userName: String,
        authenticatorChallenge: ByteArray, peerChallenge: ByteArray,
        ntResponse: ByteArray
    ): String {
        val ntHash = ntPasswordHash(password)
        val ntHashHash = ntPasswordHashHash(ntHash)

        val md = sha1()
        md.update(ntHashHash)
        md.update(ntResponse)
        md.update(MAGIC1)                     // 39 байт
        val digest1 = md.digest()

        val challenge8 = challengeHash(peerChallenge, authenticatorChallenge, userName)
        val md2 = sha1()
        md2.update(digest1)
        md2.update(challenge8)
        md2.update(MAGIC2)                    // 41 байт
        val digest2 = md2.digest()

        return "S=" + digest2.toHex().uppercase()
    }

    // ----- RFC 3079 §3.4: GetMasterKey (CMK для SSTP crypto-binding) -----

    fun getMasterKey(ntHashHash: ByteArray, ntResponse: ByteArray): ByteArray {
        val md = sha1()
        md.update(ntHashHash)
        md.update(ntResponse)
        md.update(MPPE_MAGIC1)                // 27 байт: "This is the MPPE Master Key"
        return md.digest().copyOf(16)
    }

    // ----- DES с 56-битным ключом (RFC 2759 §8.6, parity-биты) -----

    private fun des7(key7: ByteArray, data8: ByteArray): ByteArray {
        val key8 = expandKey(key7)
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key8, "DES"))
        return cipher.doFinal(data8.copyOf(8))
    }

    /**
     * 56-битный поток key7 (MSB-first) → биты 7..1 каждого из 8 байт,
     * бит 0 — нечётная чётность. Сверено с примером RFC 2759 §9.3:
     * "FC 15 6A F7 ED CD 6C" → "FD 0B 5B 5E 7F 6E 34 D9".
     */
    private fun expandKey(key7: ByteArray): ByteArray {
        val out = ByteArray(8)
        var bit = 0
        for (i in 0 until 8) {
            var b = 0
            for (j in 7 downTo 1) {
                val v = (key7[bit / 8].toInt() shr (7 - bit % 8)) and 1
                b = b or (v shl j)
                bit++
            }
            out[i] = b.toByte()
        }
        for (i in 0 until 8) {
            var v = out[i].toInt() and 0xFE
            var ones = 0
            for (j in 7 downTo 1) if ((v shr j) and 1 == 1) ones++
            if (ones % 2 == 0) v = v or 1
            out[i] = v.toByte()
        }
        return out
    }

    // ----- HMAC (SSTP Compound MAC) -----

    fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }.doFinal(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun sha1() = java.security.MessageDigest.getInstance("SHA-1")

    // ----- Константы (RFC 2759 §8.7, RFC 3079 §3.4) -----

    /** "Magic server to client signing constant" — RFC 2759 Magic1[39]. */
    private val MAGIC1 = "Magic server to client signing constant".toByteArray()

    /** "Pad to make it do more than one iteration" — RFC 2759 Magic2[41]. */
    private val MAGIC2 = "Pad to make it do more than one iteration".toByteArray()

    /** "This is the MPPE Master Key" — RFC 3079 Magic1[27]. */
    private val MPPE_MAGIC1 = "This is the MPPE Master Key".toByteArray()

    internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
