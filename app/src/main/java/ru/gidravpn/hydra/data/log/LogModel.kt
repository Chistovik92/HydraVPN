package ru.gidravpn.hydra.data.log

import java.time.LocalDate

/** Уровень строки лога — по её тексту: наши сообщения и строки sing-box/Xray («ERROR[0000] ...»). */
enum class LogLevel {
    DEBUG, INFO, WARN, ERROR;

    companion object {
        private val ERROR_RE = Regex("\\b(ERROR|FATAL|PANIC)\\b")
        private val WARN_RE = Regex("\\bWARN(ING)?\\b")
        private val DEBUG_RE = Regex("\\b(DEBUG|TRACE)\\b")

        fun of(msg: String): LogLevel = when {
            "Ошибка" in msg || ERROR_RE.containsMatchIn(msg) -> ERROR
            "Kill Switch" in msg || WARN_RE.containsMatchIn(msg) -> WARN
            DEBUG_RE.containsMatchIn(msg) -> DEBUG
            else -> INFO
        }
    }
}

/**
 * Что писать на диск. По умолчанию — только предупреждения и ошибки: строки
 * sing-box уровня INFO содержат адрес каждого соединения, т.е. историю
 * посещённых сайтов, и хранить её в файле без явного согласия нельзя.
 */
enum class LogPersistMode(val label: String, val description: String) {
    OFF("Не сохранять", "Логи только в памяти, пропадают при закрытии приложения."),
    ERRORS(
        "Только ошибки и предупреждения",
        "Без адресов сайтов — хватает, чтобы разобрать сбой подключения задним числом."
    ),
    ALL(
        "Полный журнал",
        "Включая адрес каждого соединения — это история посещённых сайтов на устройстве."
    );

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.name == id } ?: ERRORS
    }
}

enum class LogRetention(val days: Int, val label: String) {
    D1(1, "1 день"), D3(3, "3 дня"), D7(7, "7 дней");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.name == id } ?: D3
    }
}

/** Именование и ротация файлов — отдельно от ввода-вывода, чтобы покрыть тестами. */
object LogFiles {
    private val NAME = Regex("^hydra-(\\d{4}-\\d{2}-\\d{2})\\.log$")

    fun nameFor(date: LocalDate) = "hydra-$date.log"

    /** Файлы старше keepDays дней (сегодняшний считается первым днём); чужие имена не трогаем. */
    fun expired(names: List<String>, today: LocalDate, keepDays: Int): List<String> =
        names.filter { name ->
            val date = NAME.matchEntire(name)?.groupValues?.get(1)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@filter false
            date.isBefore(today.minusDays((keepDays - 1).toLong()))
        }
}
