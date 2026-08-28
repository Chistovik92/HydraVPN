package ru.gidravpn.hydra.vpn

import android.net.VpnService
import java.net.DatagramSocket
import java.net.Socket

/**
 * Держатель ссылки на активный VpnService для вызова [VpnService.protect].
 *
 * Без protect() сокет транспортного уровня (TLS для SSTP, UDP для L2TP)
 * заворачивается в собственный tun-интерфейс — петля маршрутизации.
 * HydraVpnService регистрирует себя в [attach] при старте.
 */
object SocketGuard {

    @Volatile private var service: VpnService? = null

    fun attach(service: VpnService) { this.service = service }

    fun detach() { service = null }

    /** Вынести TCP-сокет из-под VPN-маршрутизации. */
    fun protect(socket: Socket): Boolean =
        service?.protect(socket) ?: false

    /** Вынести UDP-сокет из-под VPN-маршрутизации. */
    fun protect(socket: DatagramSocket): Boolean =
        service?.protect(socket) ?: false
}
