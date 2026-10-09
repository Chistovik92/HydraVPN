package ru.gidravpn.hydra.desktop.core

import org.json.JSONArray
import org.json.JSONObject
import ru.gidravpn.hydra.data.model.DnsEndpoint
import ru.gidravpn.hydra.data.geo.GeoKind
import ru.gidravpn.hydra.data.geo.GeoStore
import ru.gidravpn.hydra.data.model.Engine
import ru.gidravpn.hydra.data.routing.RouteKind
import ru.gidravpn.hydra.data.routing.RoutePlan
import ru.gidravpn.hydra.data.routing.RoutePlanApplier
import ru.gidravpn.hydra.data.model.EngineToggles
import ru.gidravpn.hydra.data.model.GeoRoutingMode
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.SplitTunnel
import ru.gidravpn.hydra.data.model.SplitTunnelMode
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder
import ru.gidravpn.hydra.data.subscription.XrayConfigBuilder
import ru.gidravpn.hydra.desktop.ConnectionMode
import ru.gidravpn.hydra.desktop.DesktopSettings
import ru.gidravpn.hydra.desktop.Os
import java.io.File

/**
 * Конфиги ядер для ПК. Основа — тот же [SingBoxConfigBuilder], что в Android
 * (outbound, DNS, sniff/hijack-dns, split/geo-правила), платформенная часть своя:
 *  - Android отдаёт tun через VpnService — на ПК маршруты ставит сам sing-box:
 *    tun с auto_route + strict_route, либо mixed-inbound для системного прокси;
 *  - раздельное туннелирование по приложениям — правила process_name/process_path
 *    sing-box (на Android это делает VpnService по пакетам приложений);
 *  - движок Xray — как на Android: Xray headless с socks-inbound, sing-box — мост
 *    (tun/прокси, DNS, все правила) с единственным outbound socks на Xray;
 *  - clash_api слушает 127.0.0.1 с секретом — оттуда UI берёт скорость;
 *  - WireGuard — endpoint (схема 1.12), а не устаревший outbound.
 */
object DesktopConfig {

    class UnsupportedProtocol(val profile: ServerProfile) :
        IllegalArgumentException("Протокол «${profile.protocol?.displayName ?: profile.protocolId}» на ПК пока не поддерживается")

    /** На ПК работают протоколы ядер sing-box и Xray (Xray обслуживает их подмножество), а также olcRTC и OpenFlux (0.7.4, BETA). */
    fun isSupported(p: ServerProfile): Boolean = p.protocol?.engine in SUPPORTED_ENGINES

    private val SUPPORTED_ENGINES = setOf(Engine.SINGBOX, Engine.OLCRTC, Engine.OPENFLUX, Engine.BYEDPI)

    /** Кто обслужит профиль с текущими тумблерами движков; null — подходящий движок выключен. */
    fun engineFor(p: ServerProfile, settings: DesktopSettings, xrayAvailable: Boolean): EngineToggles.Kind? =
        if (!isSupported(p)) null else settings.engines.engineFor(p.protocol, xrayAvailable)

    data class Api(val port: Int, val secret: String)

    /** Локальный socks-inbound Xray, к которому подключается мост sing-box. */
    data class XrayBridge(val port: Int, val auth: XrayConfigBuilder.SocksAuth?)

    /** Теги локальных inbound'ов — правила «по приложениям» касаются только их, не раздачи в LAN. */
    private const val TUN_TAG = "tun-in"
    private const val MIXED_TAG = "mixed-in"
    private const val LAN_TAG = "lan-in"

