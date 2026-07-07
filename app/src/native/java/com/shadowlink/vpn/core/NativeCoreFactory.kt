package com.shadowlink.vpn.core

import com.shadowlink.data.model.Engine
import com.shadowlink.data.model.ServerProfile

/**
 * Фабрика реальных ядер (flavor `native`).
 * sing-box — по умолчанию для всех proxy-протоколов; Xray — опционально.
 * tun-дескриптор передаётся в VpnCore.start(), поэтому фабрике он не нужен.
 */
class NativeCoreFactory : CoreFactory {
    override fun create(profile: ServerProfile): VpnCore = when (profile.protocol?.engine) {
        Engine.XRAY -> XrayCore()
        else -> SingBoxCore()
    }
}

/** Провайдер фабрики для native-сборки. */
object CoreFactoryProvider {
    val factory: CoreFactory = NativeCoreFactory()
}
