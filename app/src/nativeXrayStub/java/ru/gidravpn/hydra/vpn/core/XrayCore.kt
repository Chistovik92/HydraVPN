package ru.gidravpn.hydra.vpn.core

import android.os.ParcelFileDescriptor
import ru.gidravpn.hydra.data.model.ServerProfile

/**
 * Заглушка — компилируется, когда `app/libs/libXray.aar` отсутствует
 * (см. `app/build.gradle.kts`, `sourceSets["native"]`). Рабочая реализация —
 * `app/src/nativeXrayReal/.../XrayCore.kt`, она поднимается вместо этой,
 * как только .aar появится в `app/libs/`.
 *
 * Xray-core сам tun не обслуживает: рабочая версия поднимает Xray headless
 * с локальным socks5-inbound (через `LibXray.invoke("runXray", ...)`) и
 * заводит поверх него sing-box как tun2socks-мост (см.
 * `SingBoxConfigBuilder.buildXrayBridge`) — отдельный нативный tun2socks
 * не нужен. Подробности — docs/PROTOCOLS.md, раздел «Xray», и docs/BUILD.md,
 * раздел 2.2 (там же — как собрать `libXray.aar`).
 */
class XrayCore : VpnCore {
    override val name = "Xray-core (не собран)"

    override fun start(
        tun: ParcelFileDescriptor,
        profile: ServerProfile,
        onLog: (String) -> Unit,
        onStats: (TrafficStats) -> Unit,
    ) {
        val xrayConfig = XrayConfigBuilder.build(profile, socksPort = 10808)
        onLog("Xray: конфиг сгенерирован (${xrayConfig.length} байт), но libXray.aar не собран")
        throw NotImplementedError(
            "Соберите libXray.aar и положите в app/libs/ — docs/BUILD.md, раздел 2.2."
        )
    }

    override fun stop() {}
}
