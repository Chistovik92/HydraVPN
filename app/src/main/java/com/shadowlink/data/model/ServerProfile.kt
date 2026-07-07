package com.shadowlink.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Единый профиль сервера. Специфичные для протокола поля хранятся в [extra]
 * как JSON, чтобы не плодить таблицы под каждый протокол.
 */
@Entity(tableName = "servers")
data class ServerProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val protocolId: String,       // Protocol.id
    val address: String,
    val port: Int,
    val uuidOrPassword: String = "",   // uuid для vless/vmess, password для trojan/ss/tuic, PSK для l2tp
    val flow: String = "",             // xtls-rprx-vision и т.п.
    val sni: String = "",
    val transport: String = "tcp",     // tcp | ws | grpc | http | quic
    val transportPath: String = "",    // path для ws/http, serviceName для grpc
    val security: String = "none",     // none | tls | reality
    val alpn: String = "",
    val fingerprint: String = "chrome",
    val extra: String = "{}",          // JSON: reality pbk/sid, hysteria obfs, tuic congestion и т.д.
    val subscriptionId: Long? = null,  // если импортирован из подписки
    val pingMs: Int = -1,              // -1 = не измерялось
    val flag: String = "🌐"
) {
    val protocol: Protocol? get() = Protocol.fromId(protocolId)
    val summary: String
        get() = buildString {
            append(protocol?.displayName ?: protocolId.uppercase())
            if (pingMs >= 0) append(" • Пинг: ${pingMs}мс")
        }
}
