package ru.gidravpn.hydra.data.stats

/** Скорость в момент времени, байт/с. */
data class Speed(val down: Long = 0, val up: Long = 0)

/**
 * Скорость «в моменте» по накопительным счётчикам (0.7.11): разность за последние [windowMs] миллисекунд (по умолчанию —
 * две секунды: плавнее одной, но без запаздывания). Не зависит от источника и частоты замеров — годится и для статуса sing-box,
 * и для счётчиков SSTP/L2TP/AmneziaWG. Сброс счётчика (переподключение) начинает окно заново.
 */
class SpeedMeter(private val windowMs: Long = 2000) {
    private class Sample(val t: Long, val down: Long, val up: Long)
    private val samples = ArrayDeque<Sample>()

    @Synchronized
    fun add(nowMs: Long, down: Long, up: Long): Speed {
        val last = samples.lastOrNull()
        if (last != null && (down < last.down || up < last.up || nowMs <= last.t)) {
            if (nowMs <= last.t && down >= last.down && up >= last.up) return current()
            samples.clear()
        }
        samples.addLast(Sample(nowMs, down, up))
        // Оставляем одну точку старше окна — от неё считаем, чтобы окно всегда покрывало ≥ windowMs.
        while (samples.size > 2 && samples[1].t <= nowMs - windowMs) samples.removeFirst()
        return current()
    }

    @Synchronized
    fun reset() = samples.clear()

    private fun current(): Speed {
        val a = samples.firstOrNull() ?: return Speed()
        val b = samples.last()
        val dt = b.t - a.t
        if (dt <= 0) return Speed()
        return Speed((b.down - a.down) * 1000 / dt, (b.up - a.up) * 1000 / dt)
    }
}
