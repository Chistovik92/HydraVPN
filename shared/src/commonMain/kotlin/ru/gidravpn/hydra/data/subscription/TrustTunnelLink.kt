package ru.gidravpn.hydra.data.subscription

import org.json.JSONObject
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import java.net.URLDecoder

/**
 * Импорт TrustTunnel.
 *
 * Канонический формат — `tt://` deep-link, который экспортирует
 * `trusttunnel_client --export-deeplink` (upstream TrustTunnelClient, Apache-2.0);
 * его битовую схему декодирует нативный `trusttunnel_deeplink_decode`
 * (deeplink-ffi). Здесь реализовано **совместимое подмножество** без нативной
 * библиотеки:
 *
 *  1. `trusttunnel://` / `tt://` + `[endpoint]` TOML (как отдаёт deeplink-ffi
 *     `trusttunnel_deeplink_decode`) — разбирается построчно;
 *  2. `tt://user:pass@host:port?...` / `trusttunnel://user:pass@host:port?...`
 *     (человекочитаемая форма Hydra), query: `sni`/`hostname`, `custom_sni`,
 *     `upstream`/`upstream_protocol`=http2|http3, `insecure`/`skip_verification`,
 *     `tls_profile`, `has_ipv6`, `client_random`, `client_random_auth_key`,
 *     `anti_dpi`, `name`, `certificate`.
 *
 * Полная поддержка официального `tt://` требует нативного deeplink-ffi;
 * для таких ссылок используйте `trusttunnel_client --setup-wizard
 * --mode non-interactive --endpoint_config ... ` → `trusttunnel_client.toml`.
 */
object TrustTunnelLink {

    const val SCHEME_TT = "tt"
    const val SCHEME_TRUSTTUNNEL = "trusttunnel"

