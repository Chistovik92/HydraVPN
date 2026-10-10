package ru.gidravpn.hydra.vpn.pptp

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * GRE для PPTP на Android через root: запускает [GreRelay] командой `su` и обменивается с ним кадрами по
 * локальному TCP. Без root (`su` нет или отказал) бросает [GreUnavailable] с понятной причиной.
 *
 * @param apkPath путь к APK приложения (`applicationInfo.sourceDir`) - оттуда помощник берёт свой код.
 * @param iface физический интерфейс (wlan0, rmnet0 ...), к которому привязывается сырой сокет, или пусто.
 */
class RootGreTransport(
    private val apkPath: String,
    private val serverIp: String,
    private val iface: String,
    private val netId: Int,
    private val onGre: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit,
    private val onDead: (String) -> Unit,
) : GreTransport {
    private var proc: Process? = null
    private var sock: java.net.Socket? = null
    private var out: DataOutputStream? = null
    @Volatile private var closed = false

    fun open() {
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val tokenHex = token.joinToString("") { "%02x".format(it) }
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = START_TIMEOUT_MS
            val cmd = "CLASSPATH='$apkPath' exec app_process /system/bin ${GreRelay::class.java.name} ${server.localPort} $tokenHex $serverIp ${iface.ifEmpty { "-" }} $netId"
            val p = try {
                ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            } catch (e: Exception) {
                throw GreUnavailable("PPTP на Android требует root: команда su недоступна (${e.message}). GRE нельзя передать без raw-сокета.")
            }
            proc = p
            // Вывод помощника и su - в журнал (причина отказа root, ошибки SELinux).
            thread(isDaemon = true, name = "pptp-su-log") {
                runCatching { p.inputStream.bufferedReader().forEachLine { if (it.isNotBlank()) onLog("PPTP root: ${it.take(200)}") } }
            }
            val s = try {
                server.accept()
            } catch (e: java.net.SocketTimeoutException) {
                val dead = !p.isAlive
                p.destroy()
                throw GreUnavailable(
                    if (dead) "PPTP на Android требует root: su завершился с кодом ${runCatching { p.exitValue() }.getOrNull()} (доступ root не выдан Hydra?)"
                    else "PPTP: помощник root не подключился за ${START_TIMEOUT_MS / 1000} с (SELinux или su без разрешения?)"
                )
            }
            val input = DataInputStream(s.getInputStream().buffered(65536))
            val got = ByteArray(16)
            s.soTimeout = START_TIMEOUT_MS
            try { input.readFully(got) } catch (e: Exception) { s.close(); p.destroy(); throw GreUnavailable("PPTP: помощник root не прислал токен") }
            if (!java.security.MessageDigest.isEqual(got, token)) { s.close(); p.destroy(); throw GreUnavailable("PPTP: к локальному порту подключился посторонний процесс") }
            s.soTimeout = 0
            s.tcpNoDelay = true
            sock = s
            out = DataOutputStream(s.getOutputStream().buffered(65536))
            thread(isDaemon = true, name = "pptp-gre-rx") {
                try {
                    while (true) {
                        val len = input.readUnsignedShort()
                        val gre = ByteArray(len)
                        input.readFully(gre)
                        onGre(gre)
                    }
                } catch (e: Exception) {
                    if (!closed) onDead("PPTP: помощник root остановился (${e.message ?: e.javaClass.simpleName})")
                }
            }
        }
        onLog("PPTP: GRE через root-помощника, интерфейс ${iface.ifEmpty { "по умолчанию" }}")
    }

    override fun send(gre: ByteArray) {
        val o = out ?: throw GreUnavailable("PPTP: GRE-канал закрыт")
        synchronized(o) { o.writeShort(gre.size); o.write(gre); o.flush() }
    }

    override fun close() {
        closed = true
        runCatching { sock?.close() }
        runCatching { proc?.destroy() }
        runCatching { proc?.waitFor(2, TimeUnit.SECONDS) }
    }

    private companion object { const val START_TIMEOUT_MS = 10_000 }
}
