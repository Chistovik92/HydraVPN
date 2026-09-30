package ru.gidravpn.hydra.desktop

import org.json.JSONArray
import org.json.JSONObject
import ru.gidravpn.hydra.data.model.EngineToggles
import ru.gidravpn.hydra.data.model.GeoRoutingMode
import ru.gidravpn.hydra.data.model.HotspotSettings
import ru.gidravpn.hydra.data.model.MtuPreset
import ru.gidravpn.hydra.data.model.NetRuleType
import ru.gidravpn.hydra.data.model.NetworkRule
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.SplitTunnelMode
import ru.gidravpn.hydra.data.model.Subscription
import ru.gidravpn.hydra.data.model.TlsFragmentMode
import ru.gidravpn.hydra.desktop.core.Rules
import ru.gidravpn.hydra.router.RouterLink
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * PROXY — локальный mixed-прокси (HTTP+SOCKS5) и системный прокси ОС: без прав
 * администратора, но только для приложений, уважающих системный прокси.
 * TUN — виртуальный адаптер, весь трафик ОС (нужны права администратора/root).
 */
enum class ConnectionMode { PROXY, TUN }

/**
 * Всё, что решает, куда идёт трафик, — то, что сохраняет профиль маршрутизации
 * (как RoutingProfilesRepository на Android: DNS, geo, MTU, фрагментация, IPv6 и
 * раздельное туннелирование по приложениям и по IP/доменам).
 */
data class RoutingSettings(
    /** Строка DNS как в Android: IP/хост → DoH, либо https:// tls:// udp://; "system" — резолвер ОС. */
    val dns: String = "1.1.1.1",
    val geoMode: GeoRoutingMode = GeoRoutingMode.OFF,
    val geoCountries: List<String> = listOf("ru"),
    val tlsFragment: TlsFragmentMode = TlsFragmentMode.OFF,
    val mtu: MtuPreset = MtuPreset.AUTO,
    val ipv6: Boolean = false,
    /** По приложениям: имена процессов (chrome.exe, firefox) или полные пути к программам. */
    val appMode: SplitTunnelMode = SplitTunnelMode.OFF,
    val apps: List<String> = emptyList(),
    /** По сайтам и адресам — те же правила, что на Android (ip_cidr/domain/…). */
    val netMode: SplitTunnelMode = SplitTunnelMode.OFF,
    val netRules: List<NetworkRule> = emptyList(),
) {
    val appsActive get() = appMode != SplitTunnelMode.OFF && apps.isNotEmpty()
    val netActive get() = netMode != SplitTunnelMode.OFF && netRules.isNotEmpty()
}

data class RoutingProfile(val name: String, val routing: RoutingSettings)

data class DesktopSettings(
    val mode: ConnectionMode = ConnectionMode.PROXY,
    val proxyPort: Int = 2080,
    val setSystemProxy: Boolean = true,
    val selectedServerId: Long? = null,
    val routing: RoutingSettings = RoutingSettings(),
    // Движки (Настройки → Движки, как «Туннель» на Android).
    val singBoxEnabled: Boolean = true,
    val xrayEnabled: Boolean = true,
    val preferXray: Boolean = false,
    // Безопасность.
    val killSwitch: Boolean = false,
    val autoReconnect: Boolean = true,
    val autoConnect: Boolean = false,
    val launchAtLogin: Boolean = false,
    // Раздача VPN в локальную сеть (хотспот-прокси Android).
    val lanShare: HotspotSettings = HotspotSettings(),
    val checkUpdates: Boolean = true,
) {
    val engines: EngineToggles
        get() = EngineToggles(singBox = singBoxEnabled, xray = xrayEnabled, preferXray = preferXray)
}

data class HydraState(
    val servers: List<ServerProfile> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val settings: DesktopSettings = DesktopSettings(),
    val profiles: List<RoutingProfile> = emptyList(),
    /** Роутеры HydraVPN for Router (ссылка сопряжения: адрес, токен, TLS-отпечаток). */
    val routers: List<RouterLink> = emptyList(),
)

/**
 * Всё состояние — один JSON (как HydraStore на iOS). В нём пароли и UUID серверов,
 * поэтому файл доступен только владельцу. Запись атомарная (tmp + move) и
 * последовательная: сохраняют и UI-поток, и фоновые обновления подписок.
 */
class Store(private val file: File = File(Platform.dataDir, "hydra.json")) {

    private val lock = Any()

    fun load(): HydraState = synchronized(lock) {
        runCatching {
            if (!file.isFile) return HydraState()
            fromJson(JSONObject(file.readText()))
        }.getOrElse {
            // Битый файл не теряем молча — откладываем рядом и начинаем с пустого состояния.
            runCatching { file.copyTo(File(file.parentFile, file.name + ".broken"), overwrite = true) }
            HydraState()
        }
    }

