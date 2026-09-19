package ru.gidravpn.hydra.vpn.core

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Выбор «физической» сети под VPN: с интернетом и не сам VPN. Нужен, когда
 * нельзя ждать колбэк (первые доли секунды после старта sing-box, №9) и для
 * `VpnService.setUnderlyingNetworks()` (7c).
 */
object PhysicalNetwork {
    fun pick(cm: ConnectivityManager): Network? {
        val candidates = cm.allNetworks.filter { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@filter false
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        // Предпочитаем сеть, которую система проверила (есть реальный интернет),
        // затем ту, что была активной до нас.
        return candidates.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } ?: candidates.firstOrNull()
    }
}
