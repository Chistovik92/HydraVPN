package ru.gidravpn.hydra.vpn.core

import ru.gidravpn.hydra.data.model.Engine
import ru.gidravpn.hydra.data.model.ServerProfile

/**
 * Фабрика реальных ядер (flavor `native`).
 *  - USERSPACE (SSTP/L2TP) — чистый Kotlin, без .aar;
 *  - SINGBOX — libbox.aar: proxy-протоколы + WireGuard;
 *  - AWG — amneziawg-go.aar;
 *  - XRAY — libXray.aar + tun2socks;
 *  - UNAVAILABLE (PPTP) — PptpCore с честным отказом.
 * tun-дескриптор передаётся в VpnCore.start(), поэтому фабрике он не нужен.
 */
class NativeCoreFactory : CoreFactory {
    override fun create(profile: ServerProfile): VpnCore {
        val proto = profile.protocol
        return when (proto?.engine) {
            Engine.USERSPACE -> when (proto) {
                ru.gidravpn.hydra.data.model.Protocol.SSTP -> SstpCore()
                ru.gidravpn.hydra.data.model.Protocol.L2TP -> L2tpCore()
                else -> SstpCore()
            }
            Engine.UNAVAILABLE -> PptpCore()
            Engine.XRAY -> XrayCore()
            Engine.AWG -> AmneziaWgCore()
            else -> SingBoxCore()
        }
    }
}

/** Провайдер фабрики для native-сборки. */
object CoreFactoryProvider {
    val factory: CoreFactory = NativeCoreFactory()
}
