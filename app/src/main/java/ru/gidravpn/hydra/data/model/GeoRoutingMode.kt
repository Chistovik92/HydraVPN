package ru.gidravpn.hydra.data.model

import java.util.Locale

/**
 * Маршрутизация по геолокации (Фаза 6c) — sing-box `route.rule_set` на
 * bundled `.srs`-базах выбранных стран (см. data/subscription/GeoAssets.kt).
 * Действует одинаково для native sing-box и для Xray-моста — TUN и
 * маршрутизация в обоих случаях на стороне sing-box (см. buildXrayBridge).
 */
enum class GeoRoutingMode(val label: String, val description: String) {
    OFF("Выключено", "Весь трафик идёт через прокси, как обычно."),
    DIRECT(
        "Выбранные страны напрямую, остальное — через прокси",
        "Их IP и сайты идут мимо VPN — быстрее для локальных сервисов."
    ),
    VIA_PROXY(
        "Выбранные страны через прокси, остальное — напрямую",
        "Только их IP и сайты идут через VPN — минимальная нагрузка на прокси."
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

/** ISO-код страны → название по-русски («ru» → «Россия»). */
fun countryName(code: String): String =
    Locale("", code.uppercase()).getDisplayCountry(Locale("ru")).ifBlank { code.uppercase() }
