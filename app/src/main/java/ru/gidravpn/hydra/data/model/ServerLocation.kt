package ru.gidravpn.hydra.data.model

/**
 * Локация сервера для «витрин» вне приложения — плитки в шторке и виджета (0.6.23):
 * флаг, страна и имя сервера одной строкой. Флаг панели обычно ставят в начало имени
 * («🇩🇪 Germany-1»), поле [ServerProfile.flag] — запасной вариант.
 */
object ServerLocation {

    private fun isRegional(cp: Int) = cp in 0x1F1E6..0x1F1FF

    /**
     * Флаг в начале строки: два regional indicator подряд. Кодовыми точками, а не регуляркой:
     * класс символов из суррогатов («[\uD83C]») Java-regex с парами не сопоставляет.
     */
    private fun leadingFlag(s: String): String? {
        val t = s.trimStart()
        if (t.codePointCount(0, t.length) < 2) return null
        val a = t.codePointAt(0)
        val b = t.codePointAt(Character.charCount(a))
        return if (isRegional(a) && isRegional(b)) t.substring(0, Character.charCount(a) + Character.charCount(b)) else null
    }

    /** Флаг-эмодзи из начала имени, иначе поле flag профиля. */
    fun flag(p: ServerProfile): String = leadingFlag(p.name) ?: p.flag

    /** Флаг из двух regional indicator → ISO-код («🇩🇪» → «de»), иначе null. */
    fun isoCode(flag: String): String? {
        val cps = flag.codePoints().toArray()
        if (cps.size != 2 || cps.any { !isRegional(it) }) return null
        return cps.joinToString("") { ('a' + (it - 0x1F1E6)).toString() }
    }

    /** Имя без флага в начале. */
    fun bareName(p: ServerProfile): String =
        leadingFlag(p.name)?.let { p.name.trimStart().removePrefix(it).trim() } ?: p.name.trim()

    /**
     * «🇩🇪 Германия · Frankfurt-1». Страна берётся из флага на языке интерфейса; если имя
     * сервера и так совпадает со страной, второй раз не повторяется. Без флага — просто имя.
     */
    fun label(p: ServerProfile, countryName: (String) -> String = ::countryName): String {
        val flag = flag(p)
        val name = bareName(p).ifBlank { p.address }
        val country = isoCode(flag)?.let(countryName)
        return when {
            country == null -> if (flag == "🌐") name else "$flag $name"
            name.equals(country, ignoreCase = true) -> "$flag $country"
            else -> "$flag $country · $name"
        }
    }
}
