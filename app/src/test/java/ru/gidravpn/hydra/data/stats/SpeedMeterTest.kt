package ru.gidravpn.hydra.data.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedMeterTest {
    @Test fun steadyRate() {
        val m = SpeedMeter()
        var s = Speed()
        for (i in 0..5) s = m.add(i * 1000L, i * 4_000_000L, i * 400_000L)
        assertEquals(Speed(4_000_000, 400_000), s)
    }

    @Test fun smoothsOverTwoSeconds() {
        val m = SpeedMeter()
        m.add(0, 0, 0); m.add(1000, 1_000_000, 0); m.add(2000, 2_000_000, 0)
        // Секунда простоя: окно 2 с — скорость падает вдвое, а не до нуля.
        assertEquals(500_000, m.add(3000, 2_000_000, 0).down)
        assertEquals(0, m.add(4000, 2_000_000, 0).down)
    }

    @Test fun counterResetRestartsWindow() {
        val m = SpeedMeter()
        m.add(0, 0, 0); m.add(1000, 5_000_000, 0)
        assertEquals(Speed(), m.add(2000, 100, 0))
        assertEquals(1000, m.add(3000, 1100, 0).down)
    }

    @Test fun irregularIntervals() {
        val m = SpeedMeter()
        m.add(0, 0, 0)
        assertEquals(2_000_000, m.add(500, 1_000_000, 0).down)
    }

    @Test fun duplicateTimestampKeepsValue() {
        val m = SpeedMeter()
        m.add(0, 0, 0); val s = m.add(1000, 1000, 1000)
        assertEquals(s, m.add(1000, 1000, 1000))
    }
}
