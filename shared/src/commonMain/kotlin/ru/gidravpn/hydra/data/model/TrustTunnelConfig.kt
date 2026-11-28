package ru.gidravpn.hydra.data.model

/**
 * TrustTunnel (AdGuard, Apache-2.0) endpoint configuration.
 *
 * Протокол: трафик TCP/UDP/ICMP внутри обычного HTTPS — TLS 1.2+, SNI обязателен,
 * проверка сертификата сервера обязательна (кроме явного [skipVerification]),
 * авторизация — HTTP `proxy-authorization: Basic base64(username:password)`.
 * Транспорт: HTTP/2 (`h2`, TCP+TLS) или HTTP/3 (`h3`, QUIC), см. PROTOCOL.md
 * upstream `TrustTunnel/TrustTunnel`. Сам туннель поднимает нативный
 * `trusttunnel_client` (TrustTunnelClient, Apache-2.0) в режиме локального
 * SOCKS5-слушателя; sing-box/Hydra поверх него маршрутизирует tun
 * (см. `TrustTunnelCore`, схема `SocksBridgeCore`).
 *
 * Источники конфигурации (все приводятся к одному `[endpoint]`):
 *  - ручной ввод (host/port + username/password + SNI + upstream);
 *  - `tt://` deep-link (экспорт `trusttunnel_client --export-deeplink`;
 *    канонический декодер — нативный `trusttunnel_deeplink_decode`
 *    (deeplink-ffi); [TrustTunnelLink.parse] — совместимое подмножество);
 *  - `trusttunnel_client.toml` (TOML `[endpoint]`, см. `TrustTunnelArgs`).
 */
data class TrustTunnelConfig(
    /** Имя хоста для TLS (SNI + проверка имени сертификата). Поддерживается `sni|remote_id`. */
    val hostname: String,
    /** Адреса эндпоинта `host:port` (IPv4/IPv6/домен); первый — основной. */
    val addresses: List<String>,
    /** Авторизация (Basic). */
    val username: String,
    val password: String,
    /** `http2` (h2) или `http3` (h3/QUIC). */
    val upstreamProtocol: UpstreamProtocol = UpstreamProtocol.HTTP2,
    /** uTLS-отпечаток: chrome, chrome_canary, safari, firefox, okhttp, openssl, boringssl. */
    val tlsProfile: String = "chrome",
    /** Не проверять сертификат сервера (самоподписанный). По умолчанию ВЫКЛ — проверка обязательна. */
    val skipVerification: Boolean = false,
    /** Сертификат эндпоинта PEM (если не в системном хранилище). */
    val certificatePem: String? = null,
    /** Может ли эндпоинт маршрутизировать IPv6. */
    val hasIpv6: Boolean = true,
    /** TLS client random prefix (hex, `prefix[/mask]`); mask в deep-link не переносится. */
    val clientRandom: String = "",
    /** HKDF+AES-ключ для `client_random` (hex); приоритетнее [clientRandom]. */
    val clientRandomAuthKey: String = "",
    /** Анти-DPI меры на стороне клиента. */
    val antiDpi: Boolean = false,
    /** DNS-апстримы через VPN (см. синтаксис upstream); пусто — AdGuard DNS на стороне эндпоинта. */
    val dnsUpstreams: List<String> = emptyList(),
    /** Человекочитаемое имя. */
    val name: String? = null,
    /** `custom_sni`, если задан отдельно от [hostname] (`sni|remote_id`). */
    val customSni: String? = null,
) {
    enum class UpstreamProtocol(val wire: String) {
        HTTP2("http2"),
        HTTP3("http3");

        companion object {
            fun parse(s: String?): UpstreamProtocol = when (s?.trim()?.lowercase()) {
                "http3", "h3", "quic" -> HTTP3
                else -> HTTP2   // `auto` не бывает в deep-link; auto→http2 как upstream
            }
        }
    }

    /** `[endpoint] hostname`, разделяя `sni|remote_id`. */
    val effectiveHostname: String get() = hostname.substringAfter('|').ifBlank { hostname }
    /** SNI для TLS: `sni` из `sni|remote_id`, иначе [customSni], иначе [effectiveHostname]. */
    val effectiveSni: String get() = when {
        '|' in hostname -> hostname.substringBefore('|').ifBlank { effectiveHostname }
        !customSni.isNullOrBlank() -> customSni
        else -> effectiveHostname
    }
}
