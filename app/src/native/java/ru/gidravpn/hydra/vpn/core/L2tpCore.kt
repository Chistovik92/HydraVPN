package ru.gidravpn.hydra.vpn.core

import android.os.ParcelFileDescriptor
import org.json.JSONObject
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.vpn.ppp.PppSession
import ru.gidravpn.hydra.vpn.ppp.TunBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * L2TP-клиент (RFC 2661), userspace, без IPsec/ESP:
 * SCCRQ → SCCRP → SCCCN (туннель), ICRQ → ICRP → ICCN (сессия),
 * затем PPP (LCP → PAP/MS-CHAPv2 → IPCP) поверх канала данных и
 * мост TunBridge в tun-интерфейс.
 *
 * L2TP/IPsec (ESP) на уровне приложения недоступен — честная позиция
 * проекта: поддержан «чистый» L2TP (аутентификация на PPP), для
 * шифрования канала используйте SSTP или WireGuard. См. docs/PROTOCOLS.md.
 */
class L2tpCore : VpnCore {

    override val name = "L2TP (userspace PPP/UDP)"

    private var transport: L2tpTransport? = null
    private var session: PppSession? = null
    private var bridge: TunBridge? = null
    @Volatile private var running = false

    override fun start(
        tun: ParcelFileDescriptor,
        profile: ServerProfile,
        onLog: (String) -> Unit,
        onStats: (TrafficStats) -> Unit,
    ) {
        val extra = runCatching { JSONObject(profile.extra) }.getOrDefault(JSONObject())
        val username = extra.optString("username")
        val password = profile.uuidOrPassword
        if (username.isBlank() || password.isBlank())
            throw IllegalStateException("L2TP: нужны username/password (l2tp://user:pass@host)")
        val secret = extra.optString("tunnel_secret").toByteArray() // опц. tunnel-auth

        val t = L2tpTransport(profile.address, profile.port, onLog)
        transport = t
        running = true
        t.start()

        t.onTunnelDown = { reason ->
            if (running) onLog("L2TP: сервер закрыл туннель: $reason")
            running = false
        }

        // --- SCCRQ → SCCRP: контрольное соединение ---
        onLog("L2TP: установка туннеля к ${profile.address}:${profile.port}…")
        val sccrp = t.exchange(
            L2tpTransport.Ctrl.SCCRQ,
            listOf(
                L2TP_PROTO_VERSION,       // ver 1.0
                L2TP_FRAMING_SYNC_ASYNC,
                L2TP_BEARER_ANALOG,
                L2TP_HOST_NAME,
                L2TP_TUNNEL_ID(t),
                L2TP_VENDOR_NAME,
            ),
            L2tpTransport.Ctrl.SCCRP,
        ) ?: throw IllegalStateException("L2TP: нет SCCRP (сервер L2TP доступен?)")

        // tunnel-auth: Challenge в SCCRP → Challenge Response в SCCCN
        val challenge = sccrp[11] // AVP Challenge
        val scccnAvps = mutableListOf<Pair<Int, ByteArray>>()
        if (challenge != null && secret.isNotEmpty()) {
            scccnAvps += 13 to t.challengeResponse(L2tpTransport.Ctrl.SCCCN, secret, challenge)
            onLog("L2TP: tunnel-auth (Challenge Response в SCCCN)")
        } else if (challenge != null) {
            onLog("L2TP: сервер требует tunnel-auth, но secret не задан — попытка без него")
        }
        t.exchange(L2tpTransport.Ctrl.SCCCN, scccnAvps, null)
        onLog("L2TP: туннель установлен (peerTunnelId=${t.peerTunnelId})")

        // --- ICRQ → ICRP: сессия ---
        val icrp = t.exchange(
            L2tpTransport.Ctrl.ICRQ,
            listOf(
                L2TP_SESSION_ID(t),
                L2TP_CALL_SERIAL,
                L2TP_BEARER_TYPE_ANALOG,
                L2TP_CALLING_NUMBER,
            ),
            L2tpTransport.Ctrl.ICRP,
        ) ?: throw IllegalStateException("L2TP: нет ICRP — сервер отклонил сессию")
        onLog("L2TP: сессия согласована (peerSessionId=${t.peerSessionId})")

        // --- ICCN ---
        t.exchange(
            L2tpTransport.Ctrl.ICCN,
            listOf(L2TP_TX_SPEED, L2TP_FRAMING_ASYNC),
            null,
        )
        onLog("L2TP: ICCN отправлен, фаза PPP")

        // --- PPP поверх L2TP ---
        val upLatch = CountDownLatch(1)
        val ppp = PppSession(
            userName = username,
            password = password,
            sendFrame = { frame -> runCatching { t.sendData(frame) } },
            onLog = onLog,
            onAuthenticated = { _, _ -> },
            onIpPacket = { packet -> bridge?.onTunnelPacket(packet) },
            onUp = { ip, dns1, dns2 ->
                onLog("L2TP: PPP поднят, IP=$ip DNS=${dns1 ?: "-"}")
                upLatch.countDown()
            },
            onDown = { reason -> if (running) onLog("L2TP: PPP закрыт: $reason") },
        )
        session = ppp
        t.onData = { frame -> ppp.onFrame(frame) }
        ppp.start()

        if (!upLatch.await(PPP_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            throw IllegalStateException("L2TP: PPP не поднялся за ${PPP_TIMEOUT_MS / 1000} с (фаза ${ppp.phase})")

        val tunBridge = TunBridge(tun = tun, session = ppp, onStats = onStats, onLog = onLog)
        bridge = tunBridge
        tunBridge.start()
        onLog("L2TP: туннель активен ✓")
    }

    override fun stop() {
        running = false
        runCatching { session?.close() }
        bridge?.stop()
        transport?.stop()
        transport = null
        session = null
        bridge = null
    }

    companion object {
        private const val PPP_TIMEOUT_MS = 30_000L

        // AVP-значения
        private val L2TP_PROTO_VERSION = 2 to byteArrayOf(0x01, 0x00)            // ver 1, rev 0
        private val L2TP_FRAMING_SYNC_ASYNC = 3 to L2tpTransport.be32Bytes(3)    // A|S
        private val L2TP_BEARER_ANALOG = 4 to L2tpTransport.be32Bytes(1)         // A
        private val L2TP_HOST_NAME = 7 to "hydra".toByteArray()
        private val L2TP_VENDOR_NAME = 8 to "HydraVPN".toByteArray()
        private val L2TP_BEARER_TYPE_ANALOG = 18 to L2tpTransport.be32Bytes(1)   // analog
        private val L2TP_FRAMING_ASYNC = 19 to L2tpTransport.be32Bytes(1)        // async
        private val L2TP_TX_SPEED = 24 to L2tpTransport.be32Bytes(10_000_000)    // 10 Мбит/с
        private val L2TP_CALLING_NUMBER = 22 to "hydra".toByteArray()

        private fun L2TP_TUNNEL_ID(t: L2tpTransport) = 9 to byteArrayOf(
            ((t.ourTunnelId shr 8) and 0xFF).toByte(), (t.ourTunnelId and 0xFF).toByte())
        private fun L2TP_SESSION_ID(t: L2tpTransport) = 14 to byteArrayOf(
            ((t.ourSessionId shr 8) and 0xFF).toByte(), (t.ourSessionId and 0xFF).toByte())
        private val L2TP_CALL_SERIAL = 15 to L2tpTransport.be32Bytes(
            (System.currentTimeMillis() and 0xFFFFFFFFL).toInt())
    }
}
