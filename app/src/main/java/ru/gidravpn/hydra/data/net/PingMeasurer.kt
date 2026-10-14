package ru.gidravpn.hydra.data.net

import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.gidravpn.hydra.data.model.Engine
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.vpn.SocketGuard
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Замер задержки до сервера.
 *
 * TCP-протоколы — время TCP-connect к порту сервера. UDP-протоколы (AmneziaWG, WireGuard, Hysteria2, TUIC, L2TP) на TCP не
 * отвечают, и до 0.8.0-rc4 такие серверы всегда показывались «недоступно», хотя работали. Теперь для них: TCP к тому же
 * порту, где отказ (RST) тоже доказывает, что хост жив, а если пакеты молча отбрасываются — ICMP echo через
 * непривилегированный ping-сокет Android. Ни то ни другое не ответило — «недоступно».
 */
object PingMeasurer {
    private const val TIMEOUT_MS = 3000
    const val UNREACHABLE = -2
    /** Нет сервера, до которого мерить (ByeDPI — локальный прокси; olcRTC/OpenFlux без адреса). */
    const val NOT_APPLICABLE = -1

    private val UDP = setOf(Protocol.AMNEZIAWG, Protocol.WIREGUARD, Protocol.HYSTERIA2, Protocol.TUIC, Protocol.L2TP, Protocol.WDTT)

    suspend fun measure(server: ServerProfile): Int {
        val p = server.protocol
        if (p?.engine == Engine.BYEDPI || server.address.isBlank() || server.port !in 1..65535) return NOT_APPLICABLE
        return if (p in UDP) measureUdpHost(server.address, server.port) else measure(server.address, server.port)
    }

    /** Возвращает мс до сервера, либо [UNREACHABLE] (-2) при таймауте/ошибке; -1 — «ещё не измеряли». */
    suspend fun measure(address: String, port: Int): Int = withContext(Dispatchers.IO) {
        runCatching { tcp(address, port, TIMEOUT_MS, refusedIsAlive = false) }.getOrNull() ?: UNREACHABLE
    }

    /** Хост UDP-сервера: TCP (отказ = жив) → ICMP. */
    suspend fun measureUdpHost(address: String, port: Int): Int = withContext(Dispatchers.IO) {
        val ip = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return@withContext UNREACHABLE
        runCatching { tcp(ip.hostAddress!!, port, 1500, refusedIsAlive = true) }.getOrNull()
            ?: runCatching { icmp(ip, 2000) }.getOrNull()
            ?: UNREACHABLE
    }

    private fun tcp(address: String, port: Int, timeout: Int, refusedIsAlive: Boolean): Int? {
        // use{}: при таймауте close() после connect() не выполнялся — дескриптор висел до GC
        Socket().use { socket ->
            SocketGuard.protect(socket)
            val start = System.nanoTime()
            return try {
                socket.connect(InetSocketAddress(address, port), timeout)
                ms(start)
            } catch (e: ConnectException) {
                if (refusedIsAlive && e.message.orEmpty().contains("refused", true)) ms(start) else null
            }
        }
    }

    /** ICMP echo через SOCK_DGRAM/IPPROTO_ICMP (root не нужен); сокет выводится из-под VPN. */
    private fun icmp(ip: InetAddress, timeout: Int): Int? {
        val v6 = ip is java.net.Inet6Address
        val fd = Os.socket(if (v6) OsConstants.AF_INET6 else OsConstants.AF_INET, OsConstants.SOCK_DGRAM, if (v6) OsConstants.IPPROTO_ICMPV6 else OsConstants.IPPROTO_ICMP)
        try {
            // protect() по числу: копия дескриптора указывает на тот же сокет (метка маршрута ставится на сокет)
            runCatching { android.os.ParcelFileDescriptor.dup(fd).use { SocketGuard.protect(it.fd) } }
            // type 8 (v4) / 128 (v6), code 0, checksum и id заполняет ядро для ping-сокета
            val pkt = byteArrayOf(if (v6) 128.toByte() else 8, 0, 0, 0, 0, 0, 0, 1, 'h'.code.toByte(), 'y'.code.toByte())
            val start = System.nanoTime()
            Os.sendto(fd, pkt, 0, pkt.size, 0, ip, 0)
            val pfd = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
            val deadline = start + timeout * 1_000_000L
            val buf = ByteArray(256)
            while (true) {
                val left = ((deadline - System.nanoTime()) / 1_000_000).toInt()
                if (left <= 0 || Os.poll(arrayOf(pfd), left) <= 0) return null
                val n = Os.recvfrom(fd, buf, 0, buf.size, 0, null)
                // ответ: type 0 (v4) / 129 (v6)
                if (n > 0 && (buf[0].toInt() and 0xff) == (if (v6) 129 else 0)) return ms(start)
            }
        } finally {
            runCatching { Os.close(fd) }
        }
    }

    private fun ms(start: Long) = ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)
}
