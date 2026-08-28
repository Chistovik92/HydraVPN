package ru.gidravpn.hydra.vpn.core

import android.os.ParcelFileDescriptor
import ru.gidravpn.hydra.data.model.ServerProfile

/** Состояние соединения, наблюдаемое из UI. */
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** Живая статистика соединения. */
data class TrafficStats(val downBytes: Long = 0, val upBytes: Long = 0)

/**
 * Абстракция ядра, работающего поверх tun-интерфейса.
 * Реализации (выбираются по flavor):
 *  - SingBoxCore (native, libbox.aar)  — VLESS/VMess/Trojan/SS/Hysteria2/TUIC/WireGuard
 *  - XrayCore    (native, libXray.aar) — альтернативный движок Xray
 *  - AmneziaWgCore (native, amneziawg-go.aar)
 *  - SstpCore / L2tpCore (native, userspace PPP на Kotlin — без .aar)
 *  - PptpCore    (native) — честный отказ (GRE → root)
 *  - NoopCore    (stub)   — симуляция для разработки/CI
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
