package ru.gidravpn.hydra.data.subscription

import android.content.Context
import java.io.File

/**
 * Копирует bundled `.srs`-базы (geoip-ru/geosite-ru, sing-box rule-set
 * binary — SagerNet-совместимый формат, собран из MetaCubeX/meta-rules-dat,
 * ветка `sing`) из assets в filesDir: sing-box (`route.rule_set`, `type: local`)
 * читает их по обычному файловому пути, а не из APK-assets напрямую.
 *
 * Почему именно эти файлы, а не geoip.dat/geosite.dat (v2fly/Xray-формат,
 * см. память "xray-geoip-routing-idea"): маршрутизацией и TUN владеет
 * sing-box даже в режиме Xray-моста (SingBoxConfigBuilder.buildXrayBridge) —
 * `route.geoip`/`.dat` в sing-box 1.8 объявлены deprecated и в 1.12 полностью
 * удалены в пользу `route.rule_set` на `.srs`. v2fly/geoip и
 * runetfreedom/russia-blocked-geoip публикуют именно старый `.dat`-формат,
 * поэтому тут используется sing-box-совместимая альтернатива.
 */
object GeoAssets {
    private const val GEOIP_ASSET = "geoip-ru.srs"
    private const val GEOSITE_ASSET = "geosite-ru.srs"

    fun geoipRuPath(context: Context): String = extract(context, GEOIP_ASSET)
    fun geositeRuPath(context: Context): String = extract(context, GEOSITE_ASSET)

    private fun extract(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        // lastUpdateTime растёт при каждом обновлении APK — иначе свежая база из
        // нового релиза никогда не заменила бы копию, извлечённую старым.
        val apkUpdated = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(Long.MAX_VALUE)
        if (!file.exists() || file.length() == 0L || file.lastModified() < apkUpdated) {
            context.assets.open(assetName).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file.absolutePath
    }
}
