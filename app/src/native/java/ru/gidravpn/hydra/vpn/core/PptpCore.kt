package ru.gidravpn.hydra.vpn.core

import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import ru.gidravpn.hydra.AppCtx
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.vpn.SocketGuard
import ru.gidravpn.hydra.vpn.ppp.PppSession
import ru.gidravpn.hydra.vpn.ppp.TunBridge
import ru.gidravpn.hydra.vpn.pptp.PptpClient
import ru.gidravpn.hydra.vpn.pptp.RootGreTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PPTP (RFC 2637), userspace: управляющее соединение TCP 1723 + PPP (MS-CHAPv2) + MPPE 128 бит.
 *
 * GRE (IP-протокол 47) требует raw-сокета, которого у приложения без root нет, и системный PPTP удалён из Android 12+.
 * Поэтому GRE идёт через root-помощника ([RootGreTransport]): на устройстве без root ядро честно сообщает причину.
 * PPTP небезопасен (MS-CHAPv2 и MPPE на RC4 взламываются) - в профиле можно отключить шифрование `mppe=off`, но по
 * умолчанию без согласованного MPPE данные не пропускаются. См. docs/PROTOCOLS.md.
 */
class PptpCore : VpnCore {

    override val name = "PPTP (userspace PPP/GRE, root)"

    private var client: PptpClient? = null
    private var session: PppSession? = null
    private var bridge: TunBridge? = null
    @Volatile private var running = false
    @Volatile private var deathListener: ((String) -> Unit)? = null

    override fun setDeathListener(listener: (reason: String) -> Unit) { deathListener = listener }

    private fun died(reason: String) {
        if (!running) return
        running = false
        deathListener?.invoke(reason)
    }

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
            throw IllegalStateException("PPTP: нужны username/password (pptp://user:pass@host)")
        val mppeRequired = !extra.optString("mppe").equals("off", ignoreCase = true)
        val ctx = AppCtx.appContext ?: throw IllegalStateException("PPTP: нет контекста приложения")

        // Физическая сеть: до неё идут и TCP 1723, и GRE; имя сервера резолвим через неё, а не через собственный tun.
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = cm?.let { PhysicalNetwork.pick(it) }
        val iface = net?.let { cm.getLinkProperties(it)?.interfaceName }.orEmpty()
        val netId = net?.toString()?.toIntOrNull() ?: 0   // Network.toString() - числовой netId
        val serverIp = (net?.getByName(profile.address) ?: java.net.InetAddress.getByName(profile.address)).hostAddress.orEmpty()

        running = true
        val upLatch = CountDownLatch(1)
        lateinit var ppp: PppSession
        val c = PptpClient(
            host = serverIp, port = profile.port.takeIf { it > 0 } ?: 1723,
            openControl = { java.net.Socket().also { s -> SocketGuard.protect(s); net?.bindSocket(s) } },
            openGre = { onGre ->
                RootGreTransport(
                    apkPath = ctx.applicationInfo.sourceDir, serverIp = serverIp, iface = iface, netId = netId,
                    onGre = onGre, onLog = onLog, onDead = { died(it) },
                ).also { it.open() }
            },
            // GRE несёт кадр PPP с адресом/управлением (FF 03): снимаем их, PppSession ждёт кадр с протокола.
            onFrame = { f ->
                val frame = if (f.size >= 2 && f[0] == 0xFF.toByte() && f[1] == 0x03.toByte()) f.copyOfRange(2, f.size) else f
                ppp.onFrame(frame)
            },
            onDown = { died(it) },
            onLog = onLog,
        )
        client = c
        ppp = PppSession(
            userName = username,
            password = password,
            sendFrame = { frame -> c.sendFrame(byteArrayOf(0xFF.toByte(), 0x03) + frame) },
            onLog = onLog,
            onAuthenticated = { _, _ -> },
            onIpPacket = { packet -> bridge?.onTunnelPacket(packet) },
            onUp = { ip, dns1, dns2 ->
                onLog("PPTP: PPP поднят, IP=$ip DNS=${dns1 ?: "-"}")
                upLatch.countDown()
            },
            onDown = { reason ->
                if (running) onLog("PPTP: PPP закрыт: $reason")
                died("PPTP: PPP закрыт ($reason)")
            },
            mppeRequired = mppeRequired,
        )
        session = ppp

        c.connect()
        ppp.start()
        if (!upLatch.await(PPP_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            throw IllegalStateException("PPTP: PPP не поднялся за ${PPP_TIMEOUT_MS / 1000} с (фаза ${ppp.phase})")

        val tunBridge = TunBridge(tun = tun, session = ppp, onStats = onStats, onLog = onLog)
        bridge = tunBridge
        tunBridge.start()
        onLog("PPTP: туннель активен ✓${if (mppeRequired) " (MPPE 128)" else " (без шифрования!)"}")
    }

    override fun stop() {
        running = false
        runCatching { session?.close() }
        bridge?.stop()
        runCatching { client?.close() }
        client = null
        session = null
        bridge = null
    }

    private companion object { const val PPP_TIMEOUT_MS = 30_000L }
}
