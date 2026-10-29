package ru.gidravpn.hydra.vpn.core

/** Pure stale-handshake policy, shared by the native AWG polling loop and unit tests. */
object AwgHealth {
    const val STALE_HANDSHAKE_TIMEOUT_MS = 180_000L

    fun isHandshakeStale(lastHandshakeSec: Long, staleSinceMs: Long, nowMs: Long): Boolean =
        lastHandshakeSec > 0L && staleSinceMs > 0L && nowMs - staleSinceMs >= STALE_HANDSHAKE_TIMEOUT_MS
}
