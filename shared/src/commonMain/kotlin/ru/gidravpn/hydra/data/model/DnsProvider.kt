package ru.gidravpn.hydra.data.model

/**
 * DNS через DoH (DNS-over-HTTPS) для sing-box/Xray — публичные резолверы по
 * их well-known IP (sing-box стучится на него по HTTPS на `/dns-query`, без
 * отдельного разрешения имени). [SYSTEM] отключает DoH и возвращает
 * резолвер платформы; [CUSTOM] — свой IP/хост, введённый пользователем.
 */
enum class DnsProvider(val label: String, val address: String?, val labelRes: Int? = null) {
    CLOUDFLARE("Cloudflare", "1.1.1.1"),
    GOOGLE("Google", "8.8.8.8"),
    QUAD9("Quad9", "9.9.9.9"),
    ADGUARD("AdGuard", "94.140.14.14"),
    /** Приватный DNS проекта Hydra VPN (0.7.0). */
    HYDRA("Hydra VPN", "dns.hydravpn.us"),
    SYSTEM("System", null, 0),
    CUSTOM("Custom", null, 0);

    companion object {
        fun fromId(id: String?): DnsProvider =
            entries.firstOrNull { it.name == id } ?: CLOUDFLARE
    }
}