package ru.gidravpn.hydra.data.subscription

import org.json.JSONObject
import ru.gidravpn.hydra.data.dpi.DpiSettings
import ru.gidravpn.hydra.data.dpi.DpiStrategies
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Ссылка профиля «Обход DPI (ByeDPI)» (0.7.13): `byedpi://?s=<стратегия, URL-кодированная>#<имя>`.
 * Стратегия — аргументы `ciadpi` (см. [DpiStrategies]); пустая — [DpiStrategies.DEFAULT].
 * Профиль: address = 127.0.0.1, порт не используется, стратегия — в extra.strategy.
 */
object ByeDpiLink {

    private const val PREFIX = "byedpi://"

    fun parse(link: String): ServerProfile? {
        if (!link.startsWith(PREFIX, ignoreCase = true)) return null
        val body = link.substring(PREFIX.length)
        val name = body.substringAfter('#', "").let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }.ifBlank { "Обход DPI" }
        val query = body.substringBefore('#').substringAfter('?', "")
        val s = query.split('&').firstOrNull { it.startsWith("s=") }?.substring(2)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }.orEmpty().ifBlank { DpiStrategies.DEFAULT }
        return ServerProfile(name = name, protocolId = Protocol.BYEDPI.id, address = "127.0.0.1", port = DpiSettings.DEFAULT_PORT,
            extra = JSONObject().put("strategy", s).toString(), flag = "🛡️")
    }

    fun build(p: ServerProfile): String {
        val s = runCatching { JSONObject(p.extra).optString("strategy") }.getOrDefault("").ifBlank { DpiStrategies.DEFAULT }
        return "$PREFIX?s=${URLEncoder.encode(s, "UTF-8")}#${URLEncoder.encode(p.name, "UTF-8").replace("+", "%20")}"
    }
}
