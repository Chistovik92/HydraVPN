package ru.gidravpn.hydra.data.model

/**
 * DNS через DoH (DNS-over-HTTPS) для sing-box/Xray — публичные резолверы по
 * их well-known IP (sing-box стучится на него по HTTPS на `/dns-query`, без
 * отдельного разрешения имени). [SYSTEM] отключает DoH и возвращает
 * резолвер платформы; [CUSTOM] — свой IP/хост, введённый пользователем.
 */
enum class DnsProvider(val label: String, val address: String?) {
    CLOUDFLARE("Cloudflare", "1.1.1.1"),
    GOOGLE("Google", "8.8.8.8"),
    QUAD9("Quad9", "9.9.9.9"),
    ADGUARD("AdGuard", "94.140.14.14"),
    SYSTEM("Системный резолвер", null),
    CUSTOM("Свой адрес", null);

    companion object {
        fun fromId(id: String?): DnsProvider =
            entries.firstOrNull { it.name == id } ?: CLOUDFLARE
    }
}
