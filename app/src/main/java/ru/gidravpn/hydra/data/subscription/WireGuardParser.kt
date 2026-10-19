package ru.gidravpn.hydra.data.subscription

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import ru.gidravpn.hydra.data.model.Protocol

/**
 * Разбор конфигов WireGuard / AmneziaWG.
 *
 * Поддерживает:
 *  - стандартный `.conf` (секции [Interface]/[Peer]) — в т.ч. с параметрами
 *    обфускации AmneziaWG (Jc, Jmin, Jmax, S1, S2, H1–H4 — версии 1.0/1.5;
 *    I1–I5 — версия 2.0);
 *  - ссылки `wireguard://<base64 конфига>` и `awg://<base64 конфига>`
 *    (де-факто стандарт экосистемы: конфиг кодируется целиком).
 *
 * Ключи и параметры складываются в [ServerProfile.extra] как JSON —
 * см. [WireGuardConfigBuilder], который собирает из них .conf / uapi.
 */
object WireGuardParser {

    /**
     * @return карта полей extra-JSON профиля либо null, если вход не похож на WG/AWG.
     */
    fun parse(text: String, isAmnezia: Boolean): Pair<Map<String, String>, JSONObject>? {
        val conf = maybeUnwrapLink(text) ?: return null
        return parseConf(conf, isAmnezia)
    }

    /** Разворачивает ссылку wireguard://…/awg://… в текст .conf. */
    private fun maybeUnwrapLink(text: String): String? {
        val t = text.trim()
        uriForm(t)?.let { return it }
        return when {
            t.startsWith("vpn://", ignoreCase = true) -> decodeBody(t.substringAfter("://"))?.takeIf { "[Interface]" in it }
            t.startsWith("wireguard://", ignoreCase = true) -> decodeBody(t.substringAfter("://"))
            t.startsWith("wg://", ignoreCase = true) -> decodeBody(t.substringAfter("://"))
            t.startsWith("awg://", ignoreCase = true) -> decodeBody(t.substringAfter("://"))
            t.startsWith("amnezia://", ignoreCase = true) -> decodeBody(t.substringAfter("://"))
            "[Interface]" in t -> t
            else -> null
        }
    }

    private fun decodeBody(body: String): String? = runCatching {
        val cleaned = body.substringBefore("#").replace('-', '+').replace('_', '/')
        val pad = if (cleaned.length % 4 == 0) 0 else 4 - cleaned.length % 4
        String(Base64.decode(cleaned + "=".repeat(pad), Base64.DEFAULT))
    }.getOrElse { runCatching { Uri.decode(body) }.getOrNull() }

