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
            // use{}: при таймауте (а это самый частый исход для мёртвого сервера)
            // close() после connect() не выполнялся — дескриптор висел до GC.
            // «Пинг всех» на списке в полсотни серверов копил их пачками.
            Socket().use { socket ->
                SocketGuard.protect(socket)
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(address, port), TIMEOUT_MS)
                ((System.nanoTime() - start) / 1_000_000).toInt()
            }
        }.getOrDefault(-1)
    }
}
