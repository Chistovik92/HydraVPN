package com.shadowlink.vpn.core

import android.os.ParcelFileDescriptor
import com.shadowlink.data.model.ServerProfile

/** Состояние соединения, наблюдаемое из UI. */
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** Живая статистика соединения. */
data class TrafficStats(val downBytes: Long = 0, val upBytes: Long = 0)

/**
 * Абстракция proxy-ядра, работающего поверх tun-интерфейса.
 * Реализации (выбираются по flavor):
 *  - SingBoxCore (native, libbox.aar)  — VLESS/VMess/Trojan/SS/Hysteria2/TUIC
 *  - XrayCore    (native, libXray.aar) — альтернативный движок Xray
 *  - NoopCore    (stub)                — симуляция для разработки/CI
 *
 * Семейство L2TP/IPsec обрабатывается отдельно ([com.shadowlink.vpn.Ikev2Connector]),
 * т.к. IKEv2 на Android — системный (VpnManager), а не поверх нашего tun.
 */
interface VpnCore {
    val name: String

    fun start(
        tun: ParcelFileDescriptor,
        profile: ServerProfile,
        onLog: (String) -> Unit,
        onStats: (TrafficStats) -> Unit,
    )

    fun stop()
}

/**
 * Фабрика ядра. Класс с этим же именем и пакетом определён в обоих flavor-sourceSet'ах
 * (src/stub и src/native); в конкретную сборку попадает ровно один.
 */
interface CoreFactory {
    fun create(profile: ServerProfile): VpnCore
}
