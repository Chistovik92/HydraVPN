package ru.gidravpn.hydra.desktop.core

import org.json.JSONArray
import org.json.JSONObject
import ru.gidravpn.hydra.data.model.DnsEndpoint
import ru.gidravpn.hydra.data.model.Engine
import ru.gidravpn.hydra.data.model.GeoRoutingMode
import ru.gidravpn.hydra.data.model.MtuPreset
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder
import ru.gidravpn.hydra.desktop.ConnectionMode
import ru.gidravpn.hydra.desktop.DesktopSettings
import ru.gidravpn.hydra.desktop.Os
import java.io.File

/**
 * Конфиг sing-box для ПК. Основа — тот же [SingBoxConfigBuilder], что в Android
 * (outbound, DNS, sniff/hijack-dns, split/geo-правила), а платформенная часть
 * заменяется:
 *  - Android отдаёт tun через VpnService (auto_route выключен) — на ПК маршруты
 *    ставит сам sing-box: tun с auto_route + strict_route, либо mixed-inbound;
 *  - clash_api слушает 127.0.0.1 с секретом — оттуда UI берёт скорость;
 *  - WireGuard — endpoint (схема 1.12), а не устаревший outbound.
 */
object DesktopConfig {

    class UnsupportedProtocol(val profile: ServerProfile) :
        IllegalArgumentException("Протокол «${profile.protocol?.displayName ?: profile.protocolId}» на ПК пока не поддерживается")

    /** На ПК работает только то, что обслуживает само ядро sing-box. */
    fun isSupported(p: ServerProfile): Boolean = p.protocol?.engine == Engine.SINGBOX

    data class Api(val port: Int, val secret: String)

    fun build(
        profile: ServerProfile,
        settings: DesktopSettings,
        os: Os,
        api: Api,
        geoDir: File?,
    ): JSONObject {
        if (!isSupported(profile)) throw UnsupportedProtocol(profile)

        val dns: DnsEndpoint? = if (settings.dns.equals("system", ignoreCase = true)) null
        else DnsEndpoint.parse(settings.dns) ?: DnsEndpoint.doh("1.1.1.1")

        val geo = geoRouting(settings, geoDir)
        val root = SingBoxConfigBuilder.build(
            profile = profile,
            dns = dns,
            geoRouting = geo,
            mtu = MtuPreset.AUTO.value,
            tlsFragment = settings.tlsFragment,
        )

        root.put("log", JSONObject().put("level", "info").put("timestamp", true))
        root.put("experimental", JSONObject().put("clash_api", JSONObject()
            .put("external_controller", "127.0.0.1:${api.port}")
            .put("secret", api.secret)))

        root.put("inbounds", JSONArray().put(
            when (settings.mode) {
                ConnectionMode.TUN -> tunInbound(os)
                ConnectionMode.PROXY -> JSONObject()
                    .put("type", "mixed").put("tag", "mixed-in")
                    .put("listen", "127.0.0.1").put("listen_port", settings.proxyPort)
            }
        ))

        if (settings.mode == ConnectionMode.TUN) {
            // IPv6-адрес у tun нужен, чтобы IPv6 не утекал мимо туннеля, но тогда ОС
            // предпочитает AAAA — а у большинства серверов IPv6 наружу нет: стек tun
            // принимает соединение и тут же рвёт его, и приложение не откатывается на IPv4
            // (поймано e2e на Windows). Как в Android по умолчанию (Ipv6Mode выключен):
            // имена резолвятся только в IPv4.
            root.getJSONObject("dns").put("strategy", "ipv4_only")
        }

        convertWireGuard(root)
        return root
    }

    private fun tunInbound(os: Os): JSONObject = JSONObject().apply {
        put("type", "tun")
        put("tag", "tun-in")
        // macOS разрешает только utunN — имя выбирает система.
        when (os) {
            Os.WINDOWS -> put("interface_name", "Hydra")
            Os.LINUX -> put("interface_name", "hydra0")
            Os.MACOS -> Unit
        }
        put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
        put("mtu", 9000)
        put("auto_route", true)
        // strict_route: на Windows закрывает утечку DNS через другие адаптеры,
        // на Linux — трафик в обход tun при смене маршрутов.
        put("strict_route", true)
        put("stack", "mixed")
    }

    private fun geoRouting(settings: DesktopSettings, geoDir: File?): SingBoxConfigBuilder.GeoRouting? {
        if (settings.geoMode == GeoRoutingMode.OFF || geoDir == null) return null
        val countries = settings.geoCountries.mapNotNull { code ->
            val ip = File(geoDir, "geoip/$code.srs").takeIf { it.isFile } ?: return@mapNotNull null
            val site = File(geoDir, "geosite/$code.srs").takeIf { it.isFile }
            SingBoxConfigBuilder.GeoCountry(code, ip.absolutePath, site?.absolutePath)
        }
        return SingBoxConfigBuilder.GeoRouting(settings.geoMode, countries).takeIf { it.active }
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
