package ru.gidravpn.hydra.data.model

data class ServerProfile(
    val id: Long = 0,
    val name: String, val protocolId: String, val address: String, val port: Int,
    val uuidOrPassword: String = "", val flow: String = "", val sni: String = "",
    val transport: String = "tcp", val transportPath: String = "", val security: String = "none",
    val alpn: String = "", val fingerprint: String = "chrome", val extra: String = "{}",
    val subscriptionId: Long? = null, val pingMs: Int = -1, val flag: String = "🌐", val sortIndex: Int = 0,
) {
    val protocol: Protocol? get() = Protocol.fromId(protocolId)
    val summary: String get() = buildString { append(protocol?.displayName ?: protocolId.uppercase()); if (pingMs >= 0) append(" • ${pingMs} ms") }
}
