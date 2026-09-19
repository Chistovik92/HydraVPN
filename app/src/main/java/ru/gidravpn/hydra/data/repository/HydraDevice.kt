package ru.gidravpn.hydra.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import ru.gidravpn.hydra.BuildConfig
import java.security.MessageDigest

/**
 * Как клиент представляется панели при загрузке подписки (0.6.21) — так же, как Happ / INCY,
 * но под своим именем. Панели с лимитом устройств (Remnawave и совместимые) считают устройства
 * по HWID; без него часть панелей отказывает в подписке.
 *
 *  - `User-Agent: Hydra/<версия>/android Dalvik/2.1.0 (Linux; U; Android <версия>; <модель>)`
 *  - `x-hwid: <UUID в верхнем регистре>` — аппаратный ID
 *  - `x-device-os: Android`, `x-ver-os: <версия>`, `x-device-model: <модель>`
 *
 * HWID — SHA-256 от ANDROID_ID и имени пакета, оформленный как UUID. ANDROID_ID на Android 8+
 * свой для каждого ключа подписи приложения и пользователя, переживает переустановку и не
 * раскрывает исходный идентификатор (хеш). Сбрасывается только при сбросе устройства.
 */
object HydraDevice {

    @SuppressLint("HardwareIds")
    fun hwid(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        return hwidFrom(androidId.ifBlank { Build.FINGERPRINT }, context.packageName.removeSuffix(".debug"))
    }

    internal fun hwidFrom(seed: String, pkg: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest("$pkg:$seed".toByteArray())
        val hex = h.take(16).joinToString("") { "%02X".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    val model: String get() = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }
        .joinToString(" ").ifBlank { "Android" }

    fun userAgent(): String = userAgentFor(BuildConfig.VERSION_NAME.removeSuffix("-stub"), Build.VERSION.RELEASE, Build.MODEL)

    internal fun userAgentFor(version: String, androidRelease: String, model: String) =
        "Hydra/$version/android Dalvik/2.1.0 (Linux; U; Android $androidRelease; $model)"

    /** Все заголовки запроса подписки. */
    fun headers(context: Context): Map<String, String> = linkedMapOf(
        "User-Agent" to userAgent(),
        "x-hwid" to hwid(context),
        "x-device-os" to "Android",
        "x-ver-os" to Build.VERSION.RELEASE,
        "x-device-model" to model,
        "Accept" to "*/*",
    )
}
