package ru.gidravpn.hydra.data.subscription

import org.json.JSONObject
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile

/**
 * Компактный URI olcRTC (docs/uri.md в openlibrecommunity/olcrtc, «URI format v1»):
 *
 *   olcrtc://<Provider>?<Transport>[<key=value&…>]@<RoomID>#<EncryptionKey>$<MIMO>
 *
 * Разделители: `?` — транспорт, `<…>` — параметры транспорта, `@` — комната, `#` — ключ
 * (64 hex), `$` — свободный комментарий. Соответствие профилю:
 * address = RoomID, uuidOrPassword = ключ, transport = Transport,
 * extra = {provider, params:{…}}; порт не используется (0).
 */
object OlcRtcLink {

    fun parse(link: String): ServerProfile? {
        if (!link.startsWith("olcrtc://", ignoreCase = true)) return null
        val body = link.substring("olcrtc://".length).trim()
        val mimo = body.substringAfter('$', "").trim()
        val main = body.substringBefore('$')

        val key = main.substringAfterLast('#', "").trim()
        val withoutKey = main.substringBeforeLast('#', main)
        val head = withoutKey.substringBefore('@')
        val room = withoutKey.substringAfter('@', "").trim()
        if (head.isBlank() || room.isBlank() || key.isBlank()) return null

        val provider = head.substringBefore('?').trim().lowercase()
        val transportPart = head.substringAfter('?', "datachannel")
        val transport = transportPart.substringBefore('<').trim().ifBlank { "datachannel" }
        val payload = transportPart.substringAfter('<', "").substringBeforeLast('>', "")
        val params = JSONObject()
        payload.split('&').filter { '=' in it }.forEach {
            params.put(it.substringBefore('='), it.substringAfter('='))
        }
        if (provider.isEmpty()) return null

        return ServerProfile(
            name = mimo.ifBlank { "olcRTC $provider" },
            protocolId = Protocol.OLCRTC.id,
            address = room,
            port = 0,
            uuidOrPassword = key,
            transport = transport,
            extra = JSONObject().put("provider", provider).put("params", params).toString(),
        )
    }

    /** Профиль → URI (обратное к [parse]). */
    fun build(p: ServerProfile): String {
        val extra = runCatching { JSONObject(p.extra) }.getOrDefault(JSONObject())
        val provider = extra.optString("provider").ifBlank { "telemost" }
        val params = extra.optJSONObject("params") ?: JSONObject()
        val payload = params.keys().asSequence().joinToString("&") { "$it=${params.optString(it)}" }
        val transport = p.transport.ifBlank { "datachannel" } + if (payload.isEmpty()) "" else "<$payload>"
        return "olcrtc://$provider?$transport@${p.address}#${p.uuidOrPassword}" +
            if (p.name.isBlank()) "" else "$${p.name}"
    }
}
