package ru.gidravpn.hydra.vpn.core

import android.os.ParcelFileDescriptor
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder

/**
 * olcRTC (beta) — TCP поверх WebRTC (DataChannel).
 *
 * Движок: gomobile-биндинг `olcrtc.aar`. Схема (как в v2rayNG для tun2socks):
 *  1. olcrtc-ядро (компонент `cnc`) устанавливает WebRTC-сессию с сигнальным
 *     сервером и выставляет локальный SOCKS5-прокси (например, 127.0.0.1:10809);
 *  2. tun2socks (hev-socks5-tunnel) перекладывает пакеты из нашего tun-fd
 *     в этот SOCKS5 — TCP-потоки едут внутри WebRTC DataChannel.
 *
 * До сборки `olcrtc.aar` + hev-socks5-tunnel ядро честно отказывает.
 * См. docs/BUILD.md и docs/SERVICES.md (раздел olcRTC).
 */
class OlcRtcCore : VpnCore {

    override val name = "olcRTC (WebRTC, beta)"

    @Volatile private var running = false

    override fun start(
        tun: ParcelFileDescriptor,
        profile: ServerProfile,
        onLog: (String) -> Unit,
        onStats: (TrafficStats) -> Unit,
    ) {
        onLog("olcRTC: движок beta — требуются olcrtc.aar и tun2socks (docs/BUILD.md)")

        // TODO(olcrtc.aar): интеграция после сборки:
        //   1. import olcrtc.OlcRtc — gomobile-биндинг компонента cnc;
        //   2. val rc = OlcRtc.newClient(signalUrl, localSocksPort = 10809); rc.start();
        //   3. Tun2Socks.start(tun.fd, "127.0.0.1", 10809) — hev-socks5-tunnel
        //      (SOCKS5-мост, аналогично XrayCore);
        //   4. статистика из rc.queryStats().
        running = true
        throw NotImplementedError(
            "olcRTC (beta): соберите olcrtc.aar и tun2socks, подключите мост в OlcRtcCore (docs/BUILD.md)"
        )
    }

    override fun stop() {
        running = false
        // TODO(olcrtc.aar): rc.stop(); Tun2Socks.stop()
    }
}
