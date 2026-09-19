package ru.gidravpn.hydra

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * Язык интерфейса (Фаза 6e): «как в системе» / русский / английский.
 *
 * Хранится в обычных SharedPreferences, а не в DataStore: язык нужен синхронно
 * в attachBaseContext() — раньше, чем можно что-либо прочитать асинхронно.
 * Логи подключения намеренно остаются на русском (диагностика, а не интерфейс).
 */
object LocaleHelper {
    const val SYSTEM = "system"
    const val RU = "ru"
    const val EN = "en"

    private const val PREFS = "hydra_ui"
    private const val KEY = "lang"

    fun current(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    fun save(ctx: Context, lang: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang).apply()
    }

    private fun localeFor(lang: String): Locale =
        if (lang == SYSTEM) Resources.getSystem().configuration.locales[0] else Locale(lang)

    /** Обёртка контекста для attachBaseContext() Activity/Service/Application. */
    fun wrap(base: Context): Context {
        val locale = localeFor(current(base))
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        return base.createConfigurationContext(cfg)
    }

    /**
     * Применяет язык к уже живому процессу: ресурсы Application (из них
     * берёт строки ViewModel) и Locale.getDefault() (форматы, названия стран).
     * Activity после этого нужно пересоздать.
     */
    @Suppress("DEPRECATION")
    fun applyToApp(app: Context, lang: String) {
        save(app, lang)
        val locale = localeFor(lang)
        Locale.setDefault(locale)
        val cfg = Configuration(app.resources.configuration)
        cfg.setLocale(locale)
        app.resources.updateConfiguration(cfg, app.resources.displayMetrics)
    }
}
