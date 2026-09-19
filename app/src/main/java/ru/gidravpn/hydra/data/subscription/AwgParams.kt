package ru.gidravpn.hydra.data.subscription

/**
 * Параметры AmneziaWG всех поколений (0.6.22) — сверены с `device/uapi.go` amneziawg-go v3.1 и
 * `org.amnezia.awg.config.Interface` официального Android-клиента (ключи .conf и порядок UAPI).
 *
 *  - 1.0: Jc, Jmin, Jmax, S1, S2, H1–H4;
 *  - 1.5: + I1–I5 (сигнатурные пакеты);
 *  - 2.0: + S3, S4, H1–H4 диапазоном «a-b»;
 *  - 3.x: + HeaderProtectionKey, ContentPaddingAddition, таймеры рукопожатия,
 *    RandomTrailers, DisableCookies.
 *
 * В профиле параметры хранятся в extra под ключом .conf в нижнем регистре.
 */
object AwgParams {

    enum class Kind { NUMBER, KEY_B64, BOOL }

    /** ключ .conf (lowercase) → (имя в .conf, ключ UAPI, тип). Порядок = порядок в UAPI. */
    data class Param(val conf: String, val confName: String, val uapi: String, val kind: Kind = Kind.NUMBER)

    val ALL: List<Param> = listOf(
        Param("jc", "Jc", "jc"), Param("jmin", "Jmin", "jmin"), Param("jmax", "Jmax", "jmax"),
        Param("s1", "S1", "s1"), Param("s2", "S2", "s2"), Param("s3", "S3", "s3"), Param("s4", "S4", "s4"),
        Param("h1", "H1", "h1"), Param("h2", "H2", "h2"), Param("h3", "H3", "h3"), Param("h4", "H4", "h4"),
        Param("i1", "I1", "i1"), Param("i2", "I2", "i2"), Param("i3", "I3", "i3"), Param("i4", "I4", "i4"), Param("i5", "I5", "i5"),
        Param("headerprotectionkey", "HeaderProtectionKey", "header_protection_key", Kind.KEY_B64),
        Param("contentpaddingaddition", "ContentPaddingAddition", "content_padding_addition"),
        Param("rekeyaftertime", "RekeyAfterTime", "rekey_after_time"),
        Param("rekeytimeout", "RekeyTimeout", "rekey_timeout"),
        Param("rejectaftertime", "RejectAfterTime", "reject_after_time"),
        Param("keepalivetimeout", "KeepaliveTimeout", "keepalive_timeout"),
        Param("maxhandshakeattempts", "MaxHandshakeAttempts", "max_handshake_attempts"),
        Param("randomtrailers", "RandomTrailers", "random_trailers", Kind.BOOL),
        Param("disablecookies", "DisableCookies", "disable_cookies", Kind.BOOL),
    )

    private val V3 = setOf("headerprotectionkey", "contentpaddingaddition", "rekeyaftertime", "rekeytimeout",
        "rejectaftertime", "keepalivetimeout", "maxhandshakeattempts", "randomtrailers", "disablecookies")

    /** Поколение протокола по набору параметров; "plain" — обычный WireGuard. */
    fun version(values: Map<String, String>): String = when {
        values.isEmpty() -> "plain"
        values.keys.any { it in V3 } -> "3.x"
        values.keys.any { it == "s3" || it == "s4" } ||
            listOf("h1", "h2", "h3", "h4").any { values[it]?.contains('-') == true } -> "2.0"
        values.keys.any { it.length == 2 && it[0] == 'i' } -> "1.5"
        else -> "1.0"
    }

    /** on/off/true/false/1/0 → UAPI 1/0 (как toUapiBool в официальном клиенте). */
    fun uapiBool(v: String): String = when (v.trim().lowercase()) {
        "on", "true", "1", "yes" -> "1"
        else -> "0"
    }
}