    /**
     * Конфиг sing-box.
     * @param bridge не null — движок Xray: sing-box становится мостом к его socks-inbound.
     * @param bypassPaths программы, чей трафик в TUN всегда идёт мимо туннеля: сам Hydra
     *   (как Android исключает своё приложение) и процесс Xray — иначе его соединение с
     *   сервером вернулось бы в tun и зациклилось.
     */
    fun build(
        profile: ServerProfile,
        settings: DesktopSettings,
        os: Os,
        api: Api,
        geoDir: File?,
        bridge: XrayBridge? = null,
        bypassPaths: List<String> = emptyList(),
        plan: RoutePlan? = null,
        geoStore: GeoStore? = null,
        onWarn: (String) -> Unit = {},
    ): JSONObject {
        if (!isSupported(profile)) throw UnsupportedProtocol(profile)
        val r = settings.routing

        val split = SplitTunnel(netMode = r.netMode, netRules = r.netRules)
        val root = if (bridge != null) {
            SingBoxConfigBuilder.buildXrayBridge(
                socksPort = bridge.port, splitTunnel = split, dns = dnsEndpoint(settings),
                geoRouting = geoRouting(settings, geoDir, geoStore), mtu = r.mtu.value, socksAuth = bridge.auth,
            )
        } else {
            SingBoxConfigBuilder.build(
                profile = profile, splitTunnel = split, dns = dnsEndpoint(settings),
                geoRouting = geoRouting(settings, geoDir, geoStore), mtu = r.mtu.value, tlsFragment = r.tlsFragment,
            )
        }

        root.put("log", JSONObject().put("level", "info").put("timestamp", true))
        root.put("experimental", JSONObject().put("clash_api", JSONObject()
            .put("external_controller", "127.0.0.1:${api.port}")
            .put("secret", api.secret)))

        root.put("inbounds", JSONArray().apply {
            put(when (settings.mode) {
                ConnectionMode.TUN -> tunInbound(os, settings)
                ConnectionMode.PROXY -> JSONObject()
                    .put("type", "mixed").put("tag", MIXED_TAG)
                    .put("listen", "127.0.0.1").put("listen_port", settings.proxyPort)
            })
            // Раздача VPN в локальную сеть — только с логином и паролем (открытый прокси в
            // чужой сети — открытый выход в интернет с вашего адреса).
            val lan = settings.lanShare
            if (lan.isUsable && !(settings.mode == ConnectionMode.PROXY && lan.port == settings.proxyPort)) {
                put(JSONObject().put("type", "mixed").put("tag", LAN_TAG)
                    .put("listen", "0.0.0.0").put("listen_port", lan.port)
                    .put("users", JSONArray().put(JSONObject().put("username", lan.username).put("password", lan.password))))
            }
        })

        if (settings.mode == ConnectionMode.TUN && !r.ipv6) {
            // IPv6-адрес у tun нужен, чтобы IPv6 не утекал мимо туннеля, но тогда ОС
            // предпочитает AAAA — а у большинства серверов IPv6 наружу нет: стек tun
            // принимает соединение и тут же рвёт его, и приложение не откатывается на IPv4
            // (поймано e2e на Windows). Как в Android по умолчанию (IPv6 блокируется):
            // имена резолвятся только в IPv4.
            root.getJSONObject("dns").put("strategy", "ipv4_only")
        }

        // Маршрутизация через несколько выходов, цепочки, группы, обход DPI (0.7.13) — до правил по процессам:
        // правила «программа мимо туннеля» остаются самыми первыми, как на Android «приложение вне VPN».
        if (plan != null) RoutePlanApplier.apply(root, plan) { kind, name ->
            geoFile(if (kind == RouteKind.GEOSITE) GeoKind.SITE else GeoKind.IP, name, geoDir, geoStore)?.absolutePath
        }.forEach(onWarn)
        addProcessRules(root, settings, bypassPaths)
        convertWireGuard(root)
        return root
    }

    /**
     * Конфиг Xray для движка Xray. [resolvedIp] — адрес сервера, заранее разрешённый
     * Hydra (режим TUN): иначе Xray спросил бы DNS у ОС, запрос ушёл бы в tun, а оттуда —
     * через этот же ещё не подключённый Xray. Имя сервера при этом остаётся в SNI.
     */
    fun xray(profile: ServerProfile, settings: DesktopSettings, bridge: XrayBridge, resolvedIp: String? = null): JSONObject {
        val p = if (resolvedIp == null || resolvedIp == profile.address) profile
        else profile.copy(sni = profile.sni.ifBlank { profile.address }, address = resolvedIp)
        val dnsUrl = dnsEndpoint(settings)?.toXrayAddress()
        return XrayConfigBuilder.build(p, bridge.port, dnsUrl, bridge.auth ?: error("для Xray нужен пароль socks-моста"))
    }

