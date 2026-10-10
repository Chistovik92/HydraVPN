package ru.gidravpn.hydra.vpn.pptp

import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Канал GRE до сервера PPTP. Реализации: сырой сокет через root-помощника (Android), на ПК — своя. Приём — в [onGre]
 * (GRE без IP-заголовка, уже отфильтрованный по адресу сервера).
 */
interface GreTransport {
    fun send(gre: ByteArray)
    fun close()
}

/** Нельзя открыть GRE (нет root / raw-сокетов): сообщение показывается пользователю как есть. */
class GreUnavailable(message: String) : java.io.IOException(message)

/**
 * Клиент PPTP: управляющее соединение TCP 1723 + GRE. Поверх него работает обычный PPP-стек ([onFrame] /
 * [sendFrame]). Не знает ни про Android, ни про tun.
 *
 * @param openControl открыть TCP-сокет к серверу (на Android — с protect(), чтобы не попасть в собственный tun).
 * @param openGre поднять GRE-канал; полученные кадры нужно передавать в переданный обратный вызов.
 */
class PptpClient(
    private val host: String,
    private val port: Int = Pptp.PORT,
    private val openControl: () -> Socket = { Socket() },
    private val openGre: (onGre: (ByteArray) -> Unit) -> GreTransport,
    private val onFrame: (ByteArray) -> Unit,
    private val onDown: (String) -> Unit,
    private val onLog: (String) -> Unit = {},
    private val timeoutMs: Int = 15_000,
) {
    private val closed = AtomicBoolean(false)
    private val up = AtomicBoolean(false)
    private var socket: Socket? = null
    private var gre: GreTransport? = null
    private var seq: GreSequencer? = null
    private val ourCallId = SecureRandom().nextInt(0xFFFE) + 1
    private var peerCallId = 0
    private val writeLock = Any()

    /** Блокирует до готового вызова (OCRP принят), бросает исключение с понятной причиной. */
    fun connect() {
        val s = openControl()
        socket = s
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        val input = s.getInputStream()

        onLog("PPTP: управляющее соединение с $host:$port…")
        writeControl(Pptp.sccrq())
        val sccrp = expect(input, Pptp.SCCRP)
        if (sccrp.result != 1) throw java.io.IOException("PPTP: сервер отклонил соединение (SCCRP result=${sccrp.result}, error=${sccrp.error})")
        onLog("PPTP: SCCRP получен")

        writeControl(Pptp.ocrq(ourCallId))
        val ocrp = expect(input, Pptp.OCRP)
        if (ocrp.result != 1) throw java.io.IOException("PPTP: вызов отклонён (OCRP result=${ocrp.result}, error=${ocrp.error})")
        peerCallId = ocrp.peerCallId
        onLog("PPTP: вызов установлен (call id: наш $ourCallId, сервера $peerCallId)")

        val sequencer = GreSequencer(peerCallId)
        seq = sequencer
        // GRE открываем только когда вызов установлен: иначе сервер ответит на кадры «неизвестный вызов».
        gre = openGre { raw ->
            val f = GreFrame.decode(raw) ?: return@openGre
            sequencer.receive(f, ourCallId)?.let(onFrame)
        }
        s.soTimeout = 0
        up.set(true)
        thread(isDaemon = true, name = "pptp-control") { controlLoop(input) }
        thread(isDaemon = true, name = "pptp-timer") { timerLoop() }
    }

    /** Кадр PPP (с заголовком адреса/управления) — серверу в GRE. */
    fun sendFrame(ppp: ByteArray) {
        val sequencer = seq ?: return
        if (!up.get()) return
        runCatching { gre?.send(sequencer.data(ppp)) }.onFailure { down("PPTP: ошибка отправки GRE: ${it.message}") }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        up.set(false)
        runCatching { writeControl(Pptp.callClear(ourCallId)) }
        runCatching { writeControl(Pptp.stopCcrq()) }
        runCatching { gre?.close() }
        runCatching { socket?.close() }
    }

    private fun down(reason: String) {
        if (closed.get()) return
        up.set(false)
        onDown(reason)
    }

    private fun writeControl(msg: ByteArray) = synchronized(writeLock) {
        val out = socket!!.getOutputStream(); out.write(msg); out.flush()
    }

    /** Ждёт ответ нужного типа; Echo-Request по дороге отвечает сам. */
    private fun expect(input: java.io.InputStream, type: Int): Pptp.Control {
        while (true) {
            val raw = Pptp.read(input) ?: throw java.io.IOException("PPTP: сервер закрыл соединение")
            val m = Pptp.parse(raw) ?: continue
            if (m.type == Pptp.ECHO_REQ) { writeControl(Pptp.echoReply(m.echoId)); continue }
            if (m.type == type) return m
            if (m.type == Pptp.STOP_CCRQ || m.type == Pptp.CALL_DISCONNECT) throw java.io.IOException("PPTP: сервер разорвал соединение при установке")
        }
    }

    private fun controlLoop(input: java.io.InputStream) {
        try {
            while (!closed.get()) {
                val raw = Pptp.read(input) ?: break
                val m = Pptp.parse(raw) ?: continue
                when (m.type) {
                    Pptp.ECHO_REQ -> writeControl(Pptp.echoReply(m.echoId))
                    Pptp.STOP_CCRQ -> { down("PPTP: сервер закрыл управляющее соединение"); return }
                    Pptp.CALL_DISCONNECT -> { down("PPTP: сервер завершил вызов"); return }
                }
            }
            down("PPTP: управляющее соединение закрыто")
        } catch (e: Exception) {
            down("PPTP: управляющее соединение: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Подтверждения GRE и keep-alive: без них сервер замолчит (окно) или сочтёт канал мёртвым. */
    private fun timerLoop() {
        var echoAt = System.currentTimeMillis() + ECHO_MS
        var echoId = 1
        while (!closed.get()) {
            try { Thread.sleep(100) } catch (_: InterruptedException) { return }
            seq?.ackOnly()?.let { runCatching { gre?.send(it) } }
            if (System.currentTimeMillis() >= echoAt) {
                echoAt = System.currentTimeMillis() + ECHO_MS
                runCatching { writeControl(Pptp.echoRequest(echoId++)) }.onFailure { down("PPTP: нет связи с сервером"); return }
            }
        }
    }

    private companion object { const val ECHO_MS = 30_000L }
}
