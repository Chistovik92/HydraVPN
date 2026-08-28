package ru.gidravpn.hydra.data.subscription

import android.net.Uri
import android.util.Base64
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Парсер стандартных ссылок-конфигов, которые выдают панели x-ui / 3x-ui /
 * PasarGuard / Remnawave. Форматы соответствуют де-факто стандарту экосистемы
 * v2ray/Xray/sing-box. См. docs/PROTOCOLS.md.
 */
object LinkParser {

    /** Разбирает содержимое подписки: plain-список ссылок ИЛИ base64-блоб. */
    fun parseSubscription(raw: String, subscriptionId: Long? = null): List<ServerProfile> {
        val text = maybeBase64Decode(raw.trim())
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && "://" in it }
            .mapNotNull { runCatching { parseLine(it) }.getOrNull() }
            .map { it.copy(subscriptionId = subscriptionId) }
            .toList()
    }

    fun parseLine(link: String): ServerProfile? {
        val scheme = link.substringBefore("://").lowercase()
        return when (scheme) {
            "vless"     -> parseVless(link)
            "trojan"    -> parseTrojan(link)
            "vmess"     -> parseVmess(link)
            "ss"        -> parseShadowsocks(link)
            "hysteria2", "hy2" -> parseHysteria2(link)
            "tuic"      -> parseTuic(link)
            "wireguard", "wg" -> WireGuardParser.toProfile(link, "WireGuard", isAmnezia = false)
            "awg", "amnezia" -> WireGuardParser.toProfile(link, "AmneziaWG", isAmnezia = true)
            // вставленный целиком .conf (без схемы): AWG-параметры → AmneziaWG, иначе WireGuard
            else -> if ("[Interface]" in link) {
                val awg = Regex("^(Jc|Jmin|Jmax|S1|S2|H[1-4]|I[1-5])\\s*=", RegexOption.IGNORE_CASE)
                    .containsMatchIn(link)
                WireGuardParser.toProfile(link, if (awg) "AmneziaWG" else "WireGuard", isAmnezia = awg)
            } else null
        }
    }

    // vless://uuid@host:port?type=ws&security=reality&pbk=...&sid=...&sni=...&flow=...#name
    private fun parseVless(link: String): ServerProfile {
        val uri = Uri.parse(link)
        val q = uri.queryMap()
        val extra = JSONObject().apply {
            q["pbk"]?.let { put("reality_pbk", it) }
            q["sid"]?.let { put("reality_sid", it) }
        }
        return ServerProfile(
            name = tag(uri) ?: "VLESS ${uri.host}",
            protocolId = Protocol.VLESS.id,
            address = uri.host.orEmpty(),
            port = uri.port.takeIf { it > 0 } ?: 443,
            uuidOrPassword = uri.userInfo.orEmpty(),
            flow = q["flow"].orEmpty(),
            sni = q["sni"] ?: q["host"].orEmpty(),
            transport = q["type"] ?: "tcp",
            transportPath = q["path"] ?: q["serviceName"].orEmpty(),
            security = q["security"] ?: "none",
            alpn = q["alpn"].orEmpty(),
            fingerprint = q["fp"] ?: "chrome",
            extra = extra.toString(),
        )
    }

    // trojan://password@host:port?sni=...&type=...#name
    private fun parseTrojan(link: String): ServerProfile {
        val uri = Uri.parse(link)
        val q = uri.queryMap()
        return ServerProfile(
            name = tag(uri) ?: "Trojan ${uri.host}",
            protocolId = Protocol.TROJAN.id,
            address = uri.host.orEmpty(),
            port = uri.port.takeIf { it > 0 } ?: 443,
            uuidOrPassword = uri.userInfo.orEmpty(),
            sni = q["sni"] ?: q["peer"].orEmpty(),
            transport = q["type"] ?: "tcp",
            transportPath = q["path"] ?: q["serviceName"].orEmpty(),
            security = q["security"] ?: "tls",
            alpn = q["alpn"].orEmpty(),
        )
    }

    // vmess://<base64 json>
    private fun parseVmess(link: String): ServerProfile {
        val json = JSONObject(String(b64(link.removePrefix("vmess://"))))
        return ServerProfile(
            name = json.optString("ps").ifBlank { "VMess ${json.optString("add")}" },
            protocolId = Protocol.VMESS.id,
            address = json.optString("add"),
            port = json.optInt("port", 443),
            uuidOrPassword = json.optString("id"),
            sni = json.optString("sni").ifBlank { json.optString("host") },
            transport = json.optString("net", "tcp"),
            transportPath = json.optString("path"),
            security = if (json.optString("tls") == "tls") "tls" else "none",
            alpn = json.optString("alpn"),
            extra = JSONObject().put("aid", json.optInt("aid", 0)).toString(),
        )
    }

    // ss://base64(method:password)@host:port#name   ИЛИ   ss://base64(method:password@host:port)#name
    private fun parseShadowsocks(link: String): ServerProfile {
        val body = link.removePrefix("ss://")
        val name = body.substringAfter("#", "").let { if (it.isEmpty()) "" else urlDecode(it) }
        val core = body.substringBefore("#")
        val (method, password, host, port) = decodeSs(core)
        return ServerProfile(
            name = name.ifBlank { "SS $host" },
            protocolId = Protocol.SHADOWSOCKS.id,
            address = host, port = port,
            uuidOrPassword = password,
            extra = JSONObject().put("method", method).toString(),
        )
    }

    // hysteria2://password@host:port?sni=...&obfs=...#name
    private fun parseHysteria2(link: String): ServerProfile {
        val uri = Uri.parse(link.replaceFirst("hy2://", "hysteria2://"))
        val q = uri.queryMap()
        return ServerProfile(
            name = tag(uri) ?: "Hysteria2 ${uri.host}",
            protocolId = Protocol.HYSTERIA2.id,
            address = uri.host.orEmpty(),
            port = uri.port.takeIf { it > 0 } ?: 443,
            uuidOrPassword = uri.userInfo.orEmpty(),
            sni = q["sni"].orEmpty(),
            security = "tls",
            extra = JSONObject().apply {
                q["obfs"]?.let { put("obfs", it) }
                q["obfs-password"]?.let { put("obfs_password", it) }
            }.toString(),
        )
    }

    // tuic://uuid:password@host:port?sni=...&congestion_control=bbr#name
    private fun parseTuic(link: String): ServerProfile {
        val uri = Uri.parse(link)
        val q = uri.queryMap()
        val userInfo = uri.userInfo.orEmpty()
        val uuid = userInfo.substringBefore(":")
        val pass = userInfo.substringAfter(":", "")
        return ServerProfile(
            name = tag(uri) ?: "TUIC ${uri.host}",
            protocolId = Protocol.TUIC.id,
            address = uri.host.orEmpty(),
            port = uri.port.takeIf { it > 0 } ?: 443,
            uuidOrPassword = uuid,
            sni = q["sni"].orEmpty(),
            security = "tls",
            alpn = q["alpn"] ?: "h3",
            extra = JSONObject().apply {
                put("password", pass)
                put("congestion_control", q["congestion_control"] ?: "bbr")
            }.toString(),
        )
    }

    // ----- helpers -----
    private fun Uri.queryMap(): Map<String, String> =
        queryParameterNames.associateWith { getQueryParameter(it).orEmpty() }

    private fun tag(uri: Uri): String? = uri.fragment?.let { urlDecode(it) }?.ifBlank { null }

    private fun urlDecode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun b64(s: String): ByteArray =
        Base64.decode(s.replace('-', '+').replace('_', '/').padBase64(), Base64.DEFAULT)

    private fun String.padBase64(): String {
        val m = length % 4
        return if (m == 0) this else this + "=".repeat(4 - m)
    }

    private fun maybeBase64Decode(s: String): String {
        if ("://" in s) return s                    // уже plain-список
        return runCatching { String(b64(s)) }.getOrDefault(s)
    }

    private data class Ss(val method: String, val password: String, val host: String, val port: Int)
    private operator fun Ss.component1() = method
    private operator fun Ss.component2() = password
    private operator fun Ss.component3() = host
    private operator fun Ss.component4() = port

    private fun decodeSs(core: String): Ss {
        return if ("@" in core) {
            val creds = String(b64(core.substringBefore("@")))
            val method = creds.substringBefore(":")
            val password = creds.substringAfter(":")
            val hostPort = core.substringAfter("@")
            Ss(method, password, hostPort.substringBeforeLast(":"),
                hostPort.substringAfterLast(":").toIntOrNull() ?: 8388)
        } else {
            val decoded = String(b64(core))       // method:password@host:port целиком в base64
            val method = decoded.substringBefore(":")
            val rest = decoded.substringAfter(":")
            val password = rest.substringBefore("@")
            val hostPort = rest.substringAfter("@")
            Ss(method, password, hostPort.substringBeforeLast(":"),
                hostPort.substringAfterLast(":").toIntOrNull() ?: 8388)
        }
    }
}
