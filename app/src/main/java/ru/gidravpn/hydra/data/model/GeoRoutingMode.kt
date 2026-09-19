package ru.gidravpn.hydra.data.model

import java.util.Locale

/**
 * Маршрутизация по геолокации (Фаза 6c) — sing-box `route.rule_set` на
 * bundled `.srs`-базах выбранных стран (см. data/subscription/GeoAssets.kt).
 * Действует одинаково для native sing-box и для Xray-моста — TUN и
 * маршрутизация в обоих случаях на стороне sing-box (см. buildXrayBridge).
 */
enum class GeoRoutingMode(@androidx.annotation.StringRes val labelRes: Int, @androidx.annotation.StringRes val descriptionRes: Int) {
    OFF(ru.gidravpn.hydra.R.string.geo_off, ru.gidravpn.hydra.R.string.geo_off_desc),
    DIRECT(
        ru.gidravpn.hydra.R.string.geo_direct,
        ru.gidravpn.hydra.R.string.geo_direct_desc
    ),
    VIA_PROXY(
        ru.gidravpn.hydra.R.string.geo_via_proxy,
        ru.gidravpn.hydra.R.string.geo_via_proxy_desc
    );

    companion object {
        fun fromId(id: String?): GeoRoutingMode = when (id) {
            // До 0.6.12 режимы были только для РФ: RU_DIRECT/RU_VIA_PROXY.
            "RU_DIRECT" -> DIRECT
            "RU_VIA_PROXY" -> VIA_PROXY
            else -> entries.firstOrNull { it.name == id } ?: OFF
        }
    }
}

/** ISO-код страны → название на языке интерфейса («ru» → «Россия» / «Russia»). */
fun countryName(code: String): String =
    Locale("", code.uppercase()).getDisplayCountry(Locale.getDefault()).ifBlank { code.uppercase() }
