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
    /** PPTP: без согласованного MPPE (128 бит, RFC 3078) данные не пускаем - иначе трафик пошёл бы открытым. */
    private val mppeRequired: Boolean = false,
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

    // ----- MPPE (PPTP) -----
    @Volatile private var mppe: ru.gidravpn.hydra.vpn.pptp.MppeSession? = null
    private var ccpOurBits = ru.gidravpn.hydra.vpn.pptp.Mppe.BIT_128 or ru.gidravpn.hydra.vpn.pptp.Mppe.BIT_STATELESS
    private var ccpPeerBits = 0
    private var ccpOurAcked = false
    private var ccpPeerAcked = false
    @Volatile private var lastCcpTxMs = 0L
    private var ccpRetries = 0
    private var resetRequested = false

    // ----- Restart-таймер (RFC 1661 §4.6) и LCP Echo (7d) -----
    // Раньше ConfReq уходил ровно один раз: потерянный кадр = ожидание общего таймаута
    // транспорта без единой повторной попытки, а «мёртвый» сервер за открытым PPP
    // не замечался вовсе (свой Echo-Request мы не слали, только отвечали на чужой).
    private var timer: java.util.concurrent.ScheduledExecutorService? = null
    @Volatile private var lastRxMs = 0L
    @Volatile private var lastLcpTxMs = 0L
    @Volatile private var lastIpcpTxMs = 0L
    @Volatile private var lastEchoMs = 0L
    @Volatile private var lcpRetries = 0
    @Volatile private var ipcpRetries = 0
    @Volatile private var echoMisses = 0

    private fun startTimers() {
        val now = System.currentTimeMillis()
        lastRxMs = now; lastEchoMs = now
        timer?.shutdownNow()
        timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ppp-timer").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay({ runCatching { tick() } }, 1, 1, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun stopTimers() {
        timer?.shutdownNow()
        timer = null
    }

    private fun tick() {
        if (closed.get()) return
        val now = System.currentTimeMillis()
        if (mppeRequired && phase == Phase.IPCP && !ccpOurAcked && now - lastCcpTxMs >= RESTART_MS) {
            if (++ccpRetries > MAX_CONFIGURE) { terminate("MPPE: сервер не ответил на согласование шифрования"); return }
            onLog("PPP CCP: повтор ConfReq (${ccpRetries}/$MAX_CONFIGURE)"); sendCcpConfigRequest()
        }
        when (phase) {
            Phase.LCP -> if (!lcpAcked && now - lastLcpTxMs >= RESTART_MS) {
                if (++lcpRetries > MAX_CONFIGURE) terminate("LCP: сервер не ответил на Configure-Request")
                else { onLog("PPP LCP: повтор ConfReq (${lcpRetries}/$MAX_CONFIGURE)"); sendLcpConfigRequest() }
            }
            Phase.IPCP -> if (!ipcpAcked && now - lastIpcpTxMs >= RESTART_MS) {
                if (++ipcpRetries > MAX_CONFIGURE) terminate("IPCP: сервер не ответил на Configure-Request")
                else { onLog("PPP IPCP: повтор ConfReq (${ipcpRetries}/$MAX_CONFIGURE)"); sendIpcpConfigRequest() }
            }
            Phase.OPEN -> if (now - lastRxMs >= ECHO_IDLE_MS && now - lastEchoMs >= ECHO_IDLE_MS) {
                if (echoMisses >= MAX_ECHO_MISSES) {
                    terminate("LCP: сервер не отвечает на Echo-Request")
                } else {
                    echoMisses++; lastEchoMs = now
                    val magic = Ppp.int32((ourMagic and 0xFFFFFFFFL).toInt())
                    sendFrame(Ppp.controlFrame(Ppp.PROTO_LCP, Ppp.CODE_ECHO_REQ, id(), magic))
                }
            }
            else -> Unit
        }
    }

    /** Начать согласование (вызывать после установки транспорта). */
    fun start() {
        phase = Phase.LCP
        ourMagic = random.nextLong()
        sendLcpConfigRequest()
        startTimers()
    }

    /** Входящий кадр PPP от транспорта. */
    fun onFrame(frame: ByteArray) {
        lastRxMs = System.currentTimeMillis()
        echoMisses = 0
        val (proto, info) = Ppp.parseFrame(frame) ?: return
        dispatch(proto, info)
    }

    private fun dispatch(proto: Int, info: ByteArray) {
        when (proto) {
            Ppp.PROTO_COMP -> onMppeData(info)
            Ppp.PROTO_LCP -> onLcp(info)
            Ppp.PROTO_CHAP -> onChap(info)
            Ppp.PROTO_PAP -> onPap(info)
            Ppp.PROTO_IPCP -> onIpcp(info)
            // Открытый IP при согласованном MPPE - подмена или сбой: не принимаем.
            Ppp.PROTO_IP -> if (phase == Phase.OPEN && mppe == null) onIpPacket(info)
            Ppp.PROTO_CCP -> onCcp(info)
            else -> {
                // Protocol-Reject для неизвестных
                if (phase != Phase.OPEN) onLog("PPP: неизвестный протокол 0x%04X".format(proto))
            }
        }
    }

    // ----- LCP -----

    private fun sendLcpConfigRequest() {
        lastLcpTxMs = System.currentTimeMillis()
        val opts = mutableListOf(
            // MRU — 2 байта (RFC 1661 §6.1). До 0.6.24 уходило 4 (optInt) — неверная длина опции.
            Ppp.optShort(Ppp.LCP_OPT_MRU, ourMru),
        )
        if (ourMagic != 0L) opts += Ppp.optInt(Ppp.LCP_OPT_MAGIC, (ourMagic and 0xFFFFFFFFL).toInt())
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
                // Echo-Reply: сначала наш Magic-Number (RFC 1661 §5.8), а не ноль.
                val data = Ppp.int32((ourMagic and 0xFFFFFFFFL).toInt())
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
                                // Значение опции CHAP — 3 байта: C223 + алгоритм (RFC 1994 §3). До 0.6.24 читался
                                // value[4] — его нет, и на правильный MS-CHAPv2 всегда уходил Nak (петля).
                                if (o.value.size < 3 || (o.value[2].toInt() and 0xFF) != Ppp.CHAP_ALG_MSCHAPV2) {
                                    naks += Ppp.optBytes(Ppp.LCP_OPT_AUTH,
                                        byteArrayOf(0xC2.toByte(), 0x23.toByte(), Ppp.CHAP_ALG_MSCHAPV2.toByte()))
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
        // Configure-Ack обязан повторить опции дословно (RFC 1661 §5.2); до 0.6.24 уходил пустым.
        return if (naks.isEmpty()) Ppp.CODE_CONF_ACK to data
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

                // RFC 2759 §4: Value-Size (49) + [PeerChallenge(16) + Reserved(8) + NT-Response(24) +
                // Flags(1)] + Name. До 0.6.24 не было ни Value-Size, ни имени — сервер отбросил бы пакет.
                val value = peerChallenge + ByteArray(8) + auth.ntResponse + byteArrayOf(0)
                val resp = byteArrayOf(value.size.toByte()) + value + userName.toByteArray(Charsets.UTF_8)
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
        if (mppeRequired) {
            if (lastAuth == null) { terminate("MPPE требует аутентификации MS-CHAPv2"); return }
            sendCcpConfigRequest()
        }
    }

    private fun sendIpcpConfigRequest() {
        lastIpcpTxMs = System.currentTimeMillis()
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
        if (ipcpAcked && peerIpcpAcked && phase == Phase.IPCP && (!mppeRequired || mppe != null)) {
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

    // ----- CCP / MPPE -----

    private fun sendCcpConfigRequest() {
        lastCcpTxMs = System.currentTimeMillis()
        val opt = Ppp.Option(ru.gidravpn.hydra.vpn.pptp.Mppe.CCP_OPTION, ru.gidravpn.hydra.vpn.pptp.Mppe.optionBytes(ccpOurBits))
        sendFrame(Ppp.controlFrame(Ppp.PROTO_CCP, Ppp.CODE_CONF_REQ, id(), Ppp.encodeOptions(listOf(opt))))
    }

    private fun onCcp(info: ByteArray) {
        val pkt = runCatching { Ppp.parseControl(info) }.getOrNull() ?: return
        val m = ru.gidravpn.hydra.vpn.pptp.Mppe
        when (pkt.code) {
            Ppp.CODE_CONF_REQ -> {
                val opts = Ppp.parseOptions(pkt.data)
                val mppeOpt = opts.firstOrNull { it.type == m.CCP_OPTION }
                if (!mppeRequired) {
                    // Шифрование не включено в профиле (SSTP/L2TP): MPPE отклоняем, остальное подтверждаем.
                    if (mppeOpt != null) {
                        sendFrame(Ppp.controlFrame(Ppp.PROTO_CCP, Ppp.CODE_CONF_REJ, pkt.id, Ppp.encodeOptions(listOf(mppeOpt))))
                        onLog("PPP CCP: MPPE отклонён (не включён)")
                    } else sendFrame(Ppp.controlFrame(Ppp.PROTO_CCP, Ppp.CODE_CONF_ACK, pkt.id, pkt.data))
                    return
                }
                val others = opts.filter { it.type != m.CCP_OPTION }
                if (others.isNotEmpty()) {
                    sendFrame(Ppp.controlFrame(Ppp.PROTO_CCP, Ppp.CODE_CONF_REJ, pkt.id, Ppp.encodeOptions(others)))
                    return
                }
                val bits = mppeOpt?.let { m.optionBits(it.value) }
                if (bits == null) {
                    val nak = Ppp.Option(m.CCP_OPTION, m.optionBytes(m.BIT_128 or m.BIT_STATELESS))
                    sendFrame(Ppp.controlFrame(Ppp.PROTO_CCP, Ppp.CODE_CONF_NAK, pkt.id, Ppp.encodeOptions(listOf(nak))))
                    return
                }
                val clean = m.BIT_128 or (bits and m.BIT_STATELESS)
                if (bits != clean) {
                    // 40/56 бит и MPPC не поддерживаем: оставляем 128 бит.
                    val nak = Ppp.Option(m.CCP_OPTION, m.optionBytes(clean))
                    sendFrame(Ppp.controlFrame(Ppp.PROTO_CCP, Ppp.CODE_CONF_NAK, pkt.id, Ppp.encodeOptions(listOf(nak))))
                    onLog("PPP CCP: сервер предложил MPPE 0x%08X - оставляем 128 бит".format(bits))
                    return
                }
                sendFrame(Ppp.controlFrame(Ppp.PROTO_CCP, Ppp.CODE_CONF_ACK, pkt.id, pkt.data))
                ccpPeerBits = bits; ccpPeerAcked = true
                tryActivateMppe()
            }
            Ppp.CODE_CONF_ACK -> if (mppeRequired) { ccpOurAcked = true; tryActivateMppe() }
            Ppp.CODE_CONF_NAK -> if (mppeRequired) {
                val bits = Ppp.parseOptions(pkt.data).firstOrNull { it.type == m.CCP_OPTION }?.let { m.optionBits(it.value) }
                if (bits == null || bits and m.BIT_128 == 0) { terminate("MPPE: сервер не поддерживает 128-битное шифрование"); return }
                ccpOurBits = m.BIT_128 or (bits and m.BIT_STATELESS)
                sendCcpConfigRequest()
            }
            Ppp.CODE_CONF_REJ -> if (mppeRequired) terminate("MPPE: сервер отклонил шифрование")
            CCP_RESET_REQ -> {
                // Сервер потерял синхронизацию потока RC4 (stateful): ответить и начать поток заново.
                mppe?.flushTx()
                sendFrame(Ppp.controlFrame(Ppp.PROTO_CCP, CCP_RESET_ACK, pkt.id, ByteArray(0)))
            }
            CCP_RESET_ACK -> resetRequested = false
        }
    }

    private fun tryActivateMppe() {
        if (!ccpOurAcked || !ccpPeerAcked || mppe != null) return
        val key = lastAuth?.masterKey ?: run { terminate("MPPE требует аутентификации MS-CHAPv2"); return }
        val m = ru.gidravpn.hydra.vpn.pptp.Mppe
        // Биты запроса стороны определяют, как шифрует ОНА для нас: наш запрос -> приём, запрос сервера -> передача.
        mppe = ru.gidravpn.hydra.vpn.pptp.MppeSession(key, txStateless = ccpPeerBits and m.BIT_STATELESS != 0, rxStateless = ccpOurBits and m.BIT_STATELESS != 0)
        onLog("PPP CCP: MPPE 128 бит согласован (${if (ccpOurBits and m.BIT_STATELESS != 0) "stateless" else "stateful"}) ✓")
        maybeIpcpUp()
    }

    private fun onMppeData(info: ByteArray) {
        val s = mppe ?: return
        val plain = s.decrypt(info)
        if (plain == null) {
            // Stateful: поток RC4 разошёлся - просим сервер начать заново (CCP Reset-Request), пока не пришёл Reset-Ack.
            if (!resetRequested) {
                resetRequested = true
                sendFrame(Ppp.controlFrame(Ppp.PROTO_CCP, CCP_RESET_REQ, id(), ByteArray(0)))
            }
            return
        }
        val (proto, inner) = Ppp.parseFrame(plain) ?: return
        if (proto == Ppp.PROTO_COMP) return                   // вложенное шифрование недопустимо
        if (proto == Ppp.PROTO_IP) { if (phase == Phase.OPEN) onIpPacket(inner) } else dispatch(proto, inner)
    }

    // ----- прочее -----

    /** Отправить IP-пакет в туннель (исходящий, от устройства). */
    fun sendIpPacket(packet: ByteArray) {
        if (phase != Phase.OPEN) return
        val plain = Ppp.frame(Ppp.PROTO_IP, packet)
        val s = mppe
        sendFrame(if (s == null) plain else Ppp.frame(Ppp.PROTO_COMP, s.encrypt(plain)))
    }

    fun close() {
        stopTimers()
        if (closed.compareAndSet(false, true) && phase != Phase.DEAD) {
            runCatching {
                sendFrame(Ppp.controlFrame(Ppp.PROTO_LCP, Ppp.CODE_TERM_REQ, id(), ByteArray(0)))
            }
            phase = Phase.DEAD
        }
    }

    private fun terminate(reason: String) {
        stopTimers()
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
        private const val CCP_RESET_REQ = 14
        private const val CCP_RESET_ACK = 15
        private const val RESTART_MS = 3_000L      // Restart-таймер RFC 1661 (3 с)
        private const val MAX_CONFIGURE = 10         // Max-Configure
        private const val ECHO_IDLE_MS = 15_000L     // тишина в линии до Echo-Request
        private const val MAX_ECHO_MISSES = 3
    }
}
