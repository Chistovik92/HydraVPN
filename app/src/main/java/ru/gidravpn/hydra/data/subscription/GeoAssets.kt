package ru.gidravpn.hydra.data.subscription

import android.content.Context
import java.io.File

/**
 * Bundled `.srs`-базы по странам (sing-box rule-set binary, precompiled из
 * MetaCubeX/meta-rules-dat, ветка `sing`):
 *  - `assets/geoip/<cc>.srs` — IP-диапазоны, ~250 стран, ~3.8 МБ суммарно;
 *  - `assets/geosite/<cc>.srs` — домены, есть только для немногих стран
 *    (ru, cn, ir, pt, tm) — у остальных маршрутизация только по IP.
 * sing-box (`route.rule_set`, `type: local`) читает их по обычному пути,
 * поэтому нужные файлы копируются в filesDir.
 *
 * Почему не geoip.dat/geosite.dat (v2fly/Xray) — см. CHANGELOG 0.6.9:
 * маршрутизацией владеет sing-box, а `route.geoip`/`.dat` он удалил в 1.12.
 */
object GeoAssets {

    /** Коды стран, для которых есть IP-база. */
    fun availableCountries(context: Context): List<String> =
        context.assets.list("geoip").orEmpty().map { it.removeSuffix(".srs") }.sorted()

    /** Коды стран, для которых есть ещё и доменная база. */
    fun countriesWithDomains(context: Context): Set<String> =
        context.assets.list("geosite").orEmpty().map { it.removeSuffix(".srs") }.toSet()

    /**
     * Пути к базам выбранных стран; неизвестные коды пропускаются (например
     * выбор, сохранённый в версии с другим набором стран).
     */
    fun resolve(context: Context, countries: Set<String>): List<SingBoxConfigBuilder.GeoCountry> {
        val withIp = availableCountries(context).toSet()
        val withDomains = countriesWithDomains(context)
        return countries.filter { it in withIp }.sorted().map { cc ->
            SingBoxConfigBuilder.GeoCountry(
                code = cc,
                geoipPath = extract(context, "geoip", cc),
                geositePath = if (cc in withDomains) extract(context, "geosite", cc) else null,
            )
        }
    }

    private fun extract(context: Context, dir: String, cc: String): String {
        val file = File(context.filesDir, "$dir/$cc.srs").apply { parentFile?.mkdirs() }
        // lastUpdateTime растёт при каждом обновлении APK — иначе свежая база из
        // нового релиза никогда не заменила бы копию, извлечённую старым.
        val apkUpdated = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(Long.MAX_VALUE)
        if (!file.exists() || file.length() == 0L || file.lastModified() < apkUpdated) {
            context.assets.open("$dir/$cc.srs").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file.absolutePath
    }
}