    fun save(state: HydraState) = synchronized(lock) {
        writePrivateAtomic(file, toJson(state).toString(2))
    }

    companion object {
        const val VERSION = 2

        /** Файл только для владельца (0600), запись через временный файл и атомарную замену. */
        fun writePrivateAtomic(target: File, text: String) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.delete()
            if (Platform.os != Os.WINDOWS) {
                // Права ставятся при создании — без окна, когда файл с паролями читаем всем.
                Files.createFile(tmp.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            }
            tmp.writeText(text)
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        fun toJson(s: HydraState): JSONObject = JSONObject()
            .put("version", VERSION)
            .put("servers", JSONArray(s.servers.map(::serverToJson)))
            .put("subscriptions", JSONArray(s.subscriptions.map(::subToJson)))
            .put("settings", settingsToJson(s.settings))
            .put("profiles", JSONArray(s.profiles.map { JSONObject().put("name", it.name).put("routing", routingToJson(it.routing)) }))
            .put("routers", JSONArray(s.routers.map { JSONObject().put("name", it.name).put("link", it.toUri()) }))

        fun fromJson(o: JSONObject): HydraState = HydraState(
            servers = o.optJSONArray("servers").objects().map(::serverFromJson)
                .filter { it.address.isNotBlank() && it.port in 1..65535 }
                .distinctBy { it.id },
            subscriptions = o.optJSONArray("subscriptions").objects().map(::subFromJson).distinctBy { it.id },
            settings = o.optJSONObject("settings")?.let(::settingsFromJson) ?: DesktopSettings(),
            routers = o.optJSONArray("routers").objects().mapNotNull { r ->
                RouterLink.parse(r.optString("link"))?.let { l -> l.copy(name = r.optString("name").trim().take(40).ifEmpty { l.host }) }
            }.distinctBy { it.baseUrl },
            profiles = o.optJSONArray("profiles").objects().mapNotNull { p ->
                val name = p.optString("name").trim().take(40).ifEmpty { return@mapNotNull null }
                RoutingProfile(name, routingFromJson(p.optJSONObject("routing") ?: JSONObject()))
            }.distinctBy { it.name.lowercase() },
        )

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it, null) }

        fun serverToJson(p: ServerProfile): JSONObject = JSONObject()
            .put("id", p.id).put("name", p.name).put("protocolId", p.protocolId)
            .put("address", p.address).put("port", p.port).put("uuidOrPassword", p.uuidOrPassword)
            .put("flow", p.flow).put("sni", p.sni).put("transport", p.transport)
            .put("transportPath", p.transportPath).put("security", p.security).put("alpn", p.alpn)
            .put("fingerprint", p.fingerprint).put("extra", p.extra)
            .put("subscriptionId", p.subscriptionId ?: JSONObject.NULL)
            .put("pingMs", p.pingMs).put("flag", p.flag)

        fun serverFromJson(o: JSONObject): ServerProfile = ServerProfile(
            id = o.optLong("id"), name = o.optString("name"), protocolId = o.optString("protocolId"),
            address = o.optString("address"), port = o.optInt("port"),
            uuidOrPassword = o.optString("uuidOrPassword"), flow = o.optString("flow"),
            sni = o.optString("sni"), transport = o.optString("transport", "tcp"),
            transportPath = o.optString("transportPath"), security = o.optString("security", "none"),
            alpn = o.optString("alpn"), fingerprint = o.optString("fingerprint", "chrome"),
            extra = o.optString("extra", "{}"),
            subscriptionId = if (o.isNull("subscriptionId")) null else o.optLong("subscriptionId"),
            pingMs = o.optInt("pingMs", -1), flag = o.optString("flag", "🌐"),
        )

        private fun subToJson(s: Subscription): JSONObject = JSONObject()
            .put("id", s.id).put("name", s.name).put("url", s.url).put("lastUpdated", s.lastUpdated)
            .put("serverTitle", s.serverTitle).put("uploadBytes", s.uploadBytes)
            .put("downloadBytes", s.downloadBytes).put("totalBytes", s.totalBytes)
            .put("expireAt", s.expireAt).put("supportUrl", s.supportUrl).put("lastError", s.lastError)
            .put("autoUpdateHours", s.autoUpdateHours)

        private fun subFromJson(o: JSONObject): Subscription = Subscription(
            id = o.optLong("id"), name = o.optString("name"), url = o.optString("url"),
            lastUpdated = o.optLong("lastUpdated"), serverTitle = o.optString("serverTitle"),
            uploadBytes = o.optLong("uploadBytes"), downloadBytes = o.optLong("downloadBytes"),
            totalBytes = o.optLong("totalBytes"), expireAt = o.optLong("expireAt"),
            supportUrl = o.optString("supportUrl"), lastError = o.optString("lastError"),
            // 0 — не обновлять автоматически; иначе не чаще раза в час и не реже раза в неделю.
            autoUpdateHours = o.optInt("autoUpdateHours", 12).let { if (it <= 0) 0 else it.coerceIn(1, 168) },
        )

