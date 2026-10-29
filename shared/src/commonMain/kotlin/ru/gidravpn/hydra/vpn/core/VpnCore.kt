package ru.gidravpn.hydra.vpn.core

/** Общие типы состояния и статистики; Android VpnCore находится в androidMain. */
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR, RECONNECTING }
data class TrafficStats(val downBytes: Long = 0, val upBytes: Long = 0)
