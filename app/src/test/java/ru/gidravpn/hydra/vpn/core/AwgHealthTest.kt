package ru.gidravpn.hydra.vpn.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgHealthTest {
    @Test fun noHandshakeIsNotReportedAsStale() {
        assertFalse(AwgHealth.isHandshakeStale(0, 1, AwgHealth.STALE_HANDSHAKE_TIMEOUT_MS + 1))
    }

    @Test fun unchangedHandshakeBecomesStaleAfterTimeout() {
        assertFalse(AwgHealth.isHandshakeStale(10, 100, AwgHealth.STALE_HANDSHAKE_TIMEOUT_MS + 99))
        assertTrue(AwgHealth.isHandshakeStale(10, 100, AwgHealth.STALE_HANDSHAKE_TIMEOUT_MS + 100))
    }

    @Test fun clockGoingBackDoesNotTriggerReconnect() {
        assertFalse(AwgHealth.isHandshakeStale(10, 200, 100))
    }
}
