package ru.gidravpn.hydra.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import ru.gidravpn.hydra.data.model.ServerProfile

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val protocolId: String, val address: String, val port: Int,
    val uuidOrPassword: String = "", val flow: String = "", val sni: String = "",
    val transport: String = "tcp", val transportPath: String = "", val security: String = "none",
    val alpn: String = "", val fingerprint: String = "chrome", val extra: String = "{}",
    val subscriptionId: Long? = null, val pingMs: Int = -1, val flag: String = "🌐", val sortIndex: Int = 0,
)

// SubscriptionEntity и его маппинги toDomain()/fromDomain() — в :shared (androidMain),
// сюда не дублируем: класс в APK должен быть один.

fun ServerEntity.toModel() = ServerProfile(id, name, protocolId, address, port, uuidOrPassword, flow, sni, transport, transportPath, security, alpn, fingerprint, extra, subscriptionId, pingMs, flag, sortIndex)
fun ServerProfile.toEntity() = ServerEntity(id, name, protocolId, address, port, uuidOrPassword, flow, sni, transport, transportPath, security, alpn, fingerprint, extra, subscriptionId, pingMs, flag, sortIndex)
