package ru.gidravpn.hydra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test fun backoffGrowsThenCaps() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..5).map { ReconnectPolicy.backoffMs(it) })
    }

    @Test fun neverExceedsCapOrOverflows() {
        (0..200).forEach { assertTrue(ReconnectPolicy.backoffMs(it) in 2_000L..30_000L) }
        assertEquals(2_000L, ReconnectPolicy.backoffMs(-5))
    }
}
