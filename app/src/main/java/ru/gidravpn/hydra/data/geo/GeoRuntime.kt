package ru.gidravpn.hydra.data.geo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import ru.gidravpn.hydra.data.model.GeoRoutingMode
import ru.gidravpn.hydra.data.repository.RoutingRepository
import ru.gidravpn.hydra.data.routing.RouteConfig
import ru.gidravpn.hydra.data.routing.RouteKind
import ru.gidravpn.hydra.data.subscription.GeoAssets
import ru.gidravpn.hydra.vpn.VpnState

/** Обновление geo-баз на Android: что нужно, откуда качать, как сообщить. Вызывается по расписанию и по кнопке. */
object GeoRuntime {

    /** Какие базы сейчас нужны: страны режима, правила «страна → выход», дополнительные наборы, свои источники. */
    fun wanted(ctx: Context, geoMode: GeoRoutingMode, countries: Set<String>, routes: RouteConfig, s: GeoSettings): List<Pair<GeoKind, String>> {
        val out = linkedSetOf<Pair<GeoKind, String>>()
        val withSite = GeoAssets.countriesWithDomains(ctx)
        if (geoMode != GeoRoutingMode.OFF) countries.forEach { cc ->
            out += GeoKind.IP to cc
            if (cc in withSite) out += GeoKind.SITE to cc
        }
        routes.rules.forEach {
            when (it.kind) {
                RouteKind.GEOIP -> out += GeoKind.IP to it.value.trim().lowercase()
                RouteKind.GEOSITE -> out += GeoKind.SITE to it.value.trim().lowercase()
                else -> Unit
            }
        }
        s.extraIp.forEach { out += GeoKind.IP to it }
        s.extraSite.forEach { out += GeoKind.SITE to it }
        s.custom.forEach { out += it.kind to it.name }
        return out.filter { it.second.isNotBlank() }
    }

    /** @param onlyIfDue true — только если прошёл интервал с последней проверки (WorkManager); false — по кнопке. */
    suspend fun update(ctx: Context, onlyIfDue: Boolean, proxyPort: Int = 0): GeoUpdater.Report? = withContext(Dispatchers.IO) {
        val repo = RoutingRepository(ctx)
        val s = repo.geoSettings.firstOrNull() ?: GeoSettings()
        val store = GeoAssets.store(ctx)
        if (onlyIfDue && (!s.autoUpdate || System.currentTimeMillis() - store.lastChecked() < s.intervalHours * 3_600_000L)) return@withContext null
        val list = wanted(ctx, repo.geoRoutingMode.firstOrNull() ?: GeoRoutingMode.OFF, repo.geoCountries.firstOrNull() ?: setOf("ru"),
            repo.routeConfig.firstOrNull() ?: RouteConfig(), s)
        if (list.isEmpty()) return@withContext null
        val report = GeoUpdater(store, JvmGeoHttp(proxyPort)).update(s, list) { VpnState.log(it) }
        VpnState.log("Geo-базы: обновлено ${report.installed}, без изменений ${report.unchanged}, ошибок ${report.failed}")
        report
    }
}
