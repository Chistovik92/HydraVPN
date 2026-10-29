package ru.gidravpn.hydra.data.model

enum class GeoRoutingMode {
    OFF, DIRECT, VIA_PROXY;
    companion object {
        fun fromId(id: String?): GeoRoutingMode = when (id) {
            "RU_DIRECT" -> DIRECT
            "RU_VIA_PROXY" -> VIA_PROXY
            else -> entries.firstOrNull { it.name == id } ?: OFF
        }
    }
}

/** ISO-код страны → название на языке интерфейса. */
fun countryName(code: String): String =
    java.util.Locale("", code.uppercase()).getDisplayCountry(java.util.Locale.getDefault())
        .ifBlank { code.uppercase() }
