package ru.gidravpn.hydra.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.gidravpn.hydra.vpn.SocketGuard
import java.net.InetSocketAddress
import java.net.Socket

/** Замер задержки до сервера через время TCP-connect (ICMP недоступен без root). */
object PingMeasurer {
    private const val TIMEOUT_MS = 3000

    /** Возвращает мс до сервера, либо -1 при таймауте/ошибке. */
    suspend fun measure(address: String, port: Int): Int = withContext(Dispatchers.IO) {
        runCatching {
            val socket = Socket()
            SocketGuard.protect(socket)
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(address, port), TIMEOUT_MS)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            socket.close()
            elapsed.toInt()
        }.getOrDefault(-1)
    }
}
