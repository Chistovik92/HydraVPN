package ru.gidravpn.hydra.vpn.core

import android.os.ParcelFileDescriptor
import org.json.JSONObject
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.vpn.SocketGuard
import ru.gidravpn.hydra.vpn.ppp.MsChapV2
import ru.gidravpn.hydra.vpn.ppp.PppSession
import ru.gidravpn.hydra.vpn.ppp.TunBridge
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * SSTP-клиент (MS-SSTP): PPP поверх TLS поверх HTTPS-соединения.
 * Полностью userspace, без нативных зависимостей.
 *
 * Порядок (MS-SSTP 3.1.1):
 *  1. TCP → TLS 1.2+ (SNI; проверка сертификата, опция allow_insecure);
 *  2. HTTP SSTP_DUPLEX_POST /sra_{BA195780-…}/ → 200;
 *  3. CALL_CONNECT_REQUEST (ENC_INFO: битмаск хэшей + nonce);
 *  4. CALL_CONNECT_ACK (ENC_INFO: хэш серт-та сервера; CRYPTO_BINDING_REQ);
 *  5. PPP: LCP → MS-CHAPv2/PAP → IPCP;
 *  6. CALL_CONNECTED с CRYPTO_BINDING (Compound MAC = HMAC(CMK, …), RFC 3079);
 *  7. фаза данных: IP-пакеты ⇄ TunBridge.
 *
 * Формат пакетов: [0x10][0x00][len BE16] далее 0x0001 → control
 * (type BE16, numAttrs BE16, attrs: [0][id][len BE16][value]),
 * иначе — данные PPP (2-байтовый Protocol + Info).
 * Требует проверки на устройстве против SoftEther/Mikrotik (см. HANDOFF).
 */
class SstpCore : VpnCore {

    override val name = "SSTP (userspace PPP/TLS)"

    private var socket: SSLSocket? = null
    private var session: PppSession? = null
    private var bridge: TunBridge? = null
    private val outLock = Any()
    @Volatile private var running = false

    // SSTP control messages
    private object Msg {
        const val CALL_CONNECT_REQUEST = 1
        const val CALL_CONNECT_ACK = 2
        const val CALL_CONNECT_NAK = 3
        const val CALL_CONNECTED = 4
        const val CALL_ABORT = 5
        const val CALL_DISCONNECT = 6
        const val CALL_DISCONNECT_ACK = 7
        const val ECHO_REQUEST = 8
        const val ECHO_RESPONSE = 9
    }

    private object Attr {
        const val ENC_INFO = 1
        const val STATUS_INFO = 2
        const val CRYPTO_BINDING = 3
        const val CRYPTO_BINDING_REQ = 5
    }

    private val random = SecureRandom()

