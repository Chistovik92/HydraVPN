package ru.gidravpn.hydra.desktop

import org.json.JSONArray
import org.json.JSONObject
import ru.gidravpn.hydra.data.model.GeoRoutingMode
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.Subscription
import ru.gidravpn.hydra.data.model.TlsFragmentMode
import java.io.File

/**
 * PROXY — локальный mixed-прокси (HTTP+SOCKS5) и системный прокси ОС: без прав
 * администратора, но только для приложений, уважающих системный прокси.
 * TUN — виртуальный адаптер, весь трафик ОС (нужны права администратора/root).
 */
enum class ConnectionMode { PROXY, TUN }

data class DesktopSettings(
    val mode: ConnectionMode = ConnectionMode.PROXY,
    /** Строка DNS как в Android: IP/хост → DoH, либо https:// tls:// udp://; "system" — резолвер ОС. */
    val dns: String = "1.1.1.1",
    val geoMode: GeoRoutingMode = GeoRoutingMode.OFF,
    val geoCountries: List<String> = listOf("ru"),
    val proxyPort: Int = 2080,
    val setSystemProxy: Boolean = true,
    val tlsFragment: TlsFragmentMode = TlsFragmentMode.OFF,
    val selectedServerId: Long? = null,
)

data class HydraState(
    val servers: List<ServerProfile> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val settings: DesktopSettings = DesktopSettings(),
)

/** Всё состояние — один JSON (как HydraStore на iOS). Запись атомарная: tmp + rename. */
class Store(private val file: File = File(Platform.dataDir, "hydra.json")) {

    fun load(): HydraState = runCatching {
        if (!file.isFile) return HydraState()
        fromJson(JSONObject(file.readText()))
    }.getOrElse {
        // Битый файл не теряем молча — откладываем рядом и начинаем с пустого состояния.
        runCatching { file.copyTo(File(file.parentFile, file.name + ".broken"), overwrite = true) }
        HydraState()
    }

    fun save(state: HydraState) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(toJson(state).toString(2))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        fun toJson(s: HydraState): JSONObject = JSONObject()
            .put("version", 1)
            .put("servers", JSONArray(s.servers.map(::serverToJson)))
            .put("subscriptions", JSONArray(s.subscriptions.map(::subToJson)))
            .put("settings", settingsToJson(s.settings))

        fun fromJson(o: JSONObject): HydraState = HydraState(
            servers = o.optJSONArray("servers").objects().map(::serverFromJson),
            subscriptions = o.optJSONArray("subscriptions").objects().map(::subFromJson),
            settings = o.optJSONObject("settings")?.let(::settingsFromJson) ?: DesktopSettings(),
        )

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

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
            autoUpdateHours = o.optInt("autoUpdateHours", 12),
        )

        private fun settingsToJson(s: DesktopSettings): JSONObject = JSONObject()
            .put("mode", s.mode.name).put("dns", s.dns).put("geoMode", s.geoMode.name)
            .put("geoCountries", JSONArray(s.geoCountries)).put("proxyPort", s.proxyPort)
            .put("setSystemProxy", s.setSystemProxy).put("tlsFragment", s.tlsFragment.name)
            .put("selectedServerId", s.selectedServerId ?: JSONObject.NULL)

        private fun settingsFromJson(o: JSONObject): DesktopSettings = DesktopSettings(
            mode = runCatching { ConnectionMode.valueOf(o.optString("mode")) }.getOrDefault(ConnectionMode.PROXY),
            dns = o.optString("dns", "1.1.1.1"),
            geoMode = GeoRoutingMode.fromId(o.optString("geoMode")),
            geoCountries = o.optJSONArray("geoCountries")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?: listOf("ru"),
            proxyPort = o.optInt("proxyPort", 2080).takeIf { it in 1024..65535 } ?: 2080,
            setSystemProxy = o.optBoolean("setSystemProxy", true),
            tlsFragment = TlsFragmentMode.fromId(o.optString("tlsFragment")),
            selectedServerId = if (o.isNull("selectedServerId")) null else o.optLong("selectedServerId"),
        )
    }
}
