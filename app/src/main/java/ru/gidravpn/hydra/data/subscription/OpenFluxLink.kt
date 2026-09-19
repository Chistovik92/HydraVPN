package ru.gidravpn.hydra.data.subscription

import org.json.JSONObject
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * OpenFlux (github.com/p1neappleXpress/OpenFlux, GPL-3.0) настраивается только флагами командной
 * строки — общепринятой ссылки у него нет. Здесь — **соглашение Hydra** (не апстрима), чтобы профиль
 * можно было импортировать, отсканировать и переслать:
 *
 *   openflux://<transport>?url=<url>&maxToken=<t>&maxUid=<u>&codec=<batched|legacy>&key=<секрет>#<имя>
 *
 * transport: yandex | vyandex | oneme | cupsonline | mailru. Для `oneme` вместо url — maxToken и maxUid.
 * key — общий секрет шифрования AES-256-GCM (`--encryption-key-file`), необязателен.
 * Соответствие профилю: transport = transport, address = url, uuidOrPassword = key,
 * extra = {maxToken, maxUid, codec}; порт не используется (0).
 */
object OpenFluxLink {

    val TRANSPORTS = listOf("yandex", "vyandex", "oneme", "cupsonline", "mailru")

    fun parse(link: String): ServerProfile? {
        if (!link.startsWith("openflux://", ignoreCase = true)) return null
        val body = link.substring("openflux://".length)
        val name = decode(body.substringAfter('#', "")).trim()
        val main = body.substringBefore('#')
        val transport = main.substringBefore('?').trim().lowercase()
        if (transport !in TRANSPORTS) return null
        val q = main.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { it.substringBefore('=') to decode(it.substringAfter('=')) }

        val url = q["url"].orEmpty()
        val token = q["maxToken"].orEmpty()
        val uid = q["maxUid"].orEmpty()
        // Без обязательных параметров транспорта клиент всё равно не запустится.
        if (transport == "oneme") {
            if (token.isEmpty() || uid.isEmpty()) return null
        } else if (transport != "cupsonline" && url.isEmpty()) {
            return null
        }

        return ServerProfile(
            name = name.ifBlank { "OpenFlux $transport" },
            protocolId = Protocol.OPENFLUX.id,
            address = url,
            port = 0,
            uuidOrPassword = q["key"].orEmpty(),
            transport = transport,
            extra = JSONObject().put("maxToken", token).put("maxUid", uid)
                .put("codec", q["codec"].orEmpty().ifBlank { "batched" }).toString(),
        )
    }

    fun build(p: ServerProfile): String {
        val extra = runCatching { JSONObject(p.extra) }.getOrDefault(JSONObject())
        val q = linkedMapOf<String, String>()
        if (p.address.isNotEmpty()) q["url"] = p.address
        extra.optString("maxToken").takeIf { it.isNotEmpty() }?.let { q["maxToken"] = it }
        extra.optString("maxUid").takeIf { it.isNotEmpty() }?.let { q["maxUid"] = it }
        extra.optString("codec").takeIf { it.isNotEmpty() && it != "batched" }?.let { q["codec"] = it }
        if (p.uuidOrPassword.isNotEmpty()) q["key"] = p.uuidOrPassword
        val qs = q.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        return "openflux://${p.transport}" + (if (qs.isEmpty()) "" else "?$qs") + "#${enc(p.name)}"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
