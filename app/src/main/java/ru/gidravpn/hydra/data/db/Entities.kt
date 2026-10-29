package ru.gidravpn.hydra.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.Subscription

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val protocolId: String, val address: String, val port: Int,
    val uuidOrPassword: String = "", val flow: String = "", val sni: String = "",
    val transport: String = "tcp", val transportPath: String = "", val security: String = "none",
    val alpn: String = "", val fingerprint: String = "chrome", val extra: String = "{}",
    val subscriptionId: Long? = null, val pingMs: Int = -1, val flag: String = "🌐", val sortIndex: Int = 0,
)

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val url: String,
    val userAgent: String = "", val lastUpdated: Long = 0L, val autoUpdateHours: Int = 12,
    @ColumnInfo(defaultValue = "") val serverTitle: String = "",
    @ColumnInfo(defaultValue = "0") val uploadBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val downloadBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val totalBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val expireAt: Long = 0,
    @ColumnInfo(defaultValue = "") val supportUrl: String = "",
    @ColumnInfo(defaultValue = "1") val autoUpdate: Boolean = true,
    @ColumnInfo(defaultValue = "0") val collapsed: Boolean = false,
    @ColumnInfo(defaultValue = "") val lastError: String = "",
    @ColumnInfo(defaultValue = "") val botPanel: String = "",
)

fun ServerEntity.toModel() = ServerProfile(id, name, protocolId, address, port, uuidOrPassword, flow, sni, transport, transportPath, security, alpn, fingerprint, extra, subscriptionId, pingMs, flag, sortIndex)
fun ServerProfile.toEntity() = ServerEntity(id, name, protocolId, address, port, uuidOrPassword, flow, sni, transport, transportPath, security, alpn, fingerprint, extra, subscriptionId, pingMs, flag, sortIndex)
fun SubscriptionEntity.toModel() = Subscription(id, name, url, userAgent, lastUpdated, autoUpdateHours, serverTitle, uploadBytes, downloadBytes, totalBytes, expireAt, supportUrl, autoUpdate, collapsed, lastError, botPanel)
fun Subscription.toEntity() = SubscriptionEntity(id, name, url, userAgent, lastUpdated, autoUpdateHours, serverTitle, uploadBytes, downloadBytes, totalBytes, expireAt, supportUrl, autoUpdate, collapsed, lastError, botPanel)