    private fun dnsEndpoint(settings: DesktopSettings): DnsEndpoint? {
        val dns = settings.routing.dns
        return if (dns.equals("system", ignoreCase = true)) null
        else DnsEndpoint.parse(dns) ?: DnsEndpoint.doh("1.1.1.1")
    }

    private fun tunInbound(os: Os, settings: DesktopSettings): JSONObject = JSONObject().apply {
        put("type", "tun")
        put("tag", TUN_TAG)
        // macOS разрешает только utunN — имя выбирает система.
        when (os) {
            Os.WINDOWS -> put("interface_name", "Hydra")
            Os.LINUX -> put("interface_name", "hydra0")
            Os.MACOS -> Unit
        }
        put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
        put("mtu", settings.routing.mtu.value)
        put("auto_route", true)
        // strict_route: на Windows закрывает утечку DNS через другие адаптеры,
        // на Linux — трафик в обход tun при смене маршрутов.
        put("strict_route", true)
        put("stack", "mixed")
    }

    /**
     * Правила по процессам — сразу после sniff и hijack-dns, до правил по адресам и geo:
     * на Android приложение, исключённое из VPN, не видит туннель вовсе, и здесь так же
     * решение «по приложению» сильнее всех остальных.
     *  - EXCLUDE: трафик выбранных программ → direct;
     *  - INCLUDE: трафик всех ОСТАЛЬНЫХ программ локальных inbound'ов → direct, а выбранные
     *    идут дальше по обычным правилам. Раздачу в LAN (у неё нет «программы») не трогаем.
     */
    private fun addProcessRules(root: JSONObject, settings: DesktopSettings, bypassPaths: List<String>) {
        val r = settings.routing
        val front = mutableListOf<JSONObject>()
        val bypass = if (settings.mode == ConnectionMode.TUN) bypassPaths.filter { it.isNotBlank() }.distinct() else emptyList()
        if (bypass.isNotEmpty()) {
            front += JSONObject().put("process_path", JSONArray(bypass)).put("outbound", "direct")
            // 0.7.10: DNS самих клиентов (Xray, olcRTC, OpenFlux) — тоже мимо туннеля. В TUN их запрос к DNS ОС
            // перехватывается hijack-dns и уходил в DNS «remote» через прокси — то есть через этот же клиент: пока
            // он переподключается к транспорту, имя транспорта не резолвится, и переподключение тянулось до таймаута.
            val dns = root.getJSONObject("dns")
            val dnsRules = dns.optJSONArray("rules") ?: JSONArray()
            dns.put("rules", JSONArray().put(JSONObject().put("process_path", JSONArray(bypass)).put("server", "local"))
                .apply { for (i in 0 until dnsRules.length()) put(dnsRules.get(i)) })
        }
        if (r.appsActive) {
            val match = processMatch(r.apps)
            val localInbound = if (settings.mode == ConnectionMode.TUN) TUN_TAG else MIXED_TAG
            front += when (r.appMode) {
                SplitTunnelMode.EXCLUDE -> JSONObject()
                    .put("type", "logical").put("mode", "and")
                    .put("rules", JSONArray().put(JSONObject().put("inbound", JSONArray().put(localInbound))).put(match))
                    .put("outbound", "direct")
                else -> JSONObject()
                    .put("type", "logical").put("mode", "and")
                    .put("rules", JSONArray()
                        .put(JSONObject().put("inbound", JSONArray().put(localInbound)))
                        .put(match.put("invert", true)))
                    .put("outbound", "direct")
            }
        }
        if (front.isEmpty()) return

        val route = root.getJSONObject("route")
        val old = route.getJSONArray("rules")
        // Первые два правила общего билдера — sniff и hijack-dns: без sniff у соединения
        // нет домена, а DNS приложений должен перехватываться при любом процессе.
        val head = (0 until old.length()).map { old.getJSONObject(it) }
        val keep = head.takeWhile { it.optString("action") == "sniff" || it.optString("action") == "hijack-dns" }
        route.put("rules", JSONArray(keep + front + head.drop(keep.size)))
        route.put("find_process", true)
    }

