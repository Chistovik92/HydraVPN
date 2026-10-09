package ru.gidravpn.hydra.data.subscription

import org.json.JSONObject
import ru.gidravpn.hydra.data.model.ServerProfile

/**
 * Профиль OpenFlux → аргументы клиента `openflux --role client --inbound socks5 …` (одни и те же на Android и ПК).
 * [keyFile] — путь к файлу с общим ключом шифрования (его пишет вызывающий и удаляет после работы); null — без шифрования.
 *
 * Профиль из официальной ссылки `openflux://v1/` хранит сессию в `extra.session` ([OpenFluxLink]): несколько транспортов с
 * приоритетами (`--negotiate --transports=direct:100,yandex:50` и адрес документа для каждого типа), необязательный
 * контекст шифрования (`--session-context`). Профиль старого вида — один транспорт в полях `transport`/`address`.
 *
 * `direct` — обычный TCP до своего exit-узла (`openflux --role exit --transports direct:100 --direct-listen host:port
 * --encryption-key-file …`). У апстрима это режим «нескольких транспортов»: флаг `--transport direct` не существует,
 * а ключ обязателен — проверено на настоящем узле (см. OpenFluxLiveTest).
 */
object OpenFluxArgs {
    private val URL_FLAGS = mapOf(
        "yandex" to "--yandex-url", "vyandex" to "--vyandex-url", "boards" to "--boards-url",
        "mailru" to "--mailru-url", "cupsonline" to "--cupsonline-url",
    )

    fun build(profile: ServerProfile, socksPort: Int, keyFile: String?): List<String> {
        val extra = runCatching { JSONObject(profile.extra) }.getOrDefault(JSONObject())
        val session = extra.optJSONObject("session")
        val transport = profile.transport
        return buildList {
            add("--role"); add("client")
            add("--inbound"); add("socks5")
            add("--socks5"); add("127.0.0.1:$socksPort")
            when {
                session != null -> addAll(sessionArgs(session))
                transport == "direct" -> { add("--transports"); add("direct:100"); add("--direct-dial"); add(profile.address) }
                transport == "oneme" -> {
                    add("--transport"); add(transport)
                    add("--maxToken"); add(extra.optString("maxToken"))
                    add("--maxUid"); add(extra.optString("maxUid"))
                }
                else -> {
                    add("--transport"); add(transport)
                    if (profile.address.isNotEmpty()) { add("--url"); add(profile.address) }
                }
            }
            val codec = session?.optString("codec").orEmpty().ifEmpty { extra.optString("codec") }
            if (codec == "legacy") { add("--codec"); add(codec) }
            if (keyFile != null && profile.uuidOrPassword.isNotEmpty()) {
                add("--encryption-key-file"); add(keyFile)
            }
        }
    }

    private fun sessionArgs(s: JSONObject): List<String> {
        val arr = s.optJSONArray("transports") ?: return emptyList()
        val ts = (0 until arr.length()).map { arr.getJSONObject(it) }
        val negotiate = s.optBoolean("negotiate")
        return buildList {
            if (ts.size == 1 && !negotiate && ts[0].optString("type") != "direct") {
                // Один транспорт без сессии — как профиль старого вида.
                add("--transport"); add(ts[0].optString("type"))
                ts[0].optString("url").takeIf { it.isNotEmpty() }?.let { add("--url"); add(it) }
            } else {
                if (negotiate) add("--negotiate")
                add("--transports")
                add(ts.joinToString(",") { t ->
                    val p = t.optInt("priority")
                    t.optString("type") + if (p != 0) ":$p" else ""
                })
                val seen = mutableSetOf<String>()
                for (t in ts) {
                    val type = t.optString("type")
                    if (!seen.add(type)) continue // флаг адреса один на тип
                    if (type == "direct") t.optString("dial").takeIf { it.isNotEmpty() }?.let { add("--direct-dial"); add(it) }
                    else URL_FLAGS[type]?.let { flag -> t.optString("url").takeIf { it.isNotEmpty() }?.let { add(flag); add(it) } }
                }
            }
            s.optString("context").takeIf { it.isNotEmpty() }?.let { add("--session-context"); add(it) }
        }
    }
}
