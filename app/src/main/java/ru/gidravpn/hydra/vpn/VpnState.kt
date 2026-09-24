package ru.gidravpn.hydra.vpn

import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.vpn.core.ConnectionState
import ru.gidravpn.hydra.vpn.core.TrafficStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    /**
     * Системный Always-on VPN (Фаза 8): (включён, «Блокировать соединения без VPN»). Узнать это
     * можно только изнутри поднятого VpnService (API 29+), поэтому сервис публикует значения
     * при каждом establish(); null — ещё не подключались в этом процессе или Android < 10.
     */
    val alwaysOn = MutableStateFlow<Pair<Boolean, Boolean>?>(null)

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private fun line(msg: String) = "[${fmt.format(Date())}] $msg"

    // Ядро sing-box/xray шлёт строки лога с терминальными цветовыми
    // escape-последовательностями. Compose Text их не интерпретирует и
    // показывает как мусорные символы, поэтому вырезаем перед сохранением.
    //
    // Прошлый регекс начинался с литерала '[' и не захватывал сам символ ESC
    // (0x1B): тот оставался в строке и рисовался в UI «точками» — в живом логе
    // sing-box это выглядело как ".INFO." вместо "INFO". Теперь матчим полную
    // CSI-последовательность (ESC + '[' + параметры + финальный байт) и на
    // всякий случай подчищаем одиночные ESC.
    private const val ESC = "\u001B"
    private val ansiCodes = Regex("$ESC\\[[0-9;?]*[ -/]*[@-~]")
    private fun stripAnsi(msg: String) = ansiCodes.replace(msg, "").replace(ESC, "")

    private val _logs = MutableStateFlow<List<String>>(
        listOf(line("Приложение запущено"))
    )
    val logs = _logs.asStateFlow()

    private const val MAX_LINES = 500
    private val throttle = ru.gidravpn.hydra.data.log.LogThrottle()

    /**
     * Зовётся с любых потоков, в том числе с JNI-потоков sing-box на горячем
     * пути.
     *
     * Фаза 7e, две правки:
     *  - `update {}` вместо `_logs.value = _logs.value + …`: то был
     *    read-modify-write без атомарности, и при параллельных вызовах строки
     *    просто терялись (побеждал тот, кто записал последним);
     *  - [LogThrottle]: всплеск лога ядра больше не превращается в сотни копий
     *    списка на 500 элементов в секунду. WARN/ERROR не режутся никогда.
     */
    fun log(msg: String) {
        val clean = stripAnsi(msg)
        android.util.Log.d("HydraCore", clean)
        val level = ru.gidravpn.hydra.data.log.LogLevel.of(clean)
        val decision = throttle.decide(level)
        if (decision is ru.gidravpn.hydra.data.log.LogThrottle.Decision.Drop) return

        val note = (decision as ru.gidravpn.hydra.data.log.LogThrottle.Decision.Pass).suppressedNote
        val stamped = line(clean)
        val lines = if (note == null) listOf(stamped) else listOf(line(note), stamped)
        _logs.update { (it + lines).takeLast(MAX_LINES) }
        lines.forEach { ru.gidravpn.hydra.data.log.LogStore.append(it, level) }
    }

    fun clearLogs() { _logs.value = listOf(line("Логи очищены")) }
}
