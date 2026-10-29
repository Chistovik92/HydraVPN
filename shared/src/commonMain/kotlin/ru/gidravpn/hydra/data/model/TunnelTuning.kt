package ru.gidravpn.hydra.data.model

enum class MtuPreset(val value: Int) {
    AUTO(9000), MTU_1500(1500), MTU_1400(1400), MTU_1280(1280);
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.name == id } ?: AUTO }
}
enum class TlsFragmentMode {
    OFF, RECORD, TCP;
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.name == id } ?: OFF }
}
enum class Ipv6Mode {
    BLOCK, ENABLE;
    companion object {
        const val TUN_ADDRESS = "fdfe:dcba:9876::1"
        const val TUN_PREFIX = 126
        fun fromId(id: String?) = entries.firstOrNull { it.name == id } ?: BLOCK
    }
}
