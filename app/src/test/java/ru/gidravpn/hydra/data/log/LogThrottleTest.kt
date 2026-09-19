package ru.gidravpn.hydra.data.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ограничитель потока лога (Фаза 7e). Часы подставные — тест не спит.
 */
class LogThrottleTest {

    private var now = 0L
    private fun throttle(max: Int = 3) = LogThrottle(maxPerSecond = max, nowMs = { now })

    @Test
    fun `в пределах лимита пропускает всё`() {
        val t = throttle()
        repeat(3) {
            assertTrue("строка ${it + 1} должна пройти", t.decide(LogLevel.INFO) is LogThrottle.Decision.Pass)
        }
    }

    @Test
    fun `сверх лимита режет`() {
        val t = throttle()
        repeat(3) { t.decide(LogLevel.INFO) }
        assertEquals(LogThrottle.Decision.Drop, t.decide(LogLevel.INFO))
        assertEquals(LogThrottle.Decision.Drop, t.decide(LogLevel.INFO))
    }

    @Test
    fun `предупреждения и ошибки не режутся никогда`() {
        val t = throttle()
        repeat(50) { t.decide(LogLevel.INFO) }
        assertTrue(t.decide(LogLevel.WARN) is LogThrottle.Decision.Pass)
        assertTrue(t.decide(LogLevel.ERROR) is LogThrottle.Decision.Pass)
    }

    @Test
    fun `новая секунда открывает окно заново`() {
        val t = throttle()
        repeat(3) { t.decide(LogLevel.INFO) }
        assertEquals(LogThrottle.Decision.Drop, t.decide(LogLevel.INFO))
        now += 1000
        assertTrue(t.decide(LogLevel.INFO) is LogThrottle.Decision.Pass)
    }

    @Test
    fun `первая прошедшая строка сообщает сколько проглочено`() {
        val t = throttle()
        repeat(3) { t.decide(LogLevel.INFO) }
        repeat(5) { t.decide(LogLevel.INFO) }          // проглочено 5
        now += 1000
        val decision = t.decide(LogLevel.INFO) as LogThrottle.Decision.Pass
        assertTrue("в пометке должно быть число 5: ${decision.suppressedNote}",
            decision.suppressedNote?.contains("5") == true)
        // Счётчик сбрасывается — следующая строка идёт без пометки.
        assertNull((t.decide(LogLevel.INFO) as LogThrottle.Decision.Pass).suppressedNote)
    }

    @Test
    fun `важная строка тоже выносит накопленную пометку`() {
        val t = throttle()
        repeat(10) { t.decide(LogLevel.INFO) }
        val decision = t.decide(LogLevel.ERROR) as LogThrottle.Decision.Pass
        assertTrue(decision.suppressedNote != null)
    }
}
