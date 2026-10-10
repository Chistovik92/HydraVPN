package ru.gidravpn.hydra.vpn.pptp

import android.system.Os
import android.system.OsConstants
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Помощник PPTP, запускаемый от root: `su -c app_process /system/bin ru.gidravpn.hydra.vpn.pptp.GreRelay <порт> <токен> <сервер> <интерфейс|-> <netId>`.
 *
 * Приложение без root не может открыть сырой сокет, а GRE (IP-протокол 47) идёт только через него. Помощник открывает
 * `socket(AF_INET, SOCK_RAW, 47)` и перекладывает GRE-кадры между этим сокетом и приложением по локальному TCP:
 * каждое сообщение — 2 байта длины + GRE (без IP-заголовка). Первыми шлются 16 байт токена: так приложение
 * убеждается, что на его порту слушает именно помощник, а не чужой процесс.
 *
 * Сокет помечается сетью (SO_MARK) или привязывается к интерфейсу, иначе GRE от root-процесса ушёл бы в собственный tun VPN.
 * Помощник завершается, когда приложение закрывает соединение.
 */
object GreRelay {
    private const val SO_MARK = 36
    private const val SO_BINDTODEVICE = 25

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 3) { System.err.println("GreRelay: порт токен сервер [интерфейс]"); exitProcess(2) }
        val port = args[0].toInt()
        val token = args[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val server = InetAddress.getByName(args[2])
        val iface = args.getOrNull(3).orEmpty().takeIf { it != "-" }.orEmpty()
        val netId = args.getOrNull(4)?.toIntOrNull() ?: 0

        val app = Socket("127.0.0.1", port)
        app.tcpNoDelay = true
        val out = DataOutputStream(app.getOutputStream().buffered(65536))
        val input = DataInputStream(app.getInputStream().buffered(65536))

        val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_RAW, Pptp.GRE_PROTOCOL)
        // Выводим сокет из-под VPN-маршрутизации. Основной способ - метка fwmark сети (netId | explicit | protectedFromVPN),
        // как у Android netd; запасной - привязка к интерфейсу (скрытый API, может быть недоступен).
        if (netId > 0) {
            try { Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, SO_MARK, netId or 0x30000) }
            catch (e: Exception) { System.err.println("GreRelay: SO_MARK: ${e.message}") }
        } else if (iface.isNotEmpty()) {
            try {
                Os::class.java.getMethod("setsockoptIfreq", java.io.FileDescriptor::class.java, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, String::class.java).invoke(null, fd, OsConstants.SOL_SOCKET, SO_BINDTODEVICE, iface)
            } catch (e: Exception) { System.err.println("GreRelay: SO_BINDTODEVICE($iface): ${e.message}") }
        }
        out.write(token); out.flush()

        // Сеть → приложение.
        thread(isDaemon = true, name = "gre-rx") {
            val buf = ByteArray(65535)
            val host = server.hostAddress
            try {
                while (true) {
                    val n = Os.recvfrom(fd, buf, 0, buf.size, 0, null)
                    if (n <= 0) continue
                    val (src, gre) = GreFrame.fromIpPacket(buf.copyOf(n)) ?: continue
                    if (src != host) continue
                    synchronized(out) { out.writeShort(gre.size); out.write(gre); out.flush() }
                }
            } catch (_: Exception) { exitProcess(0) }
        }

        // Приложение → сеть.
        try {
            while (true) {
                val len = input.readUnsignedShort()
                val gre = ByteArray(len)
                input.readFully(gre)
                Os.sendto(fd, gre, 0, gre.size, 0, server, 0)
            }
        } catch (_: Exception) {
            // Приложение закрыло соединение (или оборвалось) - помощник больше не нужен.
        } finally {
            runCatching { Os.close(fd) }
            exitProcess(0)
        }
    }
}
