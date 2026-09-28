package ru.gidravpn.hydra.data.model

import java.util.Locale

/**
 * Маршрутизация по геолокации (Фаза 6c) — sing-box `route.rule_set` на
 * bundled `.srs`-базах выбранных стран (см. data/subscription/GeoAssets.kt).
 * Действует одинаково для native sing-box и для Xray-моста — TUN и
 * маршрутизация в обоих случаях на стороне sing-box (см. buildXrayBridge).
 */
enum class GeoRoutingMode(val labelRes: Int, val descriptionRes: Int) {
    OFF(0, 0),
    DIRECT(0, 0),
    VIA_PROXY(0, 0);

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