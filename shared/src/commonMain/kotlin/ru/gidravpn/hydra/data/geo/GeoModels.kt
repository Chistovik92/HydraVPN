package ru.gidravpn.hydra.data.geo

import org.json.JSONArray
import org.json.JSONObject

/**
 * Geo-базы как динамический слой (0.7.13). Раньше `.srs` для sing-box (`route.rule_set`) были вшиты в сборку и
 * менялись только с релизом; теперь приложение само скачивает их из выбранных источников, проверяет, заменяет
 * атомарно и умеет откатиться. Вшитые базы остаются запасными — на первый запуск и офлайн.
 */
enum class GeoKind(val dir: String) {
    IP("geoip"),      // IP-диапазоны страны или набора: имя — код (`ru`) или имя набора (`ru-blocked`)
    SITE("geosite");  // домены набора: `category-ru`, `youtube`, `ru-blocked`…

    companion object { fun fromDir(d: String) = entries.firstOrNull { it.dir == d } }
}

/** Как получить базу из источника. */
enum class GeoFormat {
    SRS,   // готовый rule-set sing-box (бинарный)
    DAT,   // geoip.dat / geosite.dat (v2fly, Xray): нужные записи конвертируются в rule-set «source» на устройстве
}

/**
 * Источник баз. Шаблоны содержат `{name}`; первый — основной, остальные — зеркала на случай, если GitHub заблокирован
 * (jsDelivr обычно доступен там, где raw.githubusercontent.com — нет). Для [GeoFormat.DAT] шаблон — прямая ссылка на файл.
 */
data class GeoSource(
    val id: String,
    val title: String,
    val format: GeoFormat,
    val ip: List<String>,
    val site: List<String>,
) {
    fun templates(kind: GeoKind) = if (kind == GeoKind.IP) ip else site
}

object GeoSources {

    private const val META = "MetaCubeX/meta-rules-dat"
    private const val RUNET = "runetfreedom/russia-v2ray-rules-dat"

    val METACUBEX = GeoSource(
        "metacubex", "MetaCubeX (meta-rules-dat)", GeoFormat.SRS,
        ip = listOf("https://raw.githubusercontent.com/$META/sing/geo/geoip/{name}.srs", "https://cdn.jsdelivr.net/gh/$META@sing/geo/geoip/{name}.srs"),
        site = listOf("https://raw.githubusercontent.com/$META/sing/geo/geosite/{name}.srs", "https://cdn.jsdelivr.net/gh/$META@sing/geo/geosite/{name}.srs"),
    )
    val SAGERNET = GeoSource(
        "sagernet", "SagerNet (sing-geoip / sing-geosite)", GeoFormat.SRS,
        ip = listOf("https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-{name}.srs", "https://cdn.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-{name}.srs"),
        site = listOf("https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-{name}.srs", "https://cdn.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-{name}.srs"),
    )
    /** Списки заблокированных в РФ адресов и доменов (`ru-blocked`) в готовом виде — то, что нужно связке с обходом DPI. */
    val RUNETFREEDOM = GeoSource(
        "runetfreedom", "runetfreedom (russia-v2ray-rules-dat)", GeoFormat.SRS,
        ip = listOf("https://raw.githubusercontent.com/$RUNET/release/sing-box/rule-set-geoip/geoip-{name}.srs", "https://cdn.jsdelivr.net/gh/$RUNET@release/sing-box/rule-set-geoip/geoip-{name}.srs"),
        site = listOf("https://raw.githubusercontent.com/$RUNET/release/sing-box/rule-set-geosite/geosite-{name}.srs", "https://cdn.jsdelivr.net/gh/$RUNET@release/sing-box/rule-set-geosite/geosite-{name}.srs"),
    )
    /** v2fly: geoip.dat (~23 МБ) и dlc.dat (~2 МБ) — тяжелее, зато полный набор; записи конвертируются на устройстве. */
    val V2FLY_DAT = GeoSource(
        "v2fly-dat", "v2fly (geoip.dat / dlc.dat)", GeoFormat.DAT,
        ip = listOf("https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"),
        site = listOf("https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"),
    )
    val RUNETFREEDOM_DAT = GeoSource(
        "runetfreedom-dat", "runetfreedom (geoip.dat / geosite.dat)", GeoFormat.DAT,
        ip = listOf("https://github.com/$RUNET/releases/latest/download/geoip.dat"),
        site = listOf("https://github.com/$RUNET/releases/latest/download/geosite.dat"),
    )

    val ALL = listOf(METACUBEX, SAGERNET, RUNETFREEDOM, V2FLY_DAT, RUNETFREEDOM_DAT)
    fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: METACUBEX
}

/** Своя база по ссылке: готовый `.srs`, текстовый список (CIDR или домены, по одному в строке) либо запись из `.dat`. */
data class CustomGeoSource(val kind: GeoKind, val name: String, val url: String, val type: String = TYPE_SRS) {
    companion object {
        const val TYPE_SRS = "srs"
        const val TYPE_LIST = "list"
        const val TYPE_DAT = "dat"
    }
}

data class GeoSettings(
    val autoUpdate: Boolean = true,
    val intervalHours: Int = 24,
    val ipSource: String = GeoSources.METACUBEX.id,
    val siteSource: String = GeoSources.METACUBEX.id,
    /** Дополнительные наборы к странам из режима и правил: `ru-blocked`, `youtube`… */
    val extraIp: List<String> = emptyList(),
    val extraSite: List<String> = emptyList(),
    val custom: List<CustomGeoSource> = emptyList(),
) {
    fun toJson(): String = JSONObject()
        .put("auto", autoUpdate).put("hours", intervalHours).put("ip_source", ipSource).put("site_source", siteSource)
        .put("extra_ip", JSONArray(extraIp)).put("extra_site", JSONArray(extraSite))
        .put("custom", JSONArray().apply {
            custom.forEach { put(JSONObject().put("kind", it.kind.dir).put("name", it.name).put("url", it.url).put("type", it.type)) }
        }).toString()

    companion object {
        private fun JSONArray?.strings() = if (this == null) emptyList() else (0 until length()).map { getString(it) }

        fun fromJson(json: String?): GeoSettings = runCatching {
            val o = JSONObject(json ?: "{}")
            val c = o.optJSONArray("custom") ?: JSONArray()
            GeoSettings(
                autoUpdate = o.optBoolean("auto", true),
                intervalHours = o.optInt("hours", 24).coerceIn(1, 24 * 30),
                ipSource = o.optString("ip_source", GeoSources.METACUBEX.id),
                siteSource = o.optString("site_source", GeoSources.METACUBEX.id),
                extraIp = o.optJSONArray("extra_ip").strings(),
                extraSite = o.optJSONArray("extra_site").strings(),
                custom = (0 until c.length()).mapNotNull { i ->
                    val x = c.getJSONObject(i)
                    CustomGeoSource(GeoKind.fromDir(x.optString("kind")) ?: return@mapNotNull null, x.optString("name"),
                        x.optString("url"), x.optString("type", CustomGeoSource.TYPE_SRS)).takeIf { it.name.isNotBlank() && it.url.startsWith("http") }
                },
            )
        }.getOrDefault(GeoSettings())
    }
}
