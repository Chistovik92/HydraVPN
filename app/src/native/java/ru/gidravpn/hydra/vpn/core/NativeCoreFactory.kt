package ru.gidravpn.hydra.vpn.core

import ru.gidravpn.hydra.data.model.Engine
import ru.gidravpn.hydra.data.model.ServerProfile

/**
 * Фабрика реальных ядер (flavor `native`).
 * sing-box — по умолчанию для всех proxy-протоколов и WireGuard;
 * AmneziaWG — amneziawg-go; Xray — опционально.
 * tun-дескриптор передаётся в VpnCore.start(), поэтому фабрике он не нужен.
 */
class NativeCoreFactory : CoreFactory {
    override fun create(profile: ServerProfile): VpnCore = when (profile.protocol?.engine) {
        Engine.XRAY -> XrayCore()
        Engine.AWG -> AmneziaWgCore()
        else -> SingBoxCore()
    }
}

/** Провайдер фабрики для native-сборки. */
object CoreFactoryProvider {
    val factory: CoreFactory = NativeCoreFactory()
}
