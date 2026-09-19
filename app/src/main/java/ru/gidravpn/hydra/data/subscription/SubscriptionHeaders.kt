package ru.gidravpn.hydra.data.subscription

import java.util.Base64

/**
 * Разбор служебных заголовков ответа подписки (стандарт де-факто Remnawave / Marzban / 3x-ui /
 * Happ / v2rayN):
 *  - `profile-title` — название (часто `base64:<...>`);
 *  - `subscription-userinfo: upload=…; download=…; total=…; expire=…`;
 *  - `profile-update-interval` — часы между обновлениями;
 *  - `support-url`.
 */
object SubscriptionHeaders {

    data class Info(
        val title: String = "",
        val upload: Long = 0,
        val download: Long = 0,
        val total: Long = 0,
        val expire: Long = 0,
        val updateHours: Int? = null,
        val supportUrl: String = "",
    )

    fun parse(header: (String) -> String?): Info {
        val user = parseUserInfo(header("subscription-userinfo").orEmpty())
        return Info(
            title = decodeTitle(header("profile-title").orEmpty())
                .ifBlank { fromContentDisposition(header("content-disposition").orEmpty()) },
            upload = user["upload"] ?: 0,
            download = user["download"] ?: 0,
            total = user["total"] ?: 0,
            expire = user["expire"] ?: 0,
            updateHours = header("profile-update-interval")?.trim()?.toIntOrNull()?.takeIf { it in 1..168 },
            supportUrl = header("support-url").orEmpty().trim(),
        )
    }

    internal fun decodeTitle(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("base64:", ignoreCase = true)) {
            return runCatching { String(Base64.getMimeDecoder().decode(t.substring(7).trim())) }
                .getOrDefault("").trim()
        }
        return t
    }

    internal fun parseUserInfo(raw: String): Map<String, Long> =
        raw.split(';').mapNotNull { part ->
            val k = part.substringBefore('=', "").trim().lowercase()
            val v = part.substringAfter('=', "").trim().toDoubleOrNull()?.toLong()
            if (k.isEmpty() || v == null) null else k to v
        }.toMap()

    /** `attachment; filename*=UTF-8''My%20VPN` / `filename="My VPN"` — запасной источник имени. */
    private fun fromContentDisposition(raw: String): String {
        val star = Regex("filename\\*=(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
        val plain = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
        val v = (star ?: plain)?.trim() ?: return ""
        return runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
            .removeSuffix(".txt").removeSuffix(".yaml").removeSuffix(".json")
    }
}