        fun routingToJson(r: RoutingSettings): JSONObject = JSONObject()
            .put("dns", r.dns).put("geoMode", r.geoMode.name).put("geoCountries", JSONArray(r.geoCountries))
            .put("tlsFragment", r.tlsFragment.name).put("mtu", r.mtu.name).put("ipv6", r.ipv6)
            .put("appMode", r.appMode.name).put("apps", JSONArray(r.apps))
            .put("netMode", r.netMode.name)
            .put("netRules", JSONArray(r.netRules.map { JSONObject().put("type", it.type.name).put("value", it.value) }))

        /** Значения из файла не доверенные (ручная правка, чужая резервная копия) — всё проверяется. */
        fun routingFromJson(o: JSONObject): RoutingSettings = RoutingSettings(
            dns = o.optString("dns", "1.1.1.1").trim().ifEmpty { "1.1.1.1" },
            geoMode = GeoRoutingMode.fromId(o.optString("geoMode")),
            geoCountries = o.optJSONArray("geoCountries")?.strings()?.let(Rules::countries) ?: listOf("ru"),
            tlsFragment = TlsFragmentMode.fromId(o.optString("tlsFragment")),
            mtu = MtuPreset.fromId(o.optString("mtu")),
            ipv6 = o.optBoolean("ipv6", false),
            appMode = splitMode(o.optString("appMode")),
            apps = Rules.apps(o.optJSONArray("apps").strings()),
            netMode = splitMode(o.optString("netMode")),
            netRules = o.optJSONArray("netRules").objects().mapNotNull { r ->
                val type = runCatching { NetRuleType.valueOf(r.optString("type")) }.getOrNull() ?: return@mapNotNull null
                Rules.netRule(type, r.optString("value"))
            }.distinct().take(Rules.MAX_RULES),
        )

        private fun splitMode(s: String) = runCatching { SplitTunnelMode.valueOf(s) }.getOrDefault(SplitTunnelMode.OFF)

        private fun settingsToJson(s: DesktopSettings): JSONObject = JSONObject()
            .put("mode", s.mode.name).put("proxyPort", s.proxyPort)
            .put("setSystemProxy", s.setSystemProxy)
            .put("selectedServerId", s.selectedServerId ?: JSONObject.NULL)
            .put("routing", routingToJson(s.routing))
            .put("singBoxEnabled", s.singBoxEnabled).put("xrayEnabled", s.xrayEnabled).put("preferXray", s.preferXray)
            .put("killSwitch", s.killSwitch).put("autoReconnect", s.autoReconnect)
            .put("autoConnect", s.autoConnect).put("launchAtLogin", s.launchAtLogin)
            .put("lanShare", JSONObject().put("enabled", s.lanShare.enabled).put("port", s.lanShare.port)
                .put("username", s.lanShare.username).put("password", s.lanShare.password))
            .put("checkUpdates", s.checkUpdates)

        private fun settingsFromJson(o: JSONObject): DesktopSettings {
            // Версия 1 хранила DNS/geo/фрагментацию прямо в settings — читаем их оттуда же.
            val routing = routingFromJson(o.optJSONObject("routing") ?: o)
            val lan = o.optJSONObject("lanShare") ?: JSONObject()
            return DesktopSettings(
                mode = runCatching { ConnectionMode.valueOf(o.optString("mode")) }.getOrDefault(ConnectionMode.PROXY),
                proxyPort = o.optInt("proxyPort", 2080).takeIf { it in 1024..65535 } ?: 2080,
                setSystemProxy = o.optBoolean("setSystemProxy", true),
                selectedServerId = if (o.isNull("selectedServerId")) null else o.optLong("selectedServerId"),
                routing = routing,
                singBoxEnabled = o.optBoolean("singBoxEnabled", true),
                xrayEnabled = o.optBoolean("xrayEnabled", true),
                preferXray = o.optBoolean("preferXray", false),
                killSwitch = o.optBoolean("killSwitch", false),
                autoReconnect = o.optBoolean("autoReconnect", true),
                autoConnect = o.optBoolean("autoConnect", false),
                launchAtLogin = o.optBoolean("launchAtLogin", false),
                lanShare = HotspotSettings(
                    enabled = lan.optBoolean("enabled", false),
                    port = lan.optInt("port", 2081).takeIf { it in HotspotSettings.PORT_RANGE } ?: 2081,
                    username = lan.optString("username", HotspotSettings.DEFAULT_USER).take(64),
                    password = lan.optString("password", "").take(128),
                ),
                checkUpdates = o.optBoolean("checkUpdates", true),
            )
        }
    }
}