    override fun start(
        tun: ParcelFileDescriptor,
        profile: ServerProfile,
        onLog: (String) -> Unit,
        onStats: (TrafficStats) -> Unit,
    ) {
        val extra = runCatching { JSONObject(profile.extra) }.getOrDefault(JSONObject())
        val username = extra.optString("username")
        val password = profile.uuidOrPassword
        val allowInsecure = extra.optBoolean("allow_insecure", false)
        if (username.isBlank() || password.isBlank())
            throw IllegalStateException("SSTP: нужны username/password (sstp://user:pass@host)");

        // --- 1. TCP + TLS ---
        onLog("SSTP: подключение к ${profile.address}:${profile.port}…")
        val raw = Socket()
        SocketGuard.protect(raw) // иначе TLS-трафик уйдёт в собственный tun
        raw.connect(InetSocketAddress(profile.address, profile.port), CONNECT_TIMEOUT_MS)
        raw.tcpNoDelay = true

        val ssl = createTlsSocket(raw, profile, allowInsecure, extra.optString("sni"))
        ssl.startHandshake()
        socket = ssl
        val input = BufferedInputStream(ssl.inputStream)
        val output = BufferedOutputStream(ssl.outputStream)

        // --- 2. HTTP-апгрейд ---
        val host = extra.optString("sni").ifBlank { profile.address }
        val http = "SSTP_DUPLEX_POST /sra_{BA195780-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1\r\n" +
                "Host: $host:${profile.port}\r\n" +
                "SSTPCORRELATIONID: {${java.util.UUID.randomUUID()}}\r\n" +
                "Content-Length: 18446744073709551615\r\n\r\n"
        synchronized(outLock) { output.write(http.toByteArray()); output.flush() }

        val status = readHttpHead(input)
        if (!status.startsWith("HTTP/1.1 200") && !status.startsWith("HTTP/1.0 200"))
            throw IllegalStateException("SSTP: HTTP-апгрейд отклонён: ${status.lineSequence().first()}")
        onLog("SSTP: HTTP 200, TLS-канал установлен")

        running = true

        // --- 3. CALL_CONNECT_REQUEST ---
        val nonce = ByteArray(32).also { random.nextBytes(it) }
        // ENC_INFO: HashProtocolBitmask (SHA1=0x01|SHA256=0x02) + Nonce(32)
        val encInfo = be32(HASH_SHA1 or HASH_SHA256) + nonce
        writeControl(output, Msg.CALL_CONNECT_REQUEST, listOf(attr(Attr.ENC_INFO, encInfo)))
        onLog("SSTP: CALL_CONNECT_REQUEST отправлен")

        // --- 4. чтение до CALL_CONNECT_ACK ---
        val ackLatch = CountDownLatch(1)
        val cryptoBindingReq = arrayOfNulls<BindingReq>(1)
        val certHash = arrayOfNulls<ByteArray>(1)

        thread(name = "sstp-reader") {
            val buf = ByteArray(65536)
            var buffered = ByteArray(0)
            try {
                while (running) {
                    val n = input.read(buf)
                    if (n < 0) break
                    buffered += buf.copyOf(n)
                    while (true) {
                        val pkt = tryExtractPacket(buffered) ?: break
                        buffered = buffered.copyOfRange(pkt.second, buffered.size)
                        handlePacket(pkt.first, output, certHash, cryptoBindingReq, ackLatch, onLog)
                    }
                }
            } catch (t: Throwable) {
                if (running) onLog("SSTP: поток чтения завершён: ${t.message}")
            }
        }

        if (!ackLatch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            throw IllegalStateException("SSTP: нет CALL_CONNECT_ACK от сервера")
        onLog("SSTP: CALL_CONNECT_ACK получен (hash=0x${certHash[0]?.size ?: 0} байт)")

        // --- 5. PPP поверх SSTP ---
        val upLatch = CountDownLatch(1)
        val sstpSession = PppSession(
            userName = username,
            password = password,
            sendFrame = { frame -> runCatching { writeData(output, frame) } },
            onLog = onLog,
            onAuthenticated = { auth, _ ->
                lastAuth = auth
            },
            onIpPacket = { packet -> bridge?.onTunnelPacket(packet) },
            onUp = { ip, dns1, dns2 ->
                onLog("SSTP: PPP поднят, IP=$ip DNS=${dns1 ?: "-"}")
                sendCallConnected(output, cryptoBindingReq[0], onLog)
                upLatch.countDown()
            },
            onDown = { reason -> if (running) onLog("SSTP: PPP закрыт: $reason") },
        )
        session = sstpSession

        // проверка хэша сертификата сервера (защита от подмены при allow_insecure)
        verifyCertHash(ssl, certHash[0], onLog)

        sstpSession.start()
        if (!upLatch.await(PPP_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            throw IllegalStateException("SSTP: PPP не поднялся за ${PPP_TIMEOUT_MS / 1000} с (фаза ${sstpSession.phase})")

        // --- 6. мост tun ⇄ PPP ---
        val tunBridge = TunBridge(
            tun = tun,
            session = sstpSession,
            onStats = onStats,
            onLog = onLog,
        )
        bridge = tunBridge
        tunBridge.start()
        onLog("SSTP: туннель активен ✓")
    }

    @Volatile private var lastAuth: MsChapV2.AuthResult? = null

    override fun stop() {
        running = false
        runCatching { session?.close() }
        bridge?.stop()
        runCatching { socket?.close() }
        socket = null
        session = null
        bridge = null
    }

    // ----- обработка пакетов -----

    private class BindingReq(val protocol: Int, val hashBitmask: Int, val nonce: ByteArray)

    private fun handlePacket(
        pkt: ByteArray,
        output: BufferedOutputStream,
        certHash: Array<ByteArray?>,
        cryptoBindingReq: Array<BindingReq?>,
        ackLatch: CountDownLatch,
        onLog: (String) -> Unit,
    ) {
        // header: 4 байта уже проверены; bytes 4-5 — контроль/протокол
        val marker = ((pkt[4].toInt() and 0xFF) shl 8) or (pkt[5].toInt() and 0xFF)
        if (marker == 0x0001) {
            // control packet
            val msgType = ((pkt[6].toInt() and 0xFF) shl 8) or (pkt[7].toInt() and 0xFF)
            val numAttrs = ((pkt[8].toInt() and 0xFF) shl 8) or (pkt[9].toInt() and 0xFF)
            var off = 10
            repeat(numAttrs) {
                if (off + 4 > pkt.size) return
                val attrId = pkt[off + 1].toInt() and 0xFF
                val attrLen = ((pkt[off + 2].toInt() and 0xFF) shl 8) or (pkt[off + 3].toInt() and 0xFF)
                if (attrLen < 4 || off + attrLen > pkt.size) return
                val value = pkt.copyOfRange(off + 4, off + attrLen)
                when (attrId) {
                    Attr.ENC_INFO -> {
                        // value: HashProtocolBitmask(4) + Nonce(32) [+ CertHash(20/32) в ACK]
                        if (value.size >= 36 && certHash[0] == null && value.size > 36) {
                            certHash[0] = value.copyOfRange(36, value.size)
                        }
                    }
                    Attr.CRYPTO_BINDING_REQ -> if (value.size >= 40) {
                        cryptoBindingReq[0] = BindingReq(
                            protocol = value[0].toInt() and 0xFF,
                            hashBitmask = ((value[4].toInt() and 0xFF) shl 24) or
                                    ((value[5].toInt() and 0xFF) shl 16) or
                                    ((value[6].toInt() and 0xFF) shl 8) or
                                    (value[7].toInt() and 0xFF),
                            nonce = value.copyOfRange(8, 40),
                        )
                    }
                    Attr.STATUS_INFO -> {
                        if (value.size >= 4) {
                            val code = ((value[0].toInt() and 0xFF) shl 24) or
                                    ((value[1].toInt() and 0xFF) shl 16) or
                                    ((value[2].toInt() and 0xFF) shl 8) or
                                    (value[3].toInt() and 0xFF)
                            onLog("SSTP: статус сервера: атрибут 0x$code")
                        }
                    }
                }
                off += attrLen
            }
            when (msgType) {
                Msg.CALL_CONNECT_ACK -> ackLatch.countDown()
                Msg.ECHO_REQUEST -> {
                    runCatching { writeControl(output, Msg.ECHO_RESPONSE, emptyList()) }
                }
                Msg.CALL_ABORT, Msg.CALL_DISCONNECT -> {
                    onLog("SSTP: сервер разорвал соединение (CALL_ABORT/DISCONNECT)")
                    runCatching { writeControl(output, Msg.CALL_DISCONNECT_ACK, emptyList()) }
                    running = false
                }
            }
        } else {
            // data: PPP frame (2-байтовый Protocol + Info)
            val pppFrame = pkt.copyOfRange(4, pkt.size)
            session?.onFrame(pppFrame)
        }
    }

    // ----- CALL_CONNECTED + crypto-binding -----

    private fun sendCallConnected(
        output: BufferedOutputStream,
        req: BindingReq?,
        onLog: (String) -> Unit,
    ) {
        if (req == null) {
            onLog("SSTP: CRYPTO_BINDING_REQ не получен — CALL_CONNECTED без crypto-binding (нестандартный сервер)")
            runCatching { writeControl(output, Msg.CALL_CONNECTED, emptyList()) }
            return
        }
        val cmk = lastAuth?.masterKey
        if (cmk == null) {
            onLog("SSTP: аутентификация не MS-CHAPv2 — crypto-binding невозможен (PAP)")
            runCatching { writeControl(output, Msg.CALL_CONNECTED, emptyList()) }
            return
        }

        // выбор хэша: SHA256 (0x02) приоритетнее SHA1 (0x01)
        val useSha256 = (req.hashBitmask and HASH_SHA256) != 0
        val hashBitmask = if (useSha256) HASH_SHA256 else HASH_SHA1

        // binding value: Protocol(1) + Reserved(3) + HashProtocol(4) + MAC(32) + Nonce(32)
        val value = ByteArray(1 + 3 + 4 + 32 + 32)
        value[0] = req.protocol.toByte()
        // Reserved = 0
        writeBe32(value, 4, hashBitmask)
        req.nonce.copyInto(value, 40)

        // MAC: HMAC(CMK, MessageType(2) + NumAttrs(2) + AttrHeader(4) + Value с нулевым MAC)
        val attrLen = 4 + value.size
        val macInput = ByteArray(2 + 2 + 4 + value.size)
        macInput[0] = ((Msg.CALL_CONNECTED ushr 8) and 0xFF).toByte()
        macInput[1] = (Msg.CALL_CONNECTED and 0xFF).toByte()
        macInput[2] = 0; macInput[3] = 1 // один атрибут
        macInput[4] = 0
        macInput[5] = Attr.CRYPTO_BINDING.toByte()
        macInput[6] = ((attrLen ushr 8) and 0xFF).toByte()
        macInput[7] = (attrLen and 0xFF).toByte()
        value.copyInto(macInput, 8)

        val mac = if (useSha256) MsChapV2.hmacSha256(cmk, macInput)
        else MsChapV2.hmacSha1(cmk, macInput)
        mac.copyInto(value, 8) // поле MAC; остаток — нули (SHA1 → 20 из 32)

        runCatching { writeControl(output, Msg.CALL_CONNECTED, listOf(attr(Attr.CRYPTO_BINDING, value))) }
        onLog("SSTP: CALL_CONNECTED с crypto-binding (HMAC-${if (useSha256) "SHA256" else "SHA1"}) отправлен")
    }

    /** Сравнение хэша сертификата из ENC_INFO с реальным сертификатом TLS. */
    private fun verifyCertHash(ssl: SSLSocket, serverHash: ByteArray?, onLog: (String) -> Unit) {
        if (serverHash == null || serverHash.isEmpty()) return
        val cert = runCatching { ssl.session.peerCertificates.firstOrNull() }.getOrNull() ?: return
        val sha1 = MessageDigest.getInstance("SHA-1").digest(cert.encoded)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val ok = serverHash.contentEquals(sha1) || serverHash.contentEquals(sha256)
        onLog(if (ok) "SSTP: хэш сертификата сервера подтверждён ✓"
        else "SSTP: ⚠ хэш сертификата из ENC_INFO не совпал с сертификатом TLS")
    }

    // ----- кодек SSTP -----

    /** [0x10][0x00][len BE16][payload] — control или data. */
    private fun tryExtractPacket(buffered: ByteArray): Pair<ByteArray, Int>? {
        if (buffered.size < 4) return null
        if (buffered[0] != 0x10.toByte()) return null
        val len = ((buffered[2].toInt() and 0xFF) shl 8) or (buffered[3].toInt() and 0xFF)
        if (len < 4 || len > 1 shl 20) return null
        if (buffered.size < len) return null
        return buffered.copyOf(len) to len
    }

    private fun writeControl(output: BufferedOutputStream, msgType: Int, attrs: List<ByteArray>) {
        val total = 4 + 2 + 2 + attrs.sumOf { it.size }
        val out = ByteArray(total)
        out[0] = 0x10
        out[1] = 0
        out[2] = ((total ushr 8) and 0xFF).toByte()
        out[3] = (total and 0xFF).toByte()
        out[4] = 0x00
        out[5] = 0x01 // control marker
        out[6] = ((msgType ushr 8) and 0xFF).toByte()
        out[7] = (msgType and 0xFF).toByte()
        out[8] = ((attrs.size ushr 8) and 0xFF).toByte()
        out[9] = (attrs.size and 0xFF).toByte()
        var off = 10
        attrs.forEach { a ->
            a.copyInto(out, off)
            off += a.size
        }
        synchronized(outLock) { output.write(out); output.flush() }
    }

    private fun writeData(output: BufferedOutputStream, pppFrame: ByteArray) {
        val total = 4 + pppFrame.size
        val out = ByteArray(total)
        out[0] = 0x10
        out[1] = 0
        out[2] = ((total ushr 8) and 0xFF).toByte()
        out[3] = (total and 0xFF).toByte()
        pppFrame.copyInto(out, 4)
        synchronized(outLock) { output.write(out); output.flush() }
    }

    /** Атрибут: [0][id][len BE16][value]. */
    private fun attr(id: Int, value: ByteArray): ByteArray {
        val out = ByteArray(4 + value.size)
        out[0] = 0
        out[1] = id.toByte()
        out[2] = ((out.size ushr 8) and 0xFF).toByte()
        out[3] = (out.size and 0xFF).toByte()
        value.copyInto(out, 4)
        return out
    }

    // ----- TLS -----

    private fun createTlsSocket(raw: Socket, profile: ServerProfile, insecure: Boolean, sni: String): SSLSocket {
        val factory: SSLSocketFactory = if (insecure) {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }), SecureRandom())
            ctx.socketFactory
        } else HttpsURLConnection.getDefaultSSLSocketFactory()

        val ssl = factory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket
        ssl.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
        if (!insecure) {
            val host = sni.ifBlank { profile.address }
            val params = ssl.sslParameters
            params.serverNames = listOf(javax.net.ssl.SNIHostName(host))
            ssl.sslParameters = params
        }
        return ssl
    }

    private fun readHttpHead(input: BufferedInputStream): String {
        val sb = StringBuilder()
        val buf = ByteArray(1)
        while (sb.length < 8192) {
            val n = input.read(buf)
            if (n < 0) break
            sb.append(buf[0].toInt().toChar())
            if (sb.endsWith("\r\n\r\n")) break
        }
        return sb.toString()
    }

    private fun be32(v: Int) = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    private fun writeBe32(p: ByteArray, off: Int, v: Int) {
        p[off] = ((v ushr 24) and 0xFF).toByte()
        p[off + 1] = ((v ushr 16) and 0xFF).toByte()
        p[off + 2] = ((v ushr 8) and 0xFF).toByte()
        p[off + 3] = (v and 0xFF).toByte()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val PPP_TIMEOUT_MS = 30_000L
        private const val HASH_SHA1 = 0x00000001
        private const val HASH_SHA256 = 0x00000002
    }
}
