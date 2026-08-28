package ru.gidravpn.hydra.vpn.ppp

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Машина состояний PPP (RFC 1661/1332/1878 + MS-CHAPv2 RFC 2759):
 * LCP → аутентификация (MS-CHAPv2 / PAP) → IPCP → фаза данных (IP).
 *
 * Не зависит от транспорта: внешний код подаёт кадры в [onFrame]
 * и получает исходящие кадры через [sendFrame]. Используется и в
 * SstpCore (TLS), и в L2tpCore (UDP).
 */
class PppSession(
    private val userName: String,
    private val password: String,
    private val sendFrame: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit,
    /** Вызывается при успешной аутентификации (CMK нужен SSTP для crypto-binding). */
    private val onAuthenticated: ((auth: MsChapV2.AuthResult?, peerAuthenticatorResponse: String?) -> Unit)? = null,
    /** Фаза данных: входящий IP-пакет. */
    private val onIpPacket: (ByteArray) -> Unit = {},
    /** Сессия полностью поднята (IPCP закрыт). */
    private val onUp: (assignedIp: String, dns1: String?, dns2: String?) -> Unit = { _, _, _ -> },
    private val onDown: (reason: String) -> Unit = {},
) {
    enum class Phase { DEAD, LCP, AUTH, IPCP, OPEN }

    @Volatile var phase = Phase.DEAD
        private set

    /** IP, назначенный сервером через IPCP. */
    @Volatile var assignedIp: String? = null
        private set
    @Volatile var dns1: String? = null
        private set
    @Volatile var dns2: String? = null
        private set
    /** CMK последней MS-CHAPv2-аутентификации (для SSTP crypto-binding). */
    @Volatile var lastAuth: MsChapV2.AuthResult? = null
        private set
    /** Ответ аутентикатора из CHAP Success ("S=...") — проверяется нами. */
    @Volatile var peerAuthenticatorResponse: String? = null
        private set

    val isUp get() = phase == Phase.OPEN

    private val random = SecureRandom()
    private val closed = AtomicBoolean(false)
    private var nextId = 0
    private var ourMagic = 0L
    private var lcpAcked = false        // наш ConfReq подтверждён
    private var peerAcked = false       // ConfReq пира подтверждён нами
    private var ourMru = Ppp.DEFAULT_MRU
    private var negotiatedAuth = 0      // 0 = не требуется
    private var authStarted = false
    private var ipcpAcked = false
    private var peerIpcpAcked = false
    private var requestedIp = 0         // 0.0.0.0 → сервер выдаст в Nak

    private fun id(): Int = (nextId++) and 0xFF

    /** Начать согласование (вызывать после установки транспорта). */
    fun start() {
        phase = Phase.LCP
        ourMagic = random.nextLong()
        sendLcpConfigRequest()
    }

    /** Входящий кадр PPP от транспорта. */
    fun onFrame(frame: ByteArray) {
        val (proto, info) = Ppp.parseFrame(frame) ?: return
        when (proto) {
            Ppp.PROTO_LCP -> onLcp(info)
            Ppp.PROTO_CHAP -> onChap(info)
            Ppp.PROTO_PAP -> onPap(info)
            Ppp.PROTO_IPCP -> onIpcp(info)
            Ppp.PROTO_IP -> if (phase == Phase.OPEN) onIpPacket(info)
            Ppp.PROTO_CCP -> onCcp(info)
            else -> {
                // Protocol-Reject для неизвестных
                if (phase != Phase.OPEN) onLog("PPP: неизвестный протокол 0x%04X".format(proto))
            }
        }
    }

    // ----- LCP -----

    private fun sendLcpConfigRequest() {
        val opts = mutableListOf(
            Ppp.optInt(Ppp.LCP_OPT_MRU, ourMru),
            Ppp.optInt(Ppp.LCP_OPT_MAGIC, (ourMagic and 0xFFFFFFFFL).toInt()),
        )
        sendFrame(Ppp.controlFrame(Ppp.PROTO_LCP, Ppp.CODE_CONF_REQ, id(), Ppp.encodeOptions(opts)))
    }

    private fun onLcp(info: ByteArray) {
        val pkt = runCatching { Ppp.parseControl(info) }.getOrNull() ?: return
        when (pkt.code) {
            Ppp.CODE_CONF_REQ -> {
                val (replyCode, replyOpts) = evaluatePeerLcp(pkt.data)
                sendFrame(Ppp.controlFrame(Ppp.PROTO_LCP, replyCode, pkt.id, replyOpts))
                if (replyCode == Ppp.CODE_CONF_ACK) {
                    peerAcked = true
                    maybeAdvanceFromLcp()
                } else {
                    onLog("PPP LCP: ConfReq пира Nak/Reject — повторное согласование")
                }
            }
            Ppp.CODE_CONF_ACK -> {
                lcpAcked = true
                maybeAdvanceFromLcp()
            }
            Ppp.CODE_CONF_NAK -> {
                // корректируем параметры (MRU, auth-протокол) и повторяем
                val opts = Ppp.parseOptions(pkt.data)
                opts.forEach { o ->
                    when (o.type) {
                        Ppp.LCP_OPT_MRU -> ourMru = o.intValue().coerceIn(576, 2000)
                        Ppp.LCP_OPT_AUTH -> negotiatedAuth = authFromOption(o)
                    }
                }
                sendLcpConfigRequest()
            }
            Ppp.CODE_CONF_REJ -> {
                // убираем отклонённые опции и повторяем
                val rejected = Ppp.parseOptions(pkt.data).map { it.type }.toSet()
                if (Ppp.LCP_OPT_MAGIC in rejected) ourMagic = 0L
                sendLcpConfigRequest()
            }
            Ppp.CODE_TERM_REQ -> {
                sendFrame(Ppp.controlFrame(Ppp.PROTO_LCP, Ppp.CODE_TERM_ACK, pkt.id, ByteArray(0)))
                terminate("LCP Terminate-Request от сервера")
            }
            Ppp.CODE_TERM_ACK -> terminate("LCP Terminate-Ack")
            Ppp.CODE_ECHO_REQ -> {
                val data = Ppp.int32(0) + Ppp.int32((ourMagic and 0xFFFFFFFFL).toInt())
                sendFrame(Ppp.controlFrame(Ppp.PROTO_LCP, Ppp.CODE_ECHO_REP, pkt.id, data))
            }
            Ppp.CODE_ECHO_REP -> Unit
            Ppp.CODE_CODE_REJ -> terminate("LCP Code-Reject")
            else -> Unit
        }
    }

    /** Что ответить на ConfReq пира: Ack (все опции приняты) или Nak/Reject. */
    private fun evaluatePeerLcp(data: ByteArray): Pair<Int, ByteArray> {
        val opts = Ppp.parseOptions(data)
        val naks = mutableListOf<Ppp.Option>()
        for (o in opts) {
            when (o.type) {
                Ppp.LCP_OPT_AUTH -> {
                    val auth = authFromOption(o)
                    if (auth != AUTH_NONE) {
                        when (auth) {
                            Ppp.AUTH_CHAP_MS2 -> {
                                if (o.value.size < 5 || (o.value[4].toInt() and 0xFF) != Ppp.CHAP_ALG_MSCHAPV2) {
                                    // CHAP, но не MS-CHAPv2 — Nak с MS-CHAPv2
                                    naks += Ppp.optBytes(Ppp.LCP_OPT_AUTH,
                                        byteArrayOf(0xC2.toByte(), 0x23.toByte(), 0, Ppp.CHAP_ALG_MSCHAPV2.toByte()))
                                } else {
                                    negotiatedAuth = Ppp.AUTH_CHAP_MS2
                                }
                            }
                            Ppp.AUTH_PAP -> negotiatedAuth = Ppp.AUTH_PAP
                            else -> {
                                // EAP/MS-CHAPv1/ прочее — Nak на PAP
                                naks += Ppp.optBytes(Ppp.LCP_OPT_AUTH,
                                    byteArrayOf(0xC0.toByte(), 0x23.toByte()))
                                negotiatedAuth = Ppp.AUTH_PAP
                            }
                        }
                    }
                }
                Ppp.LCP_OPT_MRU, Ppp.LCP_OPT_MAGIC, Ppp.LCP_OPT_PFC, Ppp.LCP_OPT_ACFC -> Unit // принимаем
                else -> Unit // неизвестные опции игнорируем (упрощение: ack)
            }
        }
        return if (naks.isEmpty()) Ppp.CODE_CONF_ACK to ByteArray(0)
        else Ppp.CODE_CONF_NAK to Ppp.encodeOptions(naks)
    }

    private fun authFromOption(o: Ppp.Option): Int =
        if (o.value.size >= 2) ((o.value[0].toInt() and 0xFF) shl 8) or (o.value[1].toInt() and 0xFF)
        else AUTH_NONE

    private fun maybeAdvanceFromLcp() {
        if (lcpAcked && peerAcked && phase == Phase.LCP) {
            if (negotiatedAuth != AUTH_NONE) {
                phase = Phase.AUTH
                onLog("PPP LCP: поднято, auth = " + when (negotiatedAuth) {
                    Ppp.AUTH_CHAP_MS2 -> "MS-CHAPv2"
                    Ppp.AUTH_PAP -> "PAP"
                    else -> "0x%04X".format(negotiatedAuth)
                })
                if (negotiatedAuth == Ppp.AUTH_PAP) sendPapAuth()
                // CHAP: ждём Challenge от сервера
            } else {
                onLog("PPP LCP: поднято, аутентификация не требуется")
                startIpcp()
            }
        }
    }

    // ----- CHAP / MS-CHAPv2 -----

    private fun onChap(info: ByteArray) {
        val pkt = runCatching { Ppp.parseControl(info) }.getOrNull() ?: return
        when (pkt.code) {
            Ppp.CHAP_CODE_CHALLENGE -> {
                if (pkt.data.isEmpty()) return
                val valueSize = pkt.data[0].toInt() and 0xFF
                if (pkt.data.size < 1 + valueSize) return
                val serverChallenge = pkt.data.copyOfRange(1, 1 + valueSize)
                val peerChallenge = ByteArray(16).also { random.nextBytes(it) }

                val auth = runCatching {
                    MsChapV2.authenticate(userName, password, serverChallenge, peerChallenge)
                }.getOrElse {
                    onLog("PPP CHAP: ошибка вычисления MS-CHAPv2: ${it.message}")
                    terminate("MS-CHAPv2 failure")
                    return
                }
                lastAuth = auth

                // Response: PeerChallenge(16) + Reserved(8, нули) + NTResponse(24) + Flags(1)
                val resp = peerChallenge + ByteArray(8) + auth.ntResponse + byteArrayOf(0)
                sendFrame(Ppp.controlFrame(Ppp.PROTO_CHAP, Ppp.CHAP_CODE_RESPONSE, pkt.id, resp))
                onLog("PPP CHAP: MS-CHAPv2 Response отправлен (id=${pkt.id})")
            }
            Ppp.CHAP_CODE_SUCCESS -> {
                val msg = String(pkt.data, Charsets.US_ASCII)
                peerAuthenticatorResponse = Regex("S=([0-9A-Fa-f]{40})").find(msg)?.groupValues?.get(1)
                        ?.let { "S=$it" }
                // взаимная аутентификация: сверяем authenticator response
                val ok = lastAuth?.let { peerAuthenticatorResponse == it.authenticatorResponse } ?: false
                if (!ok && lastAuth != null && peerAuthenticatorResponse != null) {
                    onLog("PPP CHAP: authenticator response не совпал (возможен MITM) — разрыв")
                    terminate("CHAP authenticator mismatch")
                    return
                }
                onLog("PPP CHAP: аутентификация успешна ✓")
                onAuthenticated?.invoke(lastAuth, peerAuthenticatorResponse)
                startIpcp()
            }
            Ppp.CHAP_CODE_FAILURE -> {
                val msg = String(pkt.data, Charsets.US_ASCII)
                onLog("PPP CHAP: отказ аутентификации: $msg")
                terminate("CHAP failure: $msg")
            }
        }
    }

    // ----- PAP -----

    private fun sendPapAuth() {
        authStarted = true
        val user = userName.toByteArray(Charsets.UTF_8)
        val pass = password.toByteArray(Charsets.UTF_8)
        val data = byteArrayOf(user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass
        sendFrame(Ppp.controlFrame(Ppp.PROTO_PAP, 1, id(), data))
        onLog("PPP PAP: запрос аутентификации отправлен")
    }

    private fun onPap(info: ByteArray) {
        val pkt = runCatching { Ppp.parseControl(info) }.getOrNull() ?: return
        when (pkt.code) {
            2 -> {
                onLog("PPP PAP: аутентификация успешна ✓")
                onAuthenticated?.invoke(null, null)
                startIpcp()
            }
            3 -> {
                val msg = if (pkt.data.size > 1)
                    String(pkt.data.copyOfRange(1, pkt.data.size), Charsets.US_ASCII) else ""
                onLog("PPP PAP: отказ аутентификации: $msg")
                terminate("PAP failure")
            }
        }
    }

    // ----- IPCP -----

    private fun startIpcp() {
        phase = Phase.IPCP
        sendIpcpConfigRequest()
    }

    private fun sendIpcpConfigRequest() {
        val opts = mutableListOf(
            Ppp.optIp(Ppp.IPCP_OPT_ADDR, intToIp(requestedIp)),
            Ppp.optIp(Ppp.IPCP_OPT_PRIMARY_DNS, "0.0.0.0"),
            Ppp.optIp(Ppp.IPCP_OPT_SECONDARY_DNS, "0.0.0.0"),
        )
        sendFrame(Ppp.controlFrame(Ppp.PROTO_IPCP, Ppp.CODE_CONF_REQ, id(), Ppp.encodeOptions(opts)))
    }

    private fun onIpcp(info: ByteArray) {
        val pkt = runCatching { Ppp.parseControl(info) }.getOrNull() ?: return
        when (pkt.code) {
            Ppp.CODE_CONF_REQ -> {
                // сервер просит согласовать его адрес; ack всё (в т.ч. может нести наш DNS)
                val opts = Ppp.parseOptions(pkt.data)
                for (o in opts) when (o.type) {
                    Ppp.IPCP_OPT_PRIMARY_DNS -> if (dns1 == null) dns1 = o.ipValue()
                    Ppp.IPCP_OPT_SECONDARY_DNS -> if (dns2 == null) dns2 = o.ipValue()
                }
                sendFrame(Ppp.controlFrame(Ppp.PROTO_IPCP, Ppp.CODE_CONF_ACK, pkt.id, pkt.data))
                peerIpcpAcked = true
                maybeIpcpUp()
            }
            Ppp.CODE_CONF_ACK -> {
                ipcpAcked = true
                maybeIpcpUp()
            }
            Ppp.CODE_CONF_NAK -> {
                val opts = Ppp.parseOptions(pkt.data)
                for (o in opts) when (o.type) {
                    Ppp.IPCP_OPT_ADDR -> requestedIp = ipToInt(o.ipValue())
                    Ppp.IPCP_OPT_PRIMARY_DNS -> dns1 = o.ipValue()
                    Ppp.IPCP_OPT_SECONDARY_DNS -> dns2 = o.ipValue()
                }
                sendIpcpConfigRequest()
            }
            Ppp.CODE_CONF_REJ -> sendIpcpConfigRequest()
        }
    }

    private fun maybeIpcpUp() {
        if (ipcpAcked && peerIpcpAcked && phase == Phase.IPCP) {
            val ip = intToIp(requestedIp)
            if (ip == "0.0.0.0") {
                onLog("PPP IPCP: сервер не назначил IP — разрыв")
                terminate("no IP assigned")
                return
            }
            assignedIp = ip
            phase = Phase.OPEN
            onLog("PPP IPCP: поднят ✓ IP=$ip DNS=${dns1 ?: "-"}${dns2?.let { ", $it" } ?: ""}")
            onUp(ip, dns1, dns2)
        }
    }

    // ----- CCP (игнорируем: без MPPE) -----

    private fun onCcp(info: ByteArray) {
        val pkt = runCatching { Ppp.parseControl(info) }.getOrNull() ?: return
        if (pkt.code == Ppp.CODE_CONF_REQ) {
            // Reject на MPPE (опция 18): шифрование PPP не поддерживаем
            val mppe = Ppp.parseOptions(pkt.data).filter { it.type == 18 }
            if (mppe.isNotEmpty()) {
                sendFrame(Ppp.controlFrame(Ppp.PROTO_CCP, Ppp.CODE_CONF_REJ, pkt.id, Ppp.encodeOptions(mppe)))
                onLog("PPP CCP: MPPE отклонён (не поддерживается)")
            } else {
                sendFrame(Ppp.controlFrame(Ppp.PROTO_CCP, Ppp.CODE_CONF_ACK, pkt.id, pkt.data))
            }
        }
    }

    // ----- прочее -----

    /** Отправить IP-пакет в туннель (исходящий, от устройства). */
    fun sendIpPacket(packet: ByteArray) {
        if (phase == Phase.OPEN) sendFrame(Ppp.frame(Ppp.PROTO_IP, packet))
    }

    fun close() {
        if (closed.compareAndSet(false, true) && phase != Phase.DEAD) {
            runCatching {
                sendFrame(Ppp.controlFrame(Ppp.PROTO_LCP, Ppp.CODE_TERM_REQ, id(), ByteArray(0)))
            }
            phase = Phase.DEAD
        }
    }

    private fun terminate(reason: String) {
        phase = Phase.DEAD
        onLog("PPP: сессия завершена: $reason")
        onDown(reason)
    }

    private fun intToIp(v: Int): String =
        "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"

    private fun ipToInt(s: String): Int =
        s.split(".").fold(0) { acc, part -> (acc shl 8) or (part.toIntOrNull() ?: 0) }

    companion object {
        const val AUTH_NONE = 0
    }
}
