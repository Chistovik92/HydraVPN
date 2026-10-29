package ru.gidravpn.hydra.vpn.core

import android.os.ParcelFileDescriptor
import ru.gidravpn.hydra.data.model.ServerProfile

interface VpnCore {
    val name: String
    fun setDeathListener(listener: (reason: String) -> Unit) {}
    fun onNetworkChanged() {}
    fun start(
        tun: ParcelFileDescriptor,
        profile: ServerProfile,
        onLog: (String) -> Unit,
        onStats: (TrafficStats) -> Unit,
    )
    fun stop()
}

interface CoreFactory {
    fun create(profile: ServerProfile): VpnCore
}
