package ru.gidravpn.hydra.data.subscription

import org.json.JSONArray
import org.json.JSONObject
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * OpenFlux (github.com/p1neappleXpress/OpenFlux, GPL-3.0). С 0.7.11 основная ссылка — **официальная** апстрима
 * `openflux://v1/<base64url(DEFLATE(JSON))>` ([OpenFluxShare]): её делают и читают официальные клиенты (Android, ПК, iOS) и
 * сам exit-узел (`--share`). Профиль из неё хранит сессию в `extra.session` (транспорты с приоритетами, режим, контекст),
 * ключ — в `uuidOrPassword`, [ServerProfile.transport] — тип единственного транспорта или `session`.
 *
 * Старое **соглашение Hydra** (до 0.7.11) только читается, новые ссылки в нём не создаются:
 *
 *   openflux://<transport>?url=<url>&maxToken=<t>&maxUid=<u>&codec=<batched|legacy>&key=<секрет>#<имя>
 *
 * transport: yandex | vyandex | oneme | cupsonline | mailru | direct (обычный TCP до своего exit-узла: `url=host:port`). Для `oneme` вместо url — maxToken и maxUid.
 * key — общий секрет шифрования AES-256-GCM (`--encryption-key-file`), необязателен.
 * Соответствие профилю: transport = transport, address = url, uuidOrPassword = key,
 * extra = {maxToken, maxUid, codec}; порт не используется (0).
 */
object OpenFluxLink {

    val TRANSPORTS = listOf("yandex", "vyandex", "oneme", "cupsonline", "mailru", "direct")

    /** Код ошибки ядра (`damaged`, `short_secret`, …) для ссылки `openflux://v1/`, которую нельзя использовать; null — всё в порядке. */
    fun errorCode(link: String): String? {
        if (!link.trim().startsWith("openflux://", ignoreCase = true)) return null
        if (!OpenFluxShare.isV1(link)) return null
        return try {
            val c = OpenFluxShare.decode(link)
            if (c.mode == "stream") "stream_unsupported" else null
        } catch (e: OpenFluxShare.ShareException) { e.code }
    }

    private fun fromShare(c: OpenFluxShare.Config): ServerProfile {
        val single = c.transports.singleOrNull()
        val first = c.transports.first()
        val session = JSONObject()
            .put("negotiate", c.negotiate).put("codec", c.codec).put("context", c.context).put("mode", c.mode)
            .put("transports", JSONArray(c.transports.map {
                JSONObject().put("type", it.type).put("url", it.url).put("priority", it.priority).put("dial", it.dial).put("name", it.name)
            }))
        return ServerProfile(
            name = c.name.ifBlank { "OpenFlux " + (single?.type ?: "session") },
            protocolId = Protocol.OPENFLUX.id,
            address = first.dial.ifEmpty { first.url },
            port = 0,
            uuidOrPassword = c.secret,
            transport = single?.type ?: "session",
            extra = JSONObject().put("session", session).put("codec", c.codec.ifBlank { "batched" }).toString(),
        )
    }

    /** Профиль → конфигурация официальной ссылки; null — профиль нельзя выразить ссылкой v1 (MAX, ключ короче 16 знаков…). */
    private fun toShare(p: ServerProfile, extra: JSONObject): OpenFluxShare.Config? {
        extra.optJSONObject("session")?.let { s ->
            val arr = s.optJSONArray("transports") ?: return null
            return OpenFluxShare.Config(p.name, s.optBoolean("negotiate"), s.optString("codec"), p.uuidOrPassword, s.optString("context"),
                s.optString("mode"), (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                    OpenFluxShare.Transport(it.optString("type"), it.optString("url"), it.optInt("priority"), it.optString("dial"), it.optString("name"))
                })
        }
        val direct = p.transport == "direct"
        val t = if (direct) OpenFluxShare.Transport("direct", dial = p.address, priority = 100)
        else OpenFluxShare.Transport(p.transport, url = p.address)
        return OpenFluxShare.Config(p.name, negotiate = direct, codec = extra.optString("codec").takeIf { it == "legacy" }.orEmpty(),
            secret = p.uuidOrPassword, transports = listOf(t))
    }

    fun parse(link: String): ServerProfile? {
        if (!link.trim().startsWith("openflux://", ignoreCase = true)) return null
        if (OpenFluxShare.isV1(link)) {
            val c = runCatching { OpenFluxShare.decode(link) }.getOrNull() ?: return null
            if (c.mode == "stream") return null // клиентская часть режима «без сервера» — в 0.8.0
            return fromShare(c)
        }
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
        // Без обязательных параметров транспорта клиент всё равно не запустится (для direct нужен и ключ).
        if (transport == "direct" && (url.isEmpty() || q["key"].isNullOrEmpty())) return null
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
        runCatching { toShare(p, extra)?.let { OpenFluxShare.encode(it) } }.getOrNull()?.let { return it }
        return buildLegacy(p, extra)
    }

    /** Старое соглашение Hydra — только для профилей, которые официальная ссылка выразить не может. */
    private fun buildLegacy(p: ServerProfile, extra: JSONObject): String {
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