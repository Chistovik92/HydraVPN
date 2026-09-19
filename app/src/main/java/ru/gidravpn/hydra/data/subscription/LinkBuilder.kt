package ru.gidravpn.hydra.data.subscription

import org.json.JSONObject
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import java.net.URLEncoder
import java.util.Base64

/**
 * Профиль → ссылка для обмена (0.6.18+): обратное к [LinkParser]. Получившуюся
 * строку принимает любой клиент экосистемы (v2rayNG, Hiddify, Streisand…) и сама
 * Hydra через «умный импорт».
 *
 * Поля, которых у профиля нет (или они равны значению по умолчанию), в ссылку не
 * попадают — она остаётся короткой, а короткая ссылка помещается в QR.
 * Возвращает null для протоколов без общепринятого формата ссылки (PPTP недоступен,
 * WDTT — формата нет; olcRTC — compact URI из docs/uri.md).
 */
object LinkBuilder {

    fun toLink(p: ServerProfile): String? {
        val protocol = p.protocol ?: return null
        val extra = runCatching { JSONObject(p.extra) }.getOrDefault(JSONObject())
        return when (protocol) {
            Protocol.VLESS -> vless(p, extra)
            Protocol.TROJAN -> trojan(p)
            Protocol.VMESS -> vmess(p, extra)
            Protocol.SHADOWSOCKS -> shadowsocks(p, extra)
            Protocol.HYSTERIA2 -> hysteria2(p, extra)
            Protocol.TUIC -> tuic(p, extra)
            Protocol.SSTP, Protocol.L2TP -> userPass(p, extra, protocol.id)
            Protocol.WIREGUARD -> wireguard(p, "wireguard")
            Protocol.AMNEZIAWG -> wireguard(p, "awg")
            Protocol.OLCRTC -> OlcRtcLink.build(p)
            else -> null
        }
    }

    private fun vless(p: ServerProfile, extra: JSONObject): String {
        val q = linkedMapOf<String, String>()
        q["encryption"] = "none"
        q["type"] = p.transport
        q["security"] = p.security
        if (p.flow.isNotEmpty()) q["flow"] = p.flow
        if (p.sni.isNotEmpty()) q["sni"] = p.sni
        if (p.alpn.isNotEmpty()) q["alpn"] = p.alpn
        if (p.fingerprint.isNotEmpty() && p.security != "none") q["fp"] = p.fingerprint
        transportPath(p)?.let { (k, v) -> q[k] = v }
        extra.optString("reality_pbk").takeIf { it.isNotEmpty() }?.let { q["pbk"] = it }
        extra.optString("reality_sid").takeIf { it.isNotEmpty() }?.let { q["sid"] = it }
        return "vless://${p.uuidOrPassword}@${host(p.address)}:${p.port}?${query(q)}#${enc(p.name)}"
    }

    private fun trojan(p: ServerProfile): String {
        val q = linkedMapOf<String, String>()
        q["security"] = p.security
        if (p.sni.isNotEmpty()) q["sni"] = p.sni
        q["type"] = p.transport
        if (p.alpn.isNotEmpty()) q["alpn"] = p.alpn
        transportPath(p)?.let { (k, v) -> q[k] = v }
        return "trojan://${enc(p.uuidOrPassword)}@${host(p.address)}:${p.port}?${query(q)}#${enc(p.name)}"
    }

    private fun vmess(p: ServerProfile, extra: JSONObject): String {
        val json = JSONObject().apply {
            put("v", "2")
            put("ps", p.name)
            put("add", p.address)
            put("port", p.port.toString())
            put("id", p.uuidOrPassword)
            put("aid", extra.optInt("aid", 0).toString())
            put("scy", "auto")
            put("net", p.transport)
            put("type", "none")
            put("host", p.sni)
            put("path", p.transportPath)
            put("tls", if (p.security == "tls") "tls" else "")
            put("sni", p.sni)
            put("alpn", p.alpn)
        }
        return "vmess://" + Base64.getEncoder().encodeToString(json.toString().toByteArray())
    }

    private fun shadowsocks(p: ServerProfile, extra: JSONObject): String {
        val method = extra.optString("method", "aes-256-gcm")
        val userInfo = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$method:${p.uuidOrPassword}".toByteArray())
        return "ss://$userInfo@${host(p.address)}:${p.port}#${enc(p.name)}"
    }

    private fun hysteria2(p: ServerProfile, extra: JSONObject): String {
        val q = linkedMapOf<String, String>()
        if (p.sni.isNotEmpty()) q["sni"] = p.sni
        extra.optString("obfs").takeIf { it.isNotEmpty() }?.let { q["obfs"] = it }
        extra.optString("obfs_password").takeIf { it.isNotEmpty() }?.let { q["obfs-password"] = it }
        val qs = if (q.isEmpty()) "" else "?${query(q)}"
        return "hysteria2://${enc(p.uuidOrPassword)}@${host(p.address)}:${p.port}$qs#${enc(p.name)}"
    }

    private fun tuic(p: ServerProfile, extra: JSONObject): String {
        val q = linkedMapOf<String, String>()
        if (p.sni.isNotEmpty()) q["sni"] = p.sni
        if (p.alpn.isNotEmpty()) q["alpn"] = p.alpn
        extra.optString("congestion_control").takeIf { it.isNotEmpty() }?.let { q["congestion_control"] = it }
        val qs = if (q.isEmpty()) "" else "?${query(q)}"
        val pass = extra.optString("password")
        return "tuic://${p.uuidOrPassword}:${enc(pass)}@${host(p.address)}:${p.port}$qs#${enc(p.name)}"
    }

    private fun userPass(p: ServerProfile, extra: JSONObject, scheme: String): String {
        val q = linkedMapOf<String, String>()
        if (p.sni.isNotEmpty()) q["sni"] = p.sni
        if (extra.has("allow_insecure")) q["allow_insecure"] = if (extra.optBoolean("allow_insecure")) "1" else "0"
        extra.optString("tunnel_secret").takeIf { it.isNotEmpty() }?.let { q["tunnel_secret"] = it }
        val qs = if (q.isEmpty()) "" else "?${query(q)}"
        val user = enc(extra.optString("username"))
        return "$scheme://$user:${enc(p.uuidOrPassword)}@${host(p.address)}:${p.port}$qs#${enc(p.name)}"
    }

    /** wireguard://<base64 .conf> — как принимает [WireGuardParser]. */
    private fun wireguard(p: ServerProfile, scheme: String): String {
        val conf = WireGuardConfigBuilder.buildConf(p)
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(conf.toByteArray())
        return "$scheme://$body#${enc(p.name)}"
    }

    // ----- helpers -----

    /** ws/http → path, grpc → serviceName; для tcp пути нет. */
    private fun transportPath(p: ServerProfile): Pair<String, String>? {
        if (p.transportPath.isEmpty()) return null
        return (if (p.transport == "grpc") "serviceName" else "path") to p.transportPath
    }

    private fun host(h: String) = if (':' in h && !h.startsWith("[")) "[$h]" else h

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun query(q: Map<String, String>) = q.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
}
