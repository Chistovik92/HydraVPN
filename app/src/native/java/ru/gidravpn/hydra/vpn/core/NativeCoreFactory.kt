package ru.gidravpn.hydra.vpn.core

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import ru.gidravpn.hydra.AppCtx
import ru.gidravpn.hydra.BuildConfig
import ru.gidravpn.hydra.data.model.Engine
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.repository.EngineRepository

/**
 * Фабрика реальных ядер (flavor `native`).
 *  - USERSPACE (SSTP/L2TP) — чистый Kotlin, без .aar;
 *  - SINGBOX — libbox.aar: proxy-протоколы + WireGuard;
 *  - AWG — amneziawg-go.aar;
 *  - XRAY — libXray.aar + sing-box как tun2socks-мост (опционально, см. ниже);
 *  - WDTT — libclient.so (beta), OLCRTC — olcrtc.aar + tun2socks (beta);
 *  - UNAVAILABLE (PPTP) — PptpCore с честным отказом.
 * tun-дескриптор передаётся в VpnCore.start(), поэтому фабрике он не нужен.
 */
class NativeCoreFactory : CoreFactory {
    override fun create(profile: ServerProfile): VpnCore {
        val proto = profile.protocol
        return when (proto) {
            Protocol.SSTP -> SstpCore()
            Protocol.L2TP -> L2tpCore()
            Protocol.PPTP -> PptpCore()
            Protocol.WDTT -> WdttCore()
            Protocol.OLCRTC -> OlcRtcCore()
            else -> when {
                proto?.engine == Engine.AWG -> AmneziaWgCore()
                proto in XRAY_CAPABLE && BuildConfig.XRAY_AVAILABLE && preferXray() -> XrayCore()
                else -> SingBoxCore()
            }
        }
    }

    /**
     * Пользовательский тумблер «Xray Core для VLESS/VMess/Trojan/SS»
     * (Настройки → Туннель). Гейт на [BuildConfig.XRAY_AVAILABLE] выше —
     * защита: если тумблер был включён, а `native` пересобрали без
     * `libXray.aar`, тихо откатываемся на sing-box вместо падения в
     * `NotImplementedError` заглушки XrayCore.
     */
    private fun preferXray(): Boolean = AppCtx.appContext?.let { ctx ->
        runBlocking { EngineRepository(ctx).preferXray.firstOrNull() }
    } == true

    private companion object {
        /** Ровно то, что умеет [XrayConfigBuilder.outboundFor] — не Hysteria2/TUIC/WireGuard. */
        val XRAY_CAPABLE = setOf(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS)
    }
}

/** Провайдер фабрики для native-сборки. */
object CoreFactoryProvider {
    val factory: CoreFactory = NativeCoreFactory()
}
