package ru.gidravpn.hydra.vpn.core

import android.content.Context
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import ru.gidravpn.hydra.data.dpi.DpiArgs
import ru.gidravpn.hydra.data.dpi.DpiSettings
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.repository.RoutingRepository
import ru.gidravpn.hydra.data.model.Engine
import ru.gidravpn.hydra.data.repository.ServerRepository
import ru.gidravpn.hydra.data.subscription.OlcRtcConfigBuilder
import ru.gidravpn.hydra.data.subscription.OpenFluxArgs
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder
import ru.gidravpn.hydra.data.routing.RouteConfig
import ru.gidravpn.hydra.data.routing.RouteKind
import ru.gidravpn.hydra.data.routing.RoutePlan
import ru.gidravpn.hydra.data.routing.RoutePlanApplier
import ru.gidravpn.hydra.data.routing.RoutePlanFactory
import ru.gidravpn.hydra.data.routing.viaOf
import ru.gidravpn.hydra.data.routing.RouteTarget
import ru.gidravpn.hydra.data.subscription.GeoAssets
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/** Профиль «Обход DPI (ByeDPI)»: клиент → локальный SOCKS5 → sing-box → tun, трафик выходит напрямую, но «порезанным». */
class ByeDpiCore : SocksBridgeCore() {
    override val name = "ByeDPI"
    override val binaryName = ByeDpiSidecar.BINARY
    override val socksPort = DpiSettings.DEFAULT_PORT + 2   // 10880 — ByeDPI «напрямую», +1 — проверка стратегий
    override val label = "ByeDPI"
    override val buildScript = "scripts/build-byedpi.sh"
    override val readyTimeoutMs = 8_000L

    override fun prepare(ctx: Context, profile: ServerProfile, secrets: MutableList<File>): List<String> {
        val strategy = runCatching { JSONObject(profile.extra).optString("strategy") }.getOrDefault("")
        return DpiArgs.build(strategy, socksPort)
    }
}

/** Результат [RoutePlanRuntime.prepare]: итоговый конфиг и запущенные подпроцессы (ByeDPI, клиенты узлов), которые надо остановить вместе с ядром. */
internal class PreparedRoute(val config: String, val sidecars: List<AutoCloseable>) {
    fun stop() = sidecars.forEach { runCatching { it.close() } }
}

/** Применяет настройки маршрутизации (правила, группы, цепочки, обход DPI, geo-базы) к конфигу sing-box перед запуском. */
internal object RoutePlanRuntime {

    /** Любая ошибка подготовки — не повод рвать подключение: логируем и поднимаем туннель с исходным конфигом. */
    fun prepare(ctx: Context?, config: String, profile: ServerProfile?, onLog: (String) -> Unit): PreparedRoute {
        val started = mutableListOf<AutoCloseable>()
        return try {
            prepareUnsafe(ctx, config, profile, onLog, started)
        } catch (e: Exception) {
            started.forEach { runCatching { it.close() } }
            onLog("Маршрутизация: не применена, подключаемся без неё — ${e.javaClass.simpleName}: ${e.message}")
            PreparedRoute(config, emptyList())
        }
    }

