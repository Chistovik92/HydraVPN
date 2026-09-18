package ru.gidravpn.hydra.data.model

import java.net.URI

/**
 * DNS-сервер для sing-box/Xray, разобранный из пресета или того, что ввёл
 * пользователь: голый IP/хост (→ DoH), либо URL `https://host[:port]/path`
 * (в т.ч. приватный DoH с токеном в пути), `tls://host[:port]`, `udp://host[:port]`.
 */
data class DnsEndpoint(
    val type: String,
    val host: String,
    val port: Int? = null,
    val path: String? = null,
) {
    /** IP не нужно резолвить; для хоста sing-box 1.12 требует domain_resolver. */
    val isIp: Boolean get() = IPV4.matches(host) || ':' in host

    /** Адрес для внутреннего DNS Xray-core; null — Xray не умеет этот транспорт (DoT). */
    fun toXrayAddress(): String? {
        val h = if (':' in host) "[$host]" else host
        val p = port?.let { ":$it" }.orEmpty()
        return when (type) {
            TYPE_HTTPS -> "https://$h$p${path ?: DEFAULT_DOH_PATH}"
            TYPE_UDP -> if (port == null) host else "udp://$h$p"
            else -> null
        }
    }

    companion object {
        const val TYPE_HTTPS = "https"
        const val TYPE_TLS = "tls"
        const val TYPE_UDP = "udp"
        const val DEFAULT_DOH_PATH = "/dns-query"
        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val HOST = Regex("^[A-Za-z0-9.-]+$")
        private val IPV6 = Regex("^\\[?[0-9A-Fa-f:.]+]?$")

        fun doh(ip: String) = DnsEndpoint(TYPE_HTTPS, ip)

        /** null — строку нельзя превратить в DNS-сервер (показать ошибку в UI). */
        fun parse(raw: String): DnsEndpoint? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            if ("://" !in s) {
                if (s.count { it == ':' } >= 2 && IPV6.matches(s)) {
                    return DnsEndpoint(TYPE_HTTPS, s.removeSurrounding("[", "]"))
                }
                val host = s.substringBefore(':')
                val port = s.substringAfter(':', "").takeIf { it.isNotEmpty() }?.toIntOrNull()
                if (!HOST.matches(host) || (':' in s && port == null)) return null
                return DnsEndpoint(TYPE_HTTPS, host, port)
            }
            val uri = runCatching { URI(s) }.getOrNull() ?: return null
            val type = uri.scheme?.lowercase()?.takeIf { it in setOf(TYPE_HTTPS, TYPE_TLS, TYPE_UDP) }
                ?: return null
            val host = uri.host?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() } ?: return null
            val port = uri.port.takeIf { it > 0 }
            val path = if (type == TYPE_HTTPS) {
                (uri.rawPath.orEmpty() + uri.rawQuery?.let { "?$it" }.orEmpty())
                    .takeIf { it.isNotEmpty() && it != "/" }
            } else null
            return DnsEndpoint(type, host, port, path)
        }
    }
}