    /**
     * URI-форма `wireguard://<закрытый ключ>@хост:порт?publickey=…&address=…&mtu=…&presharedkey=…#имя` (так её отдают 3x-ui,
     * v2rayN, sing-box) — в текст .conf (0.8.2-a2). null — это не URI-форма (тогда base64 целого конфига).
     */
    private fun uriForm(t: String): String? {
        val scheme = t.substringBefore("://").lowercase()
        if (scheme != "wireguard" && scheme != "wg") return null
        val body = t.substringAfter("://").substringBefore("#")
        if ('@' !in body || !body.contains("publickey=", true)) return null
        // «+» в base64-ключах — не пробел (URLDecoder иначе портит ключ)
        fun dec(v: String) = java.net.URLDecoder.decode(v.replace("+", "%2B"), "UTF-8")
        val priv = dec(body.substringBeforeLast("@"))
        val hostPort = body.substringAfterLast("@").substringBefore("?").removeSuffix("/")
        val q = body.substringAfter("?", "").split("&").filter { '=' in it }
            .associate { it.substringBefore("=").lowercase() to dec(it.substringAfter("=")) }
        val pub = q["publickey"] ?: return null
        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $priv")
            q["address"]?.let { appendLine("Address = $it") }
            q["mtu"]?.let { appendLine("MTU = $it") }
            q["dns"]?.let { appendLine("DNS = $it") }
            appendLine("[Peer]")
            appendLine("PublicKey = $pub")
            q["presharedkey"]?.let { appendLine("PresharedKey = $it") }
            appendLine("AllowedIPs = ${q["allowedips"] ?: "0.0.0.0/0, ::/0"}")
            appendLine("Endpoint = $hostPort")
            q["keepalive"]?.let { appendLine("PersistentKeepalive = $it") }
        }
    }

    /** Разбор .conf: первая секция [Interface] + первый [Peer]. */
    private fun parseConf(conf: String, isAmnezia: Boolean): Pair<Map<String, String>, JSONObject>? {
        if ("[Interface]" !in conf && "[Peer]" !in conf) return null

        val extra = JSONObject()
        val iface = mutableMapOf<String, String>()
        val peer = mutableMapOf<String, String>()
        var section = ""

        conf.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.equals("[Interface]", true) -> section = "iface"
                line.equals("[Peer]", true) -> section = "peer"
                line.isEmpty() || line.startsWith("#") -> Unit
                section.isNotEmpty() && "=" in line -> {
                    val k = line.substringBefore("=").trim().lowercase()
                    val v = line.substringAfter("=").trim()
                    (if (section == "iface") iface else peer)[k] = v
                }
            }
        }

        val pubKey = peer["publickey"] ?: return null

        // Endpoint → address:port профиля; при отсутствии — хост-заглушка.
        val endpoint = peer["endpoint"].orEmpty()
        val host = endpoint.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
        val port = endpoint.substringAfterLast(":").toIntOrNull() ?: 51820

        // Открытый ключ пира читают и WireGuardConfigBuilder (uapi), и sing-box; без него подключение невозможно.
        // Раньше он попадал только в meta и в профиль не сохранялся — импорт .conf/awg:// не мог подключиться.
        extra.put("public_key", pubKey)
        iface["privatekey"]?.let { extra.put("private_key", it) }
        iface["address"]?.let { extra.put("local_address", it) }
        iface["dns"]?.let { extra.put("dns", it) }
        iface["mtu"]?.let { extra.put("mtu", it) }
        peer["presharedkey"]?.let { extra.put("preshared_key", it) }
        peer["allowedips"]?.let { extra.put("allowed_ips", it) }
        peer["persistentkeepalive"]?.let { extra.put("keepalive", it) }

        // --- Обфускация AmneziaWG ---
        // Все поколения 1.0 – 3.x (AwgParams).
        val found = AwgParams.ALL.mapNotNull { p -> iface[p.conf]?.let { p.conf to it } }.toMap()
        found.forEach { (k, v) -> extra.put(k, v) }
        val hasAwg = found.isNotEmpty()
        val version = AwgParams.version(found)
        extra.put("awg_version", version)

        val meta = mapOf(
            "host" to host.ifEmpty { "" },
            "port" to port.toString(),
            "public_key" to pubKey
        )
        if (!isAmnezia && hasAwg) {
            // конфиг AmneziaWG, но пользователь импортировал как plain WG — не теряем параметры
        }
        return meta to extra
    }

    /** Сборка ServerProfile из разобранного конфига. */
    fun toProfile(text: String, fallbackName: String, isAmnezia: Boolean): ru.gidravpn.hydra.data.model.ServerProfile? {
        val (meta, extra) = parse(text, isAmnezia) ?: return null
        // 0.8.0-rc4: параметры обфускации в конфиге — это AmneziaWG, как бы его ни назвали. Иначе профиль уходил в sing-box как
        // обычный WireGuard, рукопожатие с сервером AmneziaWG не проходило («подключено», но трафик не идёт).
        val proto = if (isAmnezia || hasAwgParams(extra)) Protocol.AMNEZIAWG else Protocol.WIREGUARD
        return ru.gidravpn.hydra.data.model.ServerProfile(
            name = fallbackName,
            protocolId = proto.id,
            address = meta["host"].orEmpty(),
            port = meta["port"]?.toIntOrNull() ?: 51820,
            uuidOrPassword = extra.optString("private_key"),   // приватный ключ интерфейса
            extra = extra.toString(),
        )
    }

    /** В extra профиля есть параметры AmneziaWG (Jc, Jmin, Jmax, S1–S4, H1–H4, I1–I5, 3.x). */
    fun hasAwgParams(extra: JSONObject): Boolean = AwgParams.ALL.any { extra.has(it.conf) }
}
