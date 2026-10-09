package ru.gidravpn.hydra.data.subscription

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Официальная ссылка OpenFlux `openflux://v1/<base64url(DEFLATE(JSON))>` (ядро: пакет `share`, docs/links.md апстрима).
 *
 * Апстрим требует, чтобы ссылки читало и делало только ядро; здесь — построчный порт `share.Decode/Encode/Validate`
 * (синхронный, без подпроцесса: разбор ссылки вызывается из синхронного LinkParser, а клиента может не быть в сборке).
 * Совпадение с ядром держат тесты: «замороженные» ссылки из `share/compat_test.go` и, когда бинарь собран, сверка
 * `openflux --parse-link` / `--make-link` (OpenFluxLiveTest). Коды ошибок — те же, что у ядра; тексты — свои, в строках приложений.
 */
object OpenFluxShare {
    const val PREFIX = "openflux://v1/"
    const val MIN_SECRET_CHARS = 16
    private const val MAX_PAYLOAD = 16 shl 10
    private val KNOWN_TYPES = setOf("yandex", "vyandex", "boards", "mailru", "cupsonline", "direct")
    private val STREAM_TYPES = setOf("cupsonline", "mailru")

    class Transport(val type: String, val url: String = "", val priority: Int = 0, val dial: String = "", val name: String = "")

    class Config(
        val name: String = "",
        val negotiate: Boolean = false,
        val codec: String = "",
        val secret: String = "",
        val context: String = "",
        val mode: String = "",
        val transports: List<Transport>,
    )

    /** [code] — код ядра (`damaged`, `short_secret`, …), [param] — значение, о котором речь. */
    class ShareException(val code: String, val param: String = "") : Exception("$code $param".trim())

    /** Ссылка, похожая на openflux://v1/… (чтобы отличать от старого соглашения Hydra `openflux://<транспорт>?…`). */
    fun isV1(link: String) = link.trim().lowercase().startsWith("openflux://v1/")

    fun validate(c: Config) {
        if (c.transports.isEmpty()) throw ShareException("no_transports")
        if (c.mode.isNotEmpty()) validateStream(c)
        if (c.transports.size > 1 && !c.negotiate) throw ShareException("several_need_session")
        val chars = c.secret.length // UTF-16, как считает ядро
        if (c.negotiate && chars < MIN_SECRET_CHARS) throw ShareException("session_secret", MIN_SECRET_CHARS.toString())
        if (c.secret.isNotEmpty() && chars < MIN_SECRET_CHARS) throw ShareException("short_secret", MIN_SECRET_CHARS.toString())
        if (c.codec.isNotEmpty() && c.codec != "batched" && c.codec != "legacy") throw ShareException("unknown_codec", c.codec)
        for (t in c.transports) {
            if (t.type !in KNOWN_TYPES) {
                if (t.type == "oneme") throw ShareException("not_shareable", t.type)
                throw ShareException("unknown_transport", t.type)
            }
            if (t.type == "direct") {
                if (t.dial.isEmpty()) throw ShareException("direct_no_dial")
                if (!c.negotiate) throw ShareException("direct_needs_session")
            }
        }
    }

    private fun validateStream(c: Config) {
        if (c.mode != "stream") throw ShareException("unknown_mode", c.mode)
        if (c.transports.size != 1) throw ShareException("stream_one_transport")
        val t = c.transports[0].type
        if (t !in STREAM_TYPES) throw ShareException("stream_transport", t)
        if (c.negotiate || c.secret.isNotEmpty()) throw ShareException("stream_plain_only")
    }

    fun decode(raw: String): Config {
        val link = raw.trim()
        if (!link.startsWith(PREFIX)) {
            if (link.lowercase().startsWith("openflux://") && !link.startsWith("openflux://")) throw ShareException("case_changed")
            if (link.startsWith("openflux://")) throw ShareException("unsupported_version")
            throw ShareException("not_link")
        }
        val body = link.removePrefix(PREFIX).filter { it !in " \t\r\n ​" }
            .replace('+', '-').replace('/', '_').trimEnd('=')
        val packed = try { Base64.getUrlDecoder().decode(body) } catch (_: IllegalArgumentException) { throw ShareException("damaged") }
        val json = inflate(packed)
        val o = try { JSONObject(json) } catch (_: JSONException) { throw ShareException("bad_payload") }
        val transports = try {
            val arr = o.optJSONArray("transports") ?: JSONArray()
            (0 until arr.length()).map {
                val t = arr.getJSONObject(it)
                Transport(t.optString("type"), t.optString("url"), t.optInt("priority"), t.optString("dial"), t.optString("name"))
            }
        } catch (_: JSONException) { throw ShareException("bad_payload") }
        val c = Config(o.optString("name"), o.optBoolean("negotiate"), o.optString("codec"), o.optString("secret"),
            o.optString("context"), o.optString("mode"), transports)
        validate(c)
        return c
    }

    private fun inflate(packed: ByteArray): String {
        val inf = Inflater(true)
        try {
            inf.setInput(packed)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inf.finished()) {
                val n = try { inf.inflate(buf) } catch (_: DataFormatException) { throw ShareException("damaged") }
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw ShareException("damaged")
                out.write(buf, 0, n)
                if (out.size() > MAX_PAYLOAD) throw ShareException("too_large")
            }
            return out.toString(Charsets.UTF_8.name())
        } finally { inf.end() }
    }

    /** JSON в том же порядке полей и с теми же omitempty, что у Go (`share.Config`). */
    fun toJson(c: Config): String = buildString {
        append('{')
        fun field(k: String, v: String) { if (v.isNotEmpty()) append(JSONObject.quote(k)).append(':').append(JSONObject.quote(v)).append(',') }
        field("name", c.name)
        if (c.negotiate) append("\"negotiate\":true,")
        field("codec", c.codec); field("secret", c.secret); field("context", c.context); field("mode", c.mode)
        append("\"transports\":[")
        append(c.transports.joinToString(",") { t ->
            buildString {
                append('{').append("\"type\":").append(JSONObject.quote(t.type))
                if (t.name.isNotEmpty()) append(",\"name\":").append(JSONObject.quote(t.name))
                if (t.url.isNotEmpty()) append(",\"url\":").append(JSONObject.quote(t.url))
                if (t.priority != 0) append(",\"priority\":").append(t.priority)
                if (t.dial.isNotEmpty()) append(",\"dial\":").append(JSONObject.quote(t.dial))
                append('}')
            }
        })
        append("]}")
    }

    fun encode(c: Config): String {
        validate(c)
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            d.setInput(toJson(c).toByteArray(Charsets.UTF_8)); d.finish()
            val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        } finally { d.end() }
    }
}
