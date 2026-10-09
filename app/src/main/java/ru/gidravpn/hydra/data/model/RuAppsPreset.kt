package ru.gidravpn.hydra.data.model

/**
 * Пресет «Российские приложения напрямую» (0.7.11, идея Meridian): банки, платёжные системы и госуслуги часто отказываются
 * работать с зарубежного IP, поэтому их выводят из VPN. Берутся только установленные приложения: точные имена пакетов плюс
 * префиксы семейств (у банков несколько пакетов: «Онлайн», «Бизнес», «Инвестиции»).
 */
object RuAppsPreset {
    val exact = setOf(
        "ru.rostel",                                   // Госуслуги
        "ru.sberbankmobile",                           // СберБанк Онлайн
        "com.idamob.tinkoff.android",                  // Т-Банк (Тинькофф)
        "ru.vtb24.mobilebanking.android",              // ВТБ
        "ru.alfabank.mobile.android",                  // Альфа-Банк
        "ru.raiffeisen.mobile.new",                    // Райффайзен
        "ru.gazprombank.android.mobilebank.app",       // Газпромбанк
        "ru.rshb.dbo",                                 // Россельхозбанк
        "ru.nspk.mirpay",                              // Mir Pay
        "ru.nspk.sbpay",                               // СБП
    )

    val prefixes = listOf(
        "ru.sberbank", "ru.sber.", "ru.vtb", "ru.alfabank", "ru.raiffeisen", "ru.gazprombank", "ru.rshb", "ru.nspk",
        "ru.rostel", "ru.gosuslugi", "ru.tinkoff", "ru.tbank", "com.idamob.tinkoff", "ru.sovcom", "ru.pochtabank",
        "ru.mtsbank", "ru.psbank", "ru.otkritie", "ru.fns.", "ru.mos.",
    )

    /** Из [installed] — те пакеты, что относятся к пресету. */
    fun presentIn(installed: Collection<String>): Set<String> =
        installed.filter { it in exact || prefixes.any { p -> it.startsWith(p) } }.toSet()
}
