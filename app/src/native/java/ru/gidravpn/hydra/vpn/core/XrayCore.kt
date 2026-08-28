package ru.gidravpn.hydra.vpn.core

import android.os.ParcelFileDescriptor
import ru.gidravpn.hydra.data.model.ServerProfile
// import libXray.LibXray   // из libXray.aar

/**
 * Альтернативный движок на Xray-core (libXray.aar).
 *
 * Особенность: в отличие от sing-box, Xray-core сам НЕ управляет tun-интерфейсом.
 * Нужна связка «tun → tun2socks → socks-inbound Xray». Практическая схема
 * (как в v2rayNG):
 *   1. Поднять Xray с локальным socks5-inbound (например, 127.0.0.1:10808) и
 *      нужным outbound (VLESS/VMess/Trojan) — конфиг в формате Xray JSON.
 *   2. Запустить tun2socks (hev-socks5-tunnel), который перекладывает пакеты
 *      из нашего tun-fd в этот socks5.
 *
 * Здесь дан каркас; конкретные вызовы зависят от версии libXray и выбранного
 * tun2socks. См. docs/PROTOCOLS.md, раздел «Xray».
 */
class XrayCore : VpnCore {
    override val name = "Xray-core"

    override fun start(
        tun: ParcelFileDescriptor,
        profile: ServerProfile,
        onLog: (String) -> Unit,
        onStats: (TrafficStats) -> Unit,
    ) {
        onLog("Xray: генерация конфига…")
        val xrayConfig = XrayConfigBuilder.build(profile)      // Xray JSON с socks-inbound
        // val res = LibXray.runXray(baseDir, xrayConfig)      // запуск ядра
        // Tun2Socks.start(tun.fd, socksHost="127.0.0.1", socksPort=10808)  // мост
        onLog("Xray: требуется собранный libXray.aar + tun2socks (см. docs/PROTOCOLS.md)")
        throw NotImplementedError("Подключите libXray.aar и tun2socks-мост")
    }

    override fun stop() {
        // Tun2Socks.stop(); LibXray.stopXray()
    }
}
