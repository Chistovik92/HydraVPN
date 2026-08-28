package ru.gidravpn.hydra.vpn.core

import android.os.ParcelFileDescriptor
import ru.gidravpn.hydra.data.model.ServerProfile
// import libXray.LibXray   // из libXray.aar
// import tun2socks.Tun2Socks  // из hev-socks5-tunnel (.aar/.so)

/**
 * Альтернативный движок на Xray-core (libXray.aar).
 *
 * Особенность: в отличие от sing-box, Xray-core сам НЕ управляет tun-интерфейсом.
 * Нужна связка «tun → tun2socks → socks-inbound Xray». Практическая схема
 * (как в v2rayNG):
 *   1. Поднять Xray с локальным socks5-inbound (127.0.0.1:socksPort) и
 *      нужным outbound (VLESS/VMess/Trojan/SS) — конфиг в XrayConfigBuilder.
 *   2. Запустить tun2socks (hev-socks5-tunnel), который перекладывает пакеты
 *      из нашего tun-fd в этот socks5:
 *        Tun2Socks.start(
 *            fd = tun.detachFd(),
 *            socksHost = "127.0.0.1", socksPort = socksPort,
 *            tunMtu = 9000, fakeDns = "1.1.1.1"  // + UDP-ретрансляция
 *        )
 *   3. При остановке: Tun2Socks.stop() → LibXray.stopXray().
 *
 * Полный streamSettings (tls/reality, ws/grpc/http/httpupgrade) —
 * [XrayConfigBuilder]. Здесь дан каркас; конкретные вызовы зависят от
 * версии libXray и выбранного tun2socks. См. docs/PROTOCOLS.md, раздел «Xray».
 */
class XrayCore : VpnCore {
    override val name = "Xray-core"

    @Volatile private var running = false

    /** Локальный SOCKS5-порт Xray (должен совпадать с inbounds[0].port). */
    private val socksPort = 10808

    override fun start(
        tun: ParcelFileDescriptor,
        profile: ServerProfile,
        onLog: (String) -> Unit,
        onStats: (TrafficStats) -> Unit,
    ) {
        onLog("Xray: генерация конфига…")
        val xrayConfig = XrayConfigBuilder.build(profile, socksPort)
        onLog("Xray: конфиг сгенерирован (${xrayConfig.length} байт), socks 127.0.0.1:$socksPort")

        // TODO(libXray.aar + hev-socks5-tunnel): интеграция после сборки:
        //   1. val instance = LibXray.runXray(baseDir, xrayConfig)   // запуск ядра
        //      (сигнатура зависит от версии libXray; см. XTLS/libXray и v2rayNG)
        //   2. Tun2Socks.start(tun.detachFd(), "127.0.0.1", socksPort, mtu = 9000)
        //   3. статистика: опрос LibXray.queryStats / Xray API (policy.stats)
        running = true
        throw NotImplementedError("Подключите libXray.aar и tun2socks-мост (docs/BUILD.md, docs/PROTOCOLS.md)")
    }

    override fun stop() {
        running = false
        // TODO: Tun2Socks.stop(); LibXray.stopXray()
    }
}
