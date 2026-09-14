package ru.gidravpn.hydra.data.model

/**
 * Маршрутизация по геолокации (Фаза 6c) — sing-box `route.rule_set` на
 * заранее собранных `.srs`-базах (geoip-ru.srs/geosite-ru.srs, см.
 * data/subscription/GeoAssets.kt). Применяется в SingBoxConfigBuilder и
 * действует одинаково для native sing-box и для Xray-моста — TUN и
 * маршрутизация в обоих случаях на стороне sing-box (см. buildXrayBridge).
 */
enum class GeoRoutingMode(val label: String, val description: String) {
    OFF(
        "Выключено",
        "Весь трафик идёт через прокси, как обычно."
    ),
    RU_DIRECT(
        "РФ напрямую, остальное — через прокси",
        "Российские IP и сайты — мимо VPN (быстрее для локальных сервисов), всё остальное — через прокси."
    ),
    RU_VIA_PROXY(
        "РФ через прокси, остальное — напрямую",
        "Только российские IP и сайты идут через VPN, всё остальное — мимо (обход блокировок с минимальной нагрузкой на прокси)."
    );

    companion object {
        fun fromId(id: String?): GeoRoutingMode =
            entries.firstOrNull { it.name == id } ?: OFF
    }
}
