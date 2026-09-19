package ru.gidravpn.hydra.data.log

/**
 * Ограничитель потока строк лога (Фаза 7e).
 *
 * Зачем: `VpnState.log()` зовут ядра на горячем пути — sing-box уровня DEBUG
 * умеет выдавать сотни строк в секунду на каждое соединение. Каждая строка
 * стоит копии списка на 500 элементов в UI и задачи в очереди дисковой
 * записи, так что всплеск лога превращается в рост кучи и дёрганый интерфейс
 * ровно в тот момент, когда пользователю нужно нормально работающее
 * соединение.
 *
 * Поведение: пропускаем не более [maxPerSecond] строк в секунду; лишние
 * считаются и на следующей пропущенной строке отдаются одной пометкой
 * «сколько проглочено». Строки уровня WARN/ERROR не режем никогда — ради них
 * лог и ведётся.
 *
 * Логика вынесена из [ru.gidravpn.hydra.vpn.VpnState] отдельным классом с
 * внешними часами, чтобы её можно было покрыть обычным JVM-тестом: сам
 * `VpnState` зовёт `android.util.Log` и на JVM не запускается (в проекте не
 * включён `unitTests.returnDefaultValues`).
 */
class LogThrottle(
    private val maxPerSecond: Int = 40,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var windowStart = 0L
    private var inWindow = 0
    private var dropped = 0

    /** Что делать со строкой: пропустить (возможно, с пометкой) или проглотить. */
    sealed interface Decision {
        /** Строку показать; [suppressedNote] — не-null, если перед ней надо отметить проглоченные. */
        data class Pass(val suppressedNote: String?) : Decision
        data object Drop : Decision
    }

    @Synchronized
    fun decide(level: LogLevel): Decision {
        val now = nowMs()
        if (now - windowStart >= 1000) {
            windowStart = now
            inWindow = 0
        }
        // Предупреждения и ошибки — всегда, они и есть смысл лога.
        val important = level >= LogLevel.WARN
        if (!important && inWindow >= maxPerSecond) {
            dropped++
            return Decision.Drop
        }
        inWindow++
        val note = if (dropped > 0) "… пропущено строк лога: $dropped (слишком быстрый поток)" else null
        dropped = 0
        return Decision.Pass(note)
    }
}