    /**
     * `tt://?<base64url/base64>` без user-info/query-полей — официальный бинарный deep-link
     * (`trusttunnel_deeplink::encode`). Не путать с `tt://user:pass@host:port?...`.
     */
    fun isOfficialBinaryDeeplink(link: String): Boolean {
        if (!link.substringBefore("://").lowercase().let { it == SCHEME_TT || it == SCHEME_TRUSTTUNNEL }) return false
        val body = link.substringAfter("://").trim()
        if (!body.startsWith("?")) return false
        val q = body.removePrefix("?")
        if (q.isEmpty() || '@' in body || '=' in q) return false
        // base64/base64url без паддинга
        return q.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '+' || it == '/' }
    }

    fun isTrustTunnelLink(link: String): Boolean {
        val s = link.substringBefore("://").lowercase()
        return s == SCHEME_TT || s == SCHEME_TRUSTTUNNEL
    }

    fun parse(link: String): ServerProfile? {
        val body = link.substringAfter("://").trim()
        if (body.isEmpty()) return null
        // Официальный бинарный `tt://?<base64>` (экспорт trusttunnel_client --export-deeplink):
        // канонический декодер — нативный trusttunnel_deeplink_decode (deeplink-ffi) → [endpoint] TOML.
        // Чистый Kotlin здесь НЕ декодирует; вернуть null, чтобы вызывающий использовал нативный декодер.
        if (isOfficialBinaryDeeplink(link)) return null
        return runCatching {
            if ("[endpoint]" in body || body.startsWith("hostname") || body.startsWith("[endpoint")) {
                fromEndpointToml(body)
            } else {
                fromUri(body)
            }
        }.getOrNull()
    }

    // ---- форма 1: [endpoint] TOML ----
    private fun fromEndpointToml(body: String): ServerProfile? {
        // вырезаем секцию [endpoint] до следующей [..]
        val section = body.substringAfter("[endpoint]", body)
            .let { if (it.startsWith("[endpoint")) it.substringAfter("]", it) else it }
            .substringBefore("\n[", "")
        val kv = parseTomlKv(section)
        val hostname = kv["hostname"] ?: return null
        val addresses = parseTomlStringArray(kv["addresses"]).ifEmpty {
            listOf(hostname.substringAfter('|').let { if (':' in it && !it.startsWith("[")) "$it:443" else "$it:443" })
        }
        val (host, port) = splitHostPort(addresses.first())
        val username = kv["username"].orEmpty()
        val password = kv["password"].orEmpty()
        val name = kv["name"]?.ifBlank { null } ?: "TrustTunnel ${kv["hostname"]}"
        return ServerProfile(
            name = name,
            protocolId = Protocol.TRUSTTUNNEL.id,
            address = host,
            port = port,
            uuidOrPassword = if (username.isNotEmpty()) "$username:$password" else password,
            sni = sniOf(kv["hostname"].orEmpty(), kv["custom_sni"]),
            transport = TrustTunnelTransport(kv["upstream_protocol"]).wire,
            security = if (kv["skip_verification"] == "true") "insecure" else "tls",
            extra = JSONObject().apply {
                put("username", username)
                put("password", password)
                put("hostname", hostname)
                kv["custom_sni"]?.let { put("custom_sni", it) }
                put("upstream_protocol", kv["upstream_protocol"].orEmpty().ifBlank { "http2" })
                put("tls_profile", kv["tls_profile"].orEmpty().ifBlank { "chrome" })
                put("skip_verification", kv["skip_verification"] == "true")
                put("has_ipv6", kv["has_ipv6"] != "false")
                kv["client_random"]?.takeIf { it.isNotEmpty() }?.let { put("client_random", it) }
                kv["client_random_auth_key"]?.takeIf { it.isNotEmpty() }?.let { put("client_random_auth_key", it) }
                put("anti_dpi", kv["anti_dpi"] == "true")
                kv["certificate"]?.takeIf { it.isNotEmpty() }?.let { put("certificate", it) }
                parseTomlStringArray(kv["dns_upstreams"]).takeIf { it.isNotEmpty() }?.let { put("dns_upstreams", org.json.JSONArray(it)) }
            }.toString(),
        )
    }

    // ---- форма 2: tt://user:pass@host:port?... ----
    private fun fromUri(body: String): ServerProfile? {
        val uri = body
        val at = uri.lastIndexOf('@')
        val userInfoRaw = if (at >= 0) uri.substring(0, at) else ""
        val afterAt = if (at >= 0) uri.substring(at + 1) else uri
        val (hostPort, queryRaw, fragmentRaw) = splitHostQueryFragment(afterAt)
        val (host, port) = splitHostPort(hostPort)

        val userInfo = urlDecode(userInfoRaw)
        val username = userInfo.substringBefore(":")
        val password = if (':' in userInfo) userInfo.substringAfter(":") else ""
        val q = parseQuery(queryRaw)

        val sni = q["sni"].orEmpty().ifBlank { q["hostname"].orEmpty().ifBlank { q["custom_sni"].orEmpty() } }
        val hostname = q["hostname"].orEmpty().ifBlank { sni.ifBlank { host } }
        val insecure = truthy(q["insecure"]) || truthy(q["skip_verification"]) || truthy(q["allow_insecure"])
        val name = urlDecode(fragmentRaw).ifBlank { q["name"].orEmpty() }.ifBlank { "TrustTunnel $host" }

        return ServerProfile(
            name = name,
            protocolId = Protocol.TRUSTTUNNEL.id,
            address = host,
            port = port,
            uuidOrPassword = if (username.isNotEmpty()) "$username:$password" else password,
            sni = sniOf(hostname, q["custom_sni"]),
            transport = TrustTunnelTransport(q["upstream"] ?: q["upstream_protocol"]).wire,
            security = if (insecure) "insecure" else "tls",
            extra = JSONObject().apply {
                put("username", username)
                put("password", password)
                put("hostname", hostname)
                q["custom_sni"]?.let { put("custom_sni", it) }
                put("upstream_protocol", (q["upstream"] ?: q["upstream_protocol"]).orEmpty().ifBlank { "http2" })
                put("tls_profile", q["tls_profile"].orEmpty().ifBlank { "chrome" })
                put("skip_verification", insecure)
                put("has_ipv6", !(q["has_ipv6"] == "0" || q["has_ipv6"] == "false"))
                q["client_random"]?.let { put("client_random", it) }
                q["client_random_auth_key"]?.let { put("client_random_auth_key", it) }
                put("anti_dpi", truthy(q["anti_dpi"]))
                q["certificate"]?.let { put("certificate", it) }
            }.toString(),
        )
    }

    // ---- helpers ----
    private fun TrustTunnelTransport(raw: String?): TrustTunnelTransportResult = TrustTunnelTransportResult(raw)
    private class TrustTunnelTransportResult(raw: String?) {
        val wire: String = when (raw?.trim()?.lowercase()) { "http3", "h3", "quic" -> "http3"; else -> "http2" }
    }

    private fun sniOf(hostname: String, customSni: String?): String {
        if (!customSni.isNullOrBlank()) return customSni
        return if ('|' in hostname) hostname.substringBefore('|') else hostname.substringAfter('|').ifBlank { hostname }
    }

    private fun splitHostPort(hostPortIn: String): Pair<String, Int> {
        var hp = hostPortIn.trim()
        // user info не должен здесь остаться
        if (hp.startsWith("[")) {
            val close = hp.indexOf(']')
            if (close >= 0) {
                val host = hp.substring(1, close)
                val rest = hp.substring(close + 1)
                val port = rest.removePrefix(":").toIntOrNull() ?: 443
                return host to port
            }
        }
        val colon = hp.lastIndexOf(':')
        return if (colon >= 0 && hp.substring(colon + 1).toIntOrNull() != null) {
            hp.substring(0, colon) to (hp.substring(colon + 1).toIntOrNull() ?: 443)
        } else hp to 443
    }

    private fun splitHostQueryFragment(s: String): Triple<String, String, String> {
        var rest = s
        val fragIdx = rest.indexOf('#')
        val fragment = if (fragIdx >= 0) rest.substring(fragIdx + 1).also { rest = rest.substring(0, fragIdx) } else ""
        val qIdx = rest.indexOf('?')
        val query = if (qIdx >= 0) rest.substring(qIdx + 1).also { rest = rest.substring(0, qIdx) } else ""
        return Triple(rest, query, fragment)
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.isNotBlank() }.associate { part ->
            val k = part.substringBefore('='); val v = part.substringAfter('=', "")
            urlDecode(k) to urlDecode(v)
        }

    private fun urlDecode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun truthy(s: String?): Boolean = s?.lowercase() in setOf("1", "true", "yes", "on")

    /** Минимальный построчный разбор `key = value` TOML (строки, bool, числа). */
    private fun parseTomlKv(section: String): Map<String, String> =
        section.lineSequence().mapNotNull { raw ->
            val line = raw.trim().substringBefore('#').trim()
            if (line.isEmpty() || line.startsWith("[") || '=' !in line) return@mapNotNull null
            val key = line.substringBefore('=').trim().trim('"')
            val value = line.substringAfter('=').trim()
            key to tomlUnquote(value)
        }.toMap()

    private fun tomlUnquote(v: String): String = when {
        v.startsWith("\"") && v.endsWith("\"") && v.length >= 2 ->
            v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\\", "\\")
        else -> v
    }

    /** `["a", "b"]` → list. */
    private fun parseTomlStringArray(v: String?): List<String> {
        if (v == null) return emptyList()
        val inner = v.trim().removePrefix("[").removeSuffix("]").trim()
        if (inner.isEmpty()) return emptyList()
        return inner.split(',').map { tomlUnquote(it.trim()) }.filter { it.isNotEmpty() }
    }
}
