package ru.gidravpn.hydra.data.subscription

import ru.gidravpn.hydra.data.model.ServerProfile
import java.net.URLDecoder

/**
 * «Умный» импорт (0.6.18): клиент сам определяет, что ему дали — из QR-кода,
 * фотографии, буфера обмена или поля ввода, — и не заставляет пользователя
 * выбирать «ссылка это или подписка».
 *
 * Распознаётся:
 *  - URL подписки `https://…` (и обёртки панелей/клиентов: `sing-box://import-remote-profile?url=`,
 *    `clash://install-config?url=`, `hiddify://import/<url>`, `v2rayng://install-sub?url=`);
 *  - зашифрованная ссылка подписки `hydra://crypt1/…` (0.8.4-rc1, аналог incy-link-encoder):
 *    URL внутри, в чате/на скриншоте не светится;
 *  - одна или несколько ссылок серверов (`vless://`, `vmess://`, …), в т.ч. списком по строке;
 *  - base64-блоб подписки;
 *  - целиком вставленный `.conf` WireGuard/AmneziaWG.
 * JSON-конфиги (sing-box/Xray целиком) пока не поддерживаются — это честный отказ,
 * а не «не удалось разобрать».
 */
object ImportDetector {

    sealed interface Result {
        data class SubscriptionUrl(val url: String, val nameHint: String) : Result
        data class Servers(val profiles: List<ServerProfile>) : Result
        data class Unsupported(val reason: Reason) : Result
        /** Ссылка `openflux://v1/…` не годится: [code] — код ошибки ядра (`damaged`, `short_secret`, …) или `stream_unsupported`. */
        data class OpenFluxError(val code: String) : Result
        data object Empty : Result
    }

    enum class Reason { JSON_CONFIG, HYDRA_LINK, UNKNOWN }

    private val wrapperSchemes = setOf(
        "sing-box", "clash", "clashmeta", "hiddify", "v2rayng", "v2raytun", "happ", "flclash", "olcbox",
    )

    fun classify(raw: String): Result {
        val text = raw.trim().removePrefix("﻿").trim()
        if (text.isEmpty()) return Result.Empty

        // 0. Шифрованная hydra://crypt1/… — расшифровать; повреждённая — отдельная причина,
        //    а не общее «не распознано» (пользователь явно ожидал импорт).
        if (text.startsWith("hydra://crypt1/", ignoreCase = true)) {
            HydraCryptLink.decode(text)?.let { return Result.SubscriptionUrl(it.url, it.name ?: nameFrom(it.url)) }
            return Result.Unsupported(Reason.HYDRA_LINK)
        }

        // 1. Обёртки deep-link'ов панелей и клиентов → внутри URL подписки.
        unwrapDeepLink(text)?.let { return Result.SubscriptionUrl(it, nameFrom(text)) }

        // 2. Голый URL подписки: одна строка, http/https.
        if ((text.startsWith("http://") || text.startsWith("https://")) && text.none { it.isWhitespace() }) {
            return Result.SubscriptionUrl(text.substringBefore('#'), nameFrom(text))
        }

        // 3. JSON целиком. Цельный .conf тоже начинается с «[» («[Interface]») — это не JSON-массив.
        val isConf = "[Interface]" in text
        if (!isConf && (text.startsWith("{") || text.startsWith("["))) return Result.Unsupported(Reason.JSON_CONFIG)

        // 4. Серверы: цельный .conf (у него нет "://"), либо ссылки списком / base64-подписка.
        val profiles = if (isConf) {
            listOfNotNull(runCatching { LinkParser.parseLine(text) }.getOrNull())
        } else {
            LinkParser.parseSubscription(text)
        }
        if (profiles.isNotEmpty()) return Result.Servers(profiles)

        text.lineSequence().map { it.trim() }.firstNotNullOfOrNull { l ->
            if (l.startsWith("openflux://", ignoreCase = true)) OpenFluxLink.errorCode(l) else null
        }?.let { return Result.OpenFluxError(it) }

        return Result.Unsupported(Reason.UNKNOWN)
    }

    /** `scheme://…?url=<https-url>` / `hiddify://import/<url>` → сам URL. */
    internal fun unwrapDeepLink(text: String): String? {
        val scheme = text.substringBefore("://", "").lowercase()
        if (scheme !in wrapperSchemes) return null
        val body = text.substringAfter("://").substringBefore('#')
        val query = body.substringAfter('?', "")
        query.split('&').forEach { kv ->
            if (kv.startsWith("url=", ignoreCase = true)) {
                val v = decode(kv.substring(4))
                if (v.startsWith("http://") || v.startsWith("https://")) return v
            }
        }
        // hiddify://import/https://…
        val direct = Regex("^(?:import/)?(https?://.+)$", RegexOption.IGNORE_CASE).find(body)
        return direct?.let { decode(it.groupValues[1]) }
    }

    /** Имя подписки: `#фрагмент` ссылки, иначе хост. */
    internal fun nameFrom(text: String): String {
        val frag = text.substringAfter('#', "")
        if (frag.isNotBlank()) return decode(frag).trim()
        val url = unwrapDeepLink(text) ?: text.substringBefore('#')
        val host = url.substringAfter("://", url).substringBefore('/').substringBefore(':').substringBefore('?')
        return host.ifBlank { "Subscription" }
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
