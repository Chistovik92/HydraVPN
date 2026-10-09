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

    /** Скачанные базы (0.7.13): имеют приоритет над вшитыми. */
    fun store(context: Context) = ru.gidravpn.hydra.data.geo.GeoStore(File(context.filesDir, "geo-dynamic"))

    /** Коды стран, для которых есть IP-база. */
    fun availableCountries(context: Context): List<String> =
        (context.assets.list("geoip").orEmpty().map { it.removeSuffix(".srs") } +
            store(context).entries().filter { it.kind == ru.gidravpn.hydra.data.geo.GeoKind.IP && it.name.length == 2 }.map { it.name }).distinct().sorted()

    /** Коды стран, для которых есть ещё и доменная база. */
    fun countriesWithDomains(context: Context): Set<String> =
        (context.assets.list("geosite").orEmpty().map { it.removeSuffix(".srs") } +
            store(context).entries().filter { it.kind == ru.gidravpn.hydra.data.geo.GeoKind.SITE && it.name.length == 2 }.map { it.name }).toSet()

    /**
     * Пути к базам выбранных стран; неизвестные коды пропускаются (например
     * выбор, сохранённый в версии с другим набором стран).
     */
    fun resolve(context: Context, countries: Set<String>): List<SingBoxConfigBuilder.GeoCountry> {
        val withIp = availableCountries(context).toSet()
        val withDomains = countriesWithDomains(context)
        return countries.filter { it in withIp }.sorted().mapNotNull { cc ->
            // Скачанная база могла оказаться повреждённой, а вшитой для страны нет — такую страну пропускаем, а не падаем.
            val ip = pathFor(context, false, cc) ?: return@mapNotNull null
            SingBoxConfigBuilder.GeoCountry(
                code = cc,
                geoipPath = ip,
                geositePath = if (cc in withDomains) pathFor(context, true, cc) else null,
            )
        }
    }

    /** Путь к одной базе (правила «страна → выход» из 0.7.12); null — такой базы нет. */
    fun pathFor(context: Context, domains: Boolean, cc: String): String? {
        val kind = if (domains) ru.gidravpn.hydra.data.geo.GeoKind.SITE else ru.gidravpn.hydra.data.geo.GeoKind.IP
        store(context).resolve(kind, cc)?.let { return it.absolutePath }
        val bundled = context.assets.list(kind.dir).orEmpty().contains("$cc.srs")
        return if (bundled) extract(context, kind.dir, cc) else null
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
