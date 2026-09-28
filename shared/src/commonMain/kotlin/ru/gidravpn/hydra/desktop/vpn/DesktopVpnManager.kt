package ru.gidravpn.hydra.desktop.vpn

import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.SplitTunnel
import ru.gidravpn.hydra.data.model.DnsEndpoint

/**
 * Desktop VPN Manager interface - implemented in :desktop module.
 * Using interface instead of expect/actual to avoid KMP compiler issues.
 */
interface DesktopVpnManager {
    fun start(
        profile: ServerProfile,
        splitTunnel: SplitTunnel,
        dns: DnsEndpoint?,
        onTrafficUpdate: (up: Long, down: Long) -> Unit,
    )

    fun stop()

    fun isRunning(): Boolean
}

/** Factory for creating platform-specific VPN manager */
interface DesktopVpnManagerFactory {
    fun create(): DesktopVpnManager
}