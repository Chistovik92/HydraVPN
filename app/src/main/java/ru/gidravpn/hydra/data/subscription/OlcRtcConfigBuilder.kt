package ru.gidravpn.hydra.data.subscription

import org.json.JSONObject
import ru.gidravpn.hydra.data.model.ServerProfile

/**
 * Профиль olcRTC → YAML клиента (`mode: cnc`) для `olcrtc <config.yaml>`.
 * Схема — docs/configuration.md в openlibrecommunity/olcrtc. Строки — JSON-скаляры
 * (валидный YAML), поэтому кавычки и спецсимволы в названии комнаты безопасны.
 */
object OlcRtcConfigBuilder {

    /** Ключ параметра из URI → (секция YAML, поле). Таблица — docs/uri.md. */
    private val PARAM_MAP = mapOf(
        "vp8-fps" to ("vp8" to "fps"),
        "vp8-batch" to ("vp8" to "batch_size"),
        "fps" to ("sei" to "fps"),
        "batch" to ("sei" to "batch_size"),
        "frag" to ("sei" to "fragment_size"),
        "ack-ms" to ("sei" to "ack_timeout_ms"),
        "video-w" to ("video" to "width"),
        "video-h" to ("video" to "height"),
        "video-fps" to ("video" to "fps"),
        "video-codec" to ("video" to "codec"),
        "video-qr-size" to ("video" to "qr_size"),
        "video-qr-recovery" to ("video" to "qr_recovery"),
        "video-tile-module" to ("video" to "tile_module"),
        "video-tile-rs" to ("video" to "tile_rs"),
    )

    fun build(p: ServerProfile, socksPort: Int, dns: String = "1.1.1.1:53"): String {
        val extra = runCatching { JSONObject(p.extra) }.getOrDefault(JSONObject())
        val provider = extra.optString("provider").ifBlank { "telemost" }
        val params = extra.optJSONObject("params") ?: JSONObject()

        val sections = linkedMapOf<String, MutableList<String>>()
        params.keys().forEach { k ->
            val (section, field) = PARAM_MAP[k] ?: return@forEach
            sections.getOrPut(section) { mutableListOf() } += "  $field: ${scalar(params.optString(k))}"
        }

        return buildString {
            appendLine("mode: cnc")
            appendLine("auth:")
            appendLine("  provider: ${q(provider)}")
            appendLine("room:")
            appendLine("  id: ${q(p.address)}")
            appendLine("crypto:")
            appendLine("  key: ${q(p.uuidOrPassword)}")
            appendLine("net:")
            appendLine("  transport: ${q(p.transport.ifBlank { "datachannel" })}")
            // У Go на Android нет /etc/resolv.conf — без явного резолвера имена не разрешатся.
            appendLine("  dns: ${q(dns)}")
            appendLine("liveness:")
            appendLine("  interval: 10s")
            appendLine("  timeout: 15s")
            appendLine("  failures: 4")
            appendLine("socks:")
            appendLine("  host: \"127.0.0.1\"")
            appendLine("  port: $socksPort")
            sections.forEach { (name, lines) ->
                appendLine("$name:")
                lines.forEach { appendLine(it) }
            }
            appendLine("debug: false")
        }
    }

    private fun q(s: String) = JSONObject.quote(s)

    /** Число — как есть, иначе строка в кавычках. */
    private fun scalar(v: String) = if (v.toLongOrNull() != null) v else q(v)
}
