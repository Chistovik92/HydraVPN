package ru.gidravpn.hydra.data.subscription

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрованные ссылки подписки `hydra://crypt1/<base64url>` (0.8.4-rc1) — аналог incy-link-encoder
 * (github.com/INCY-DEV/incy-link-encoder, MIT) на собственных константах Hydra.
 *
 * Зачем: подписка `https://sub.provider.example/abc123token` в чате/на скриншоте видна ботам
 * модерации, RKN-сканерам и просто любому, кто прочитает переписку. Ссылка
 * `hydra://crypt1/AAECAwQFBgcICQoL…` не светит URL: получатель открывает её на устройстве с
 * Hydra — и подписка импортируется без ручного копирования.
 *
 * Формат (структурно совпадает с incy, чтобы будущие кросс-инструменты были знакомы):
 *  - `hydra://crypt1/` + base64url без паддинга от `IV(12) || ciphertext || GCM-tag(16)`;
 *  - AES-256-GCM, ключ K1 = SHA-256(seed), seed собран из строк-солей и двух 32-байтовых констант;
 *  - открытый текст — компактный JSON: `{"n":<имя?>,"url":<url>,"v":1}`, имя до 128 символов.
 *
 * Модель угроз — как у incy, честно: это **не** шифрование ради секретности. Ключ не является
 * секретом: он зашит во все клиенты Hydra, и любой, кто разберёт APK/бинарь, его извлечёт.
 * Защищает от автоматических сканеров, ботов модерации и случайных скриншотов; от целенаправленного
 * реверсера — нет. При компрометации появится `crypt2/` с новым ключом; `crypt1/`-ссылки в истории
 * чатов продолжат расшифровываться вечно.
 */
object HydraCryptLink {
    const val SCHEME = "hydra"
    const val HOST = "crypt1"
    const val PREFIX = "$SCHEME://$HOST/"
    const val KEY_FINGERPRINT = "f02a091bc99fb112f9d3395dde9ab6dfbf549d557f7ce969be16d9ebdd6a7d13"

    private const val IV_LEN = 12
    private const val TAG_LEN = 16
    private const val MAX_URL = 8192
    private const val MAX_NAME = 128
    private const val MAX_PAYLOAD = 64 * 1024

    // Части соли и блобы разбиты на отдельные константы (как у incy и в клиентах), чтобы
    // однострочный grep по бинарнику не выдавал весь seed целиком. Это обфускация, не секретность.
    private const val SALT_P1 = "hydra"
    private const val SALT_P2 = "deep"
    private const val SALT_P3 = "crypt1"
    private const val SALT_P4 = "v2026.11"
    private const val KEYMAT_A_HEX = "a29230c77fdd225c84a5cda43b2530501fecb242ae237c9528abac7b06cefd4b"
    private const val KEYMAT_B_HEX = "5da3a91e54d49b6869f43a1a65390f69eec75494a760b4afeee878a60e4eff48"

    private val random = SecureRandom()

    /** K1 = SHA-256(seed); SHA-256(K1) должен равняться [KEY_FINGERPRINT] (страж в тестах). */
    private val key: SecretKeySpec by lazy {
        val a = hex(KEYMAT_A_HEX)
        val b = hex(KEYMAT_B_HEX)
        require(a.size == 32 && b.size == 32) { "keymat повреждён" }
        val seed = (SALT_P1 + SALT_P2 + SALT_P3 + SALT_P4).toByteArray(StandardCharsets.UTF_8) + a + b
        SecretKeySpec(sha256(seed), "AES")
    }

    data class Decrypted(val url: String, val name: String?)

    /**
     * Подписка -> `hydra://crypt1/…`. `null` — URL не http(s) или длиннее лимита. Имя необязательно:
     * получатель увидит его в листе импорта. IV случайный: две кодировки одной подписки различаются,
     * но обе расшифровываются.
     */
    fun encode(url: String, name: String? = null): String? {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        if (url.length > MAX_URL) return null
        val plaintext = plaintextJson(url, name?.trim()).toByteArray(StandardCharsets.UTF_8)
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val wire = iv + encrypt(iv).doFinal(plaintext)
        return PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(wire)
    }

    /**
     * `hydra://crypt1/…` -> подписка. `null` — ссылка не наша (префикс не тот), повреждена,
     * обрезана (GCM-тег не сошёлся) или внутри нет корректного http(s)-URL. Префикс сравнивается
     * без учёта регистра; base64url-полезная нагрузка — регистрозависима.
     */
    fun decode(link: String): Decrypted? {
        val t = link.trim()
        if (!t.lowercase().startsWith(PREFIX)) return null
        val payload = t.substring(PREFIX.length).trimEnd('/')
        if (payload.isEmpty() || payload.length > MAX_PAYLOAD) return null
        val wire = runCatching { java.util.Base64.getUrlDecoder().decode(payload) }.getOrNull() ?: return null
        if (wire.size < IV_LEN + TAG_LEN + 1) return null
        val iv = wire.copyOfRange(0, IV_LEN)
        val plain = runCatching { decrypt(iv).doFinal(wire, IV_LEN, wire.size - IV_LEN) }.getOrNull() ?: return null
        val json = runCatching { JSONObject(String(plain, StandardCharsets.UTF_8)) }.getOrNull() ?: return null
        val url = json.optString("url")
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        if (url.length > MAX_URL) return null
        val name = json.optString("n").trim().ifEmpty { null }?.take(MAX_NAME)
        return Decrypted(url, name)
    }

    /** Детерминированная кодировка с заданным IV — только для закреплённых тест-векторов (public: тесты в другом модуле). */
    fun encodeDeterministic(url: String, name: String?, iv: ByteArray): String? {
        if (iv.size != IV_LEN) return null
        val wire = iv + encrypt(iv).doFinal(plaintextJson(url, name).toByteArray(StandardCharsets.UTF_8))
        return PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(wire)
    }

    /** SHA-256(K1) в hex — страж в тестах: сверяется с [KEY_FINGERPRINT]; меняется вместе с ключом. */
    fun fingerprintHex(): String = sha256(key.encoded).joinToString("") { "%02x".format(it) }

    /**
     * Компактный JSON с фиксированным (алфавитным) порядком ключей `n`, `url`, `v` — руками, а не
     * org.json: его HashMap не гарантирует порядок, а байты plaintext обязаны совпадать на Android,
     * ПК и iOS (закреплённый тест-вектор). Так же устроен и апстрим incy.
     */
    private fun plaintextJson(url: String, name: String?): String {
        val sb = StringBuilder("{")
        if (!name.isNullOrEmpty()) sb.append("\"n\":").append(jsonString(name.take(MAX_NAME))).append(",")
        sb.append("\"url\":").append(jsonString(url)).append(",\"v\":1}")
        return sb.toString()
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\t' -> sb.append("\\t")
            ch < ' ' -> sb.append("\\u").append(String.format("%04x", ch.code))
            else -> sb.append(ch)
        }
        return sb.append('"').toString()
    }

    private fun encrypt(iv: ByteArray): Cipher {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LEN * 8, iv))
        return c
    }

    private fun decrypt(iv: ByteArray): Cipher {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LEN * 8, iv))
        return c
    }

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
    private fun hex(s: String) = ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte() }
}