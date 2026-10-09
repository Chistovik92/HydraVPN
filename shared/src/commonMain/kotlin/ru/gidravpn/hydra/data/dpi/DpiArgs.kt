package ru.gidravpn.hydra.data.dpi

/**
 * Настройки обхода DPI (0.7.12). Движок — ByeDPI (`ciadpi`): локальный SOCKS5, который режет, подделывает и
 * переставляет первые пакеты соединения так, что DPI провайдера не узнаёт запрещённый хост (SNI).
 *
 *  - [strategy] — строка аргументов `ciadpi` (см. [DpiStrategies]);
 *  - [directViaDpi] — трафик «напрямую» (правила «мимо VPN», страны «напрямую») идёт через обход DPI;
 *  - [proxyViaDpi] — соединение с самим VPN-сервером идёт через обход DPI (цепочка «обход DPI → VPN»; только TCP-протоколы).
 */
data class DpiSettings(
    val enabled: Boolean = false,
    val strategy: String = DpiStrategies.DEFAULT,
    val port: Int = DEFAULT_PORT,
    val directViaDpi: Boolean = true,
    val proxyViaDpi: Boolean = false,
) {
    companion object {
        /** Порт локального SOCKS5 ByeDPI (OpenFlux — 10810, olcRTC — 10808, Xray — 10809). */
        const val DEFAULT_PORT = 10880
    }
}

object DpiArgs {

    /** Ключи, которые ломают подпроцесс (справка/версия — выход сразу; демон — отвязка от нас) или задаются нами. */
    private val BANNED = setOf("-h", "--help", "-v", "--version", "-D", "--daemon", "-w", "--pidfile", "-P")
    private val OWN = setOf("-i", "--ip", "-p", "--port")

    /** Разбор строки как в командной оболочке: пробелы разделяют, кавычки группируют. */
    fun shellSplit(s: CharSequence): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote = 0.toChar()
        var has = false
        for (c in s) {
            when {
                quote != 0.toChar() -> if (c == quote) quote = 0.toChar() else cur.append(c)
                c == '"' || c == '\'' -> { quote = c; has = true }
                c.isWhitespace() -> if (has || cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0); has = false }
                else -> cur.append(c)
            }
        }
        if (has || cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** Строка стратегии без служебного: `{sni}` подставлен, опасные и «наши» ключи убраны. */
    fun sanitize(strategy: String): List<String> {
        val toks = shellSplit(strategy.replace("{sni}", DpiStrategies.FAKE_SNI)).dropWhile { !it.startsWith("-") }
        val out = mutableListOf<String>()
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            val key = t.substringBefore('=')
            when {
                key in BANNED -> if (key in setOf("-w", "--pidfile", "-P") && !t.contains('=')) i++
                key in OWN -> if (!t.contains('=')) i++
                // «слитные» формы -i127.0.0.1 / -p1080
                t.length > 2 && t[0] == '-' && t[1] != '-' && (t[1] == 'i' || t[1] == 'p') && t.drop(2).all { it.isDigit() || it == '.' || it == ':' } -> Unit
                else -> out += t
            }
            i++
        }
        return out
    }

    /** Полный список аргументов для `ciadpi` (без имени программы). */
    fun build(strategy: String, port: Int = DpiSettings.DEFAULT_PORT, ip: String = "127.0.0.1"): List<String> =
        listOf("-i", ip, "-p", port.toString()) + sanitize(strategy.ifBlank { DpiStrategies.DEFAULT })

    /** Строка нормальная, если после очистки осталось хоть что-то (ciadpi без аргументов — просто прокси без обхода). */
    fun isUsable(strategy: String): Boolean = sanitize(strategy).isNotEmpty()
}
