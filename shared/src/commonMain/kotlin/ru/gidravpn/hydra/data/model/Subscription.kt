package ru.gidravpn.hydra.data.model

data class Subscription(
    val id: Long = 0, val name: String, val url: String,
    val userAgent: String = "", val lastUpdated: Long = 0L, val autoUpdateHours: Int = 12,
    val serverTitle: String = "",
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val totalBytes: Long = 0,
    val expireAt: Long = 0,
    val supportUrl: String = "",
    val autoUpdate: Boolean = true,
    val collapsed: Boolean = false,
    val lastError: String = "",
    val botPanel: String = "",
) {
    val displayName: String get() = serverTitle.ifBlank { name }
    val usedBytes: Long get() = uploadBytes + downloadBytes
}
