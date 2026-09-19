package ru.gidravpn.hydra.vpn.core

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import ru.gidravpn.hydra.AppCtx
import ru.gidravpn.hydra.BuildConfig
import ru.gidravpn.hydra.data.model.Engine
import ru.gidravpn.hydra.data.model.EngineToggles
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
            Protocol.OPENFLUX -> OpenFluxCore()
            else -> when {
                proto?.engine == Engine.AWG -> AmneziaWgCore()
                // Тумблеры ядер (0.6.22): Xray берёт VLESS/VMess/Trojan/SS, если он предпочтён или
                // sing-box выключен. Что ядро вообще разрешено, HydraVpnService проверяет до фабрики.
                toggles().engineFor(proto, BuildConfig.XRAY_AVAILABLE) == EngineToggles.Kind.XRAY -> XrayCore()
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
    private fun toggles(): EngineToggles = AppCtx.appContext?.let { ctx ->
        runBlocking { EngineRepository(ctx).toggles.firstOrNull() }
    } ?: EngineToggles()
}

/** Провайдер фабрики для native-сборки. */
object CoreFactoryProvider {
    val factory: CoreFactory = NativeCoreFactory()
}
