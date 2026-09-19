package ru.gidravpn.hydra.data.subscription

import org.json.JSONObject
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile

/**
 * Собирает конфигурацию для amneziawg-go (движок AWG):
 *  - [.conf]-формат (INI), идентичный amnezia-wg quick, с параметрами обфускации;
 *  - uapi-формат (userspace WireGuard IPC API, wireguard-go/amneziawg-go).
 *
 * Ключи в профиле хранятся в base64 (как в .conf); в uapi нужны hex-значения.
 */
object WireGuardConfigBuilder {


    /** Полный .conf (для amneziawg-go tun-режима). */
    fun buildConf(p: ServerProfile): String = buildString {
        val extra = extra(p)
        appendLine("[Interface]")
        appendLine("PrivateKey = ${p.uuidOrPassword}")
        extra.optString("local_address").takeIf { it.isNotBlank() }?.let { appendLine("Address = $it") }
        extra.optString("dns").takeIf { it.isNotBlank() }?.let { appendLine("DNS = $it") }
        extra.optString("mtu").takeIf { it.isNotBlank() }?.let { appendLine("MTU = $it") }

        // Обфускация AmneziaWG (только для awg-профиля)
        if (p.protocol == Protocol.AMNEZIAWG) {
            AwgParams.ALL.forEach { prm ->
                extra.optString(prm.conf).takeIf { it.isNotBlank() }?.let { appendLine("${prm.confName} = $it") }
            }
        }

        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${extra.optString("public_key")}")
        extra.optString("preshared_key").takeIf { it.isNotBlank() }?.let { appendLine("PresharedKey = $it") }
        val allowed = extra.optString("allowed_ips").ifBlank { "0.0.0.0/0, ::/0" }
        appendLine("AllowedIPs = $allowed")
        appendLine("Endpoint = ${p.address}:${p.port}")
        extra.optString("keepalive").takeIf { it.isNotBlank() }?.let { appendLine("PersistentKeepalive = $it") }
    }

    /**
     * uapi-представление (формат wireguard-go UAPI: key=value, lowercase-hex ключи).
     * Используется amneziawg-go при интеграции через IpcUapi/GoBackend.
     */
    fun buildUapi(p: ServerProfile): String {
        val extra = extra(p)
        return buildString {
            appendLine("private_key=${b64ToHex(p.uuidOrPassword)}")
            appendLine("listen_port=0")
            // Параметры AmneziaWG — уровня интерфейса и обязаны идти ДО первого public_key,
            // иначе amneziawg-go отвергает их как «unknown key». Раньше они уходили после пира с
            // префиксом awg_ — такого ключа в uapi нет.
            if (p.protocol == Protocol.AMNEZIAWG) {
                AwgParams.ALL.forEach { prm ->
                    val v = extra.optString(prm.conf).takeIf { it.isNotBlank() } ?: return@forEach
                    val out = when (prm.kind) {
                        AwgParams.Kind.NUMBER -> v
                        AwgParams.Kind.KEY_B64 -> b64ToHex(v)
                        AwgParams.Kind.BOOL -> AwgParams.uapiBool(v)
                    }
                    appendLine("${prm.uapi}=$out")
                }
            }
            appendLine("replace_peers=true")
            appendLine("public_key=${b64ToHex(extra.optString("public_key"))}")
            extra.optString("preshared_key").takeIf { it.isNotBlank() }?.let {
                appendLine("preshared_key=${b64ToHex(it)}")
            }
            extra.optString("allowed_ips").ifBlank { "0.0.0.0/0, ::/0" }.split(",").map { it.trim() }
                .filter { it.isNotBlank() }.forEach { cidr ->
                    val (ip, mask) = parseCidr(cidr)
                    appendLine("allowed_ip=$ip/$mask")
                }
            appendLine("endpoint=${p.address}:${p.port}")
            extra.optString("keepalive").takeIf { it.isNotBlank() }?.let { appendLine("persistent_keepalive_interval=$it") }
            appendLine("replace_allowed_ips=true")


        }
    }

    /** Локальный IP интерфейса (из AllowedIPs/Address либо дефолт туннеля). */
    fun localAddress(p: ServerProfile): String =
        extra(p).optString("local_address").ifBlank { "172.19.0.2/32" }

    /** Адреса интерфейса из `Address` (IPv4/IPv6, через запятую) → пары (ip, префикс). */
    fun localAddresses(p: ServerProfile): List<Pair<String, Int>> = cidrList(localAddress(p))

    /** AllowedIPs пира — маршруты туннеля; по умолчанию всё. */
    fun allowedIps(p: ServerProfile): List<Pair<String, Int>> =
        cidrList(extra(p).optString("allowed_ips").ifBlank { "0.0.0.0/0, ::/0" })

    /** MTU из конфига, иначе консервативные 1380 (обфускация добавляет заголовки). */
    fun mtu(p: ServerProfile): Int = extra(p).optString("mtu").toIntOrNull()?.takeIf { it in 576..9000 } ?: 1380

    private fun cidrList(s: String): List<Pair<String, Int>> = s.split(",").map { it.trim() }
        .filter { it.isNotBlank() }.map {
            val ip = it.substringBefore("/")
            ip to (it.substringAfter("/", "").toIntOrNull() ?: if (":" in ip) 128 else 32)
        }

    fun dnsServers(p: ServerProfile): List<String> =
        extra(p).optString("dns").split(",").map { it.trim() }.filter { it.isNotBlank() }

    private fun extra(p: ServerProfile): JSONObject =
        runCatching { JSONObject(p.extra) }.getOrDefault(JSONObject())

    private fun parseCidr(cidr: String): Pair<String, Int> {
        val ip = cidr.substringBefore("/")
        val mask = cidr.substringAfter("/", "32").toIntOrNull() ?: 32
        // IPv6-длина маски возвращается как есть; wireguard-go ждёт число
        return ip to mask
    }

    /** base64 (44 симв., .conf) → lowercase hex (64 симв., uapi). */
    internal fun b64ToHex(b64: String): String {
        if (b64.isBlank()) return ""
        val bytes = java.util.Base64.getMimeDecoder().decode(b64.trim())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
