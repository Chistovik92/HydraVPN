package ru.gidravpn.hydra.data.model

/**
 * Хотспот-прокси (Фаза 6f): локальный SOCKS5/HTTP-листенер на 0.0.0.0, чтобы
 * раздать VPN другим устройствам той же сети (точка доступа телефона, Wi-Fi).
 *
 * Это `mixed`-inbound sing-box (SOCKS4/5 и HTTP на одном порту), поэтому он
 * живёт ровно столько, сколько живёт туннель, и идёт через те же правила
 * маршрутизации. Логин и пароль обязательны: открытый прокси в чужой сети —
 * это открытый выход в интернет с вашего адреса.
 */
data class HotspotSettings(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val username: String = DEFAULT_USER,
    val password: String = "",
) {
    /** Включён и настроен достаточно, чтобы его можно было безопасно поднять. */
    val isUsable: Boolean
        get() = enabled && port in PORT_RANGE && username.isNotBlank() && password.length >= MIN_PASSWORD

    companion object {
        const val DEFAULT_PORT = 2080
        const val DEFAULT_USER = "hydra"
        const val MIN_PASSWORD = 6
        val PORT_RANGE = 1024..65535
    }
}
