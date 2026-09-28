package ru.gidravpn.hydra.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import ru.gidravpn.hydra.data.model.ServerProfile

/**
 * Room entity для ServerProfile. Соответствует доменной модели в commonMain,
 * но с аннотациями Room. Маппинг между доменной моделью и сущностью
 * делается в репозитории.
 */
@Entity(tableName = "servers")
data class ServerProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val protocolId: String,
    val address: String,
    val port: Int,
    val uuidOrPassword: String = "",
    val flow: String = "",
    val sni: String = "",
    val transport: String = "tcp",
    val transportPath: String = "",
    val security: String = "none",
    val alpn: String = "",
    val fingerprint: String = "chrome",
    val extra: String = "{}",
    val subscriptionId: Long? = null,
    val pingMs: Int = -1,
    val flag: String = "🌐"
) {
    fun toDomain(): ServerProfile = ServerProfile(
        id = id,
        name = name,
        protocolId = protocolId,
        address = address,
        port = port,
        uuidOrPassword = uuidOrPassword,
        flow = flow,
        sni = sni,
        transport = transport,
        transportPath = transportPath,
        security = security,
        alpn = alpn,
        fingerprint = fingerprint,
        extra = extra,
        subscriptionId = subscriptionId,
        pingMs = pingMs,
        flag = flag,
    )

    companion object {
        fun fromDomain(profile: ServerProfile): ServerProfileEntity = ServerProfileEntity(
            id = profile.id,
            name = profile.name,
            protocolId = profile.protocolId,
            address = profile.address,
            port = profile.port,
            uuidOrPassword = profile.uuidOrPassword,
            flow = profile.flow,
            sni = profile.sni,
            transport = profile.transport,
            transportPath = profile.transportPath,
            security = profile.security,
            alpn = profile.alpn,
            fingerprint = profile.fingerprint,
            extra = profile.extra,
            subscriptionId = profile.subscriptionId,
            pingMs = profile.pingMs,
            flag = profile.flag,
        )
    }
}