package com.shadowlink.vpn

import com.shadowlink.data.model.ServerProfile
import com.shadowlink.vpn.core.ConnectionState
import com.shadowlink.vpn.core.TrafficStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Глобальная шина состояния между VpnService и UI.
 * Для небольшого приложения этого достаточно; при росте — заменить на Hilt + репозиторий.
 */
object VpnState {
    val state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val activeServer = MutableStateFlow<ServerProfile?>(null)
    val stats = MutableStateFlow(TrafficStats())
    val connectedSince = MutableStateFlow(0L)

    private val _logs = MutableStateFlow<List<String>>(
        listOf(line("Приложение запущено"))
    )
    val logs = _logs.asStateFlow()

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private fun line(msg: String) = "[${fmt.format(Date())}] $msg"

    fun log(msg: String) {
        _logs.value = (_logs.value + line(msg)).takeLast(500)
    }

    fun clearLogs() { _logs.value = listOf(line("Логи очищены")) }
}
