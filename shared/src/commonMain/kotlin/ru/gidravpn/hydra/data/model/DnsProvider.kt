package ru.gidravpn.hydra.data.model

enum class DnsProvider(val label: String, val address: String?) {
    CLOUDFLARE("Cloudflare", "1.1.1.1"), GOOGLE("Google", "8.8.8.8"),
    QUAD9("Quad9", "9.9.9.9"), ADGUARD("AdGuard", "94.140.14.14"),
    HYDRA("Hydra VPN", null), SYSTEM("System", null), CUSTOM("Custom", null);
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.name == id } ?: CLOUDFLARE }
}
