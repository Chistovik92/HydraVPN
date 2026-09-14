package ru.gidravpn.hydra.vpn.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import ru.gidravpn.hydra.AppCtx
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.SplitTunnel
import ru.gidravpn.hydra.data.repository.SplitTunnelRepository
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder
import ru.gidravpn.hydra.vpn.SocketGuard
import ru.gidravpn.hydra.vpn.core.xray.IXrayEngine
import ru.gidravpn.hydra.vpn.core.xray.IXraySocketProtector
import ru.gidravpn.hydra.vpn.core.xray.XrayEngineService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Xray-core (`libXray.aar`) + sing-box как tun2socks-мост.
 *
 * Xray-core запускается не здесь, а в отдельном процессе (`:xray`,
 * [XrayEngineService]) — libbox (sing-box, этот процесс) и libXray не могут
 * делить один процесс: у каждого gomobile-сборки свой Go-рантайм, и Go не
 * поддерживает два независимых рантайма в одном процессе (см. docs/BUILD.md,
 * раздел 2.2). Общение — через AIDL ([IXrayEngine]/[IXraySocketProtector]),
 * только строки/[ParcelFileDescriptor] через границу.
 *
 * Сам Xray сам tun не обслуживает (docs/PROTOCOLS.md, раздел «Xray»): он
 * поднимается headless с локальным socks5-inbound (`127.0.0.1:$socksPort`,
 * см. [XrayConfigBuilder]), а TUN/статистику/split tunneling берёт на себя
 * уже проверенный на устройстве [SingBoxCore] — конфиг строит
 * [SingBoxConfigBuilder.buildXrayBridge] (единственный outbound — socks на
 * тот же порт).
 */
class XrayCore : VpnCore {
    override val name = "Xray-core"

    private val socksPort = 10808
    private var bridge: SingBoxCore? = null
    private var engine: IXrayEngine? = null
    private var connection: ServiceConnection? = null
    private var xrayRunning = false

    override fun start(
        tun: ParcelFileDescriptor,
        profile: ServerProfile,
        onLog: (String) -> Unit,
        onStats: (TrafficStats) -> Unit,
    ) {
        // bindService к :xray доставляет onServiceConnected через Handler
        // главного потока — если ждать его здесь же на главном потоке, будет
        // взаимная блокировка (никогда не дождёмся собственного колбэка).
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "XrayCore.start() не должен вызываться на главном потоке"
        }
        val ctx = AppCtx.appContext ?: error("AppCtx не инициализирован")
        val (dnsAddress, geoRouting) = resolveRouting(ctx)

        val xrayConfig = XrayConfigBuilder.build(profile, socksPort, dnsAddress = dnsAddress)
        onLog("Xray: конфиг сгенерирован (${xrayConfig.length} байт)")

        val svc = bindEngine(ctx)
        svc.setProtector(object : IXraySocketProtector.Stub() {
            override fun protect(pfd: ParcelFileDescriptor?): Boolean {
                val ok = pfd?.let { SocketGuard.protect(it.fd) } ?: false
                runCatching { pfd?.close() }
                return ok
            }
        })

        val runResponse = JSONObject(svc.runXray(xrayConfig))
        if (!runResponse.optBoolean("success")) {
            error("Xray: ${runResponse.optString("error", "runXray failed")}")
        }
        xrayRunning = true
        onLog("Xray: ядро запущено в процессе :xray (127.0.0.1:$socksPort)")

        val split = runBlocking { SplitTunnelRepository(ctx).settings.firstOrNull() } ?: SplitTunnel()
        val bridgeConfig = SingBoxConfigBuilder.buildXrayBridge(
            socksPort, split, dnsAddress = dnsAddress, geoRouting = geoRouting
        ).toString(2)
        onLog("Xray: sing-box-мост, конфиг сгенерирован (${bridgeConfig.length} байт)")

        val b = SingBoxCore()
        bridge = b
        b.runConfig(tun, bridgeConfig, onLog, onStats)
    }

    override fun stop() {
        bridge?.stop()
        bridge = null
        if (xrayRunning) runCatching { engine?.stopXray() }
        xrayRunning = false
        runCatching { connection?.let { AppCtx.appContext?.unbindService(it) } }
        engine = null
        connection = null
    }

    private fun bindEngine(ctx: Context): IXrayEngine {
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                engine = IXrayEngine.Stub.asInterface(binder)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                engine = null
            }
        }
        connection = conn
        ctx.bindService(Intent(ctx, XrayEngineService::class.java), conn, Context.BIND_AUTO_CREATE)
        check(latch.await(10, TimeUnit.SECONDS)) { "Xray: процесс :xray не ответил за 10с" }
        return engine ?: error("Xray: bindService не вернул IXrayEngine")
    }
}
