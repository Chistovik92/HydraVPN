package ru.gidravpn.hydra.vpn

import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.vpn.core.ConnectionState
import ru.gidravpn.hydra.vpn.core.TrafficStats
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

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private fun line(msg: String) = "[${fmt.format(Date())}] $msg"

    // Ядро sing-box/xray шлёт строки лога с терминальными цветовыми
    // escape-последовательностями. Compose Text их не интерпретирует и
    // показывает как мусорные символы, поэтому вырезаем перед сохранением.
    private val ansiCodes = Regex("""\Q[\E[0-9;]*m""")
    private fun stripAnsi(msg: String) = ansiCodes.replace(msg, "")

    private val _logs = MutableStateFlow<List<String>>(
        listOf(line("Приложение запущено"))
    )
    val logs = _logs.asStateFlow()

    fun log(msg: String) {
        val clean = stripAnsi(msg)
        android.util.Log.d("HydraCore", clean)
        _logs.value = (_logs.value + line(clean)).takeLast(500)
    }

    fun clearLogs() { _logs.value = listOf(line("Логи очищены")) }
}
