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
        return when {
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

        iface["privatekey"]?.let { extra.put("private_key", it) }
        iface["address"]?.let { extra.put("local_address", it) }
        iface["dns"]?.let { extra.put("dns", it) }
        iface["mtu"]?.let { extra.put("mtu", it) }
        peer["presharedkey"]?.let { extra.put("preshared_key", it) }
        peer["allowedips"]?.let { extra.put("allowed_ips", it) }
        peer["persistentkeepalive"]?.let { extra.put("keepalive", it) }

        // --- Обфускация AmneziaWG ---
        var hasAwg = false
        val awgParams = listOf(
            "jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4",
            "i1", "i2", "i3", "i4", "i5"
        )
        awgParams.forEach { k ->
            iface[k]?.let { extra.put(k, it); hasAwg = true }
        }
        // Версия: 2.0 определяется по маркерам I1–I5, иначе 1.x
        val version = when {
            !hasAwg -> "plain"
            iface.keys.any { it in listOf("i1", "i2", "i3", "i4", "i5") } -> "2.0"
            else -> "1.0"
        }
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
        val proto = if (isAmnezia) Protocol.AMNEZIAWG else Protocol.WIREGUARD
        return ru.gidravpn.hydra.data.model.ServerProfile(
            name = fallbackName,
            protocolId = proto.id,
            address = meta["host"].orEmpty(),
            port = meta["port"]?.toIntOrNull() ?: 51820,
            uuidOrPassword = extra.optString("private_key"),   // приватный ключ интерфейса
            extra = extra.toString(),
        )
    }
}