    private fun prepareUnsafe(ctx: Context?, config: String, profile: ServerProfile?, onLog: (String) -> Unit, sidecars: MutableList<AutoCloseable>): PreparedRoute {
        if (ctx == null) return PreparedRoute(config, emptyList())
        val cfg = runBlocking { RoutingRepository(ctx).routeConfig.firstOrNull() } ?: RouteConfig()
        if (cfg.rules.isEmpty() && cfg.groups.isEmpty() && !cfg.dpi.enabled && (profile == null || viaOf(profile) == null)) return PreparedRoute(config, emptyList())

        val failedNodes = mutableSetOf<String>()
        // Узлы на движках-подпроцессах (OpenFlux, olcRTC): каждому свой клиент на свободном порту, выход — socks на этот порт.
        val nodeOutbound = { p: ServerProfile, tag: String ->
            when (p.protocol?.engine) {
                Engine.SINGBOX -> SingBoxConfigBuilder.nodeOutbound(p, tag)
                Engine.OPENFLUX, Engine.OLCRTC -> startNode(ctx, p, tag, sidecars, onLog)
                else -> null
            } ?: run { failedNodes += tag; null }
        }
        var plan = RoutePlanFactory.build(cfg, profile, setOf(RouteKind.APP), { id -> runBlocking { ServerRepository(ctx).byId(id) } }, nodeOutbound)
        failedNodes.forEach { onLog("Маршрутизация: выход $it недоступен — протокол не поддерживается как узел или клиент не запустился") }
        if (plan.isEmpty) { PreparedRoute(config, sidecars).stop(); return PreparedRoute(config, emptyList()) }

        if (plan.needsDpi) {
            val s = ByeDpiSidecar(ctx, plan.dpi, onLog)
            try {
                s.start()
                sidecars += s
            } catch (e: Exception) {
                // Без обхода DPI соединение лучше, чем никакого: убираем всё, что на него опирается.
                onLog("ByeDPI: не запущен, продолжаем без него — ${e.message}")
                s.stop()
                plan = plan.withoutDpi()
            }
        }
        if (plan.needsTgWs) {
            // 0.7.14: Telegram через WebSocket — свой SOCKS5 внутри приложения (его сокеты вне tun, как и у ByeDPI).
            val tg = ru.gidravpn.hydra.data.tgws.TgWsProxy(log = onLog)
            try {
                tg.start(RouteTarget.TGWS_PORT)
                runCatching { tg.startMtProto(RouteTarget.TGWS_MT_PORT) }.onFailure { onLog("TG WS: MTProto-прокси не запущен (${it.message}) - работает только SOCKS5") }
                sidecars += AutoCloseable { tg.stop() }
            } catch (e: Exception) {
                onLog("TG WS: не запущен, продолжаем без него — ${e.message}")
                tg.stop()
                plan = plan.withoutTgWs()
            }
        }
        val root = JSONObject(config)
        val geo = { kind: RouteKind, name: String -> GeoAssets.pathFor(ctx, kind == RouteKind.GEOSITE, name) }
        RoutePlanApplier.apply(root, plan, geo).forEach { onLog("Маршрутизация: $it") }
        return PreparedRoute(root.toString(2), sidecars)
    }

    /** Запускает клиент OpenFlux/olcRTC для узла и возвращает socks-outbound на его порт; null — не вышло. */
    private fun startNode(ctx: Context, p: ServerProfile, tag: String, sidecars: MutableList<AutoCloseable>, onLog: (String) -> Unit): JSONObject? {
        val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val secrets = mutableListOf<File>()
        fun secret(name: String, text: String) = File(ctx.filesDir, "$tag-$name").also {
            it.writeText(text); it.setReadable(false, false); it.setReadable(true, true); secrets += it
        }
        return try {
            val proc = when (p.protocol?.engine) {
                Engine.OPENFLUX -> {
                    val key = p.uuidOrPassword.takeIf { it.isNotEmpty() }?.let { secret("openflux.key", it).path }
                    SocksProcess(ctx, "OpenFlux[$tag]", "libopenflux.so", OpenFluxArgs.build(p, port, key), port, 45_000, secrets, onLog)
                }
                else -> {
                    val cfg = secret("olcrtc.yaml", OlcRtcConfigBuilder.build(p, port))
                    SocksProcess(ctx, "olcRTC[$tag]", "libolcrtc.so", listOf(cfg.path), port, 45_000, secrets, onLog)
                }
            }
            proc.start()
            sidecars += proc
            JSONObject().put("type", "socks").put("tag", tag).put("server", "127.0.0.1").put("server_port", port).put("version", "5")
        } catch (e: Exception) {
            onLog("Маршрутизация: узел $tag не запущен — ${e.message}")
            secrets.forEach { runCatching { it.delete() } }
            null
        }
    }
}