    /** Имена → process_name, пути → process_path; оба вида — через логическое «или». */
    private fun processMatch(apps: List<String>): JSONObject {
        val (paths, names) = apps.partition(Rules::isPath)
        val parts = buildList {
            if (names.isNotEmpty()) add(JSONObject().put("process_name", JSONArray(names)))
            if (paths.isNotEmpty()) add(JSONObject().put("process_path", JSONArray(paths)))
        }
        return if (parts.size == 1) parts[0]
        else JSONObject().put("type", "logical").put("mode", "or").put("rules", JSONArray(parts))
    }

    /** База: сначала скачанная ([GeoStore]), потом вшитая в пакет. Доменные наборы стран в апстримах зовутся `category-<код>`, вшитые — по коду. */
    fun geoFile(kind: GeoKind, name: String, geoDir: File?, store: GeoStore?): File? =
        store?.resolve(kind, name) ?: geoDir?.let { File(it, "${kind.dir}/$name.srs") }?.takeIf { it.isFile }

    private fun geoRouting(settings: DesktopSettings, geoDir: File?, store: GeoStore?): SingBoxConfigBuilder.GeoRouting? {
        val r = settings.routing
        if (r.geoMode == GeoRoutingMode.OFF || (geoDir == null && store == null)) return null
        val countries = Rules.countries(r.geoCountries).mapNotNull { code ->
            val ip = geoFile(GeoKind.IP, code, geoDir, store) ?: return@mapNotNull null
            val site = geoFile(GeoKind.SITE, code, geoDir, store)
            SingBoxConfigBuilder.GeoCountry(code, ip.absolutePath, site?.absolutePath)
        }
        return SingBoxConfigBuilder.GeoRouting(r.geoMode, countries).takeIf { it.active }
    }

    /** outbound type=wireguard (удалён в sing-box 1.13) → endpoint wireguard. */
    private fun convertWireGuard(root: JSONObject) {
        val outbounds = root.getJSONArray("outbounds")
        val idx = (0 until outbounds.length()).firstOrNull {
            outbounds.getJSONObject(it).optString("type") == "wireguard"
        } ?: return
        val wg = outbounds.getJSONObject(idx)
        outbounds.remove(idx)
        val peer = JSONObject()
            .put("address", wg.getString("server"))
            .put("port", wg.getInt("server_port"))
            .put("public_key", wg.optString("peer_public_key"))
            .put("allowed_ips", JSONArray().put("0.0.0.0/0").put("::/0"))
        wg.optString("pre_shared_key").takeIf { it.isNotEmpty() }?.let { peer.put("pre_shared_key", it) }
        val endpoint = JSONObject()
            .put("type", "wireguard").put("tag", wg.optString("tag", "proxy"))
            .put("address", JSONArray(localAddresses(wg.optJSONArray("local_address"))))
            .put("private_key", wg.optString("private_key"))
            .put("peers", JSONArray().put(peer))
        if (wg.has("mtu")) endpoint.put("mtu", wg.getInt("mtu"))
        root.put("endpoints", JSONArray().put(endpoint))
    }

    /** sing-box требует префикс: "10.0.0.2" → "10.0.0.2/32", IPv6 → /128. */
    private fun localAddresses(raw: JSONArray?): List<String> {
        val list = raw?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }.orEmpty()
            .filter { it.isNotEmpty() }
            .map { if ('/' in it) it else if (':' in it) "$it/128" else "$it/32" }
        return list.ifEmpty { listOf("172.19.0.2/32") }
    }
}
