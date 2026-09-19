package ru.gidravpn.hydra.data.log

import java.io.File
import java.time.LocalDate

/**
 * Логи на диске (Фаза 6d): `filesDir/logs/hydra-YYYY-MM-DD.log`, по файлу в
 * день, старше срока хранения удаляются. Запись — в одном фоновом потоке:
 * VpnState.log зовётся из ядер sing-box/Xray на горячем пути, блокировать
 * его на диске нельзя.
 */
object LogStore {
    /** Защита от переполнения: после 5 МБ за день пишем только WARN/ERROR. */
    private const val DAY_LIMIT_BYTES = 5L * 1024 * 1024

    /** Потолок очереди записи на диск; сверх него строки отбрасываются. */
    private const val QUEUE_CAPACITY = 2048

    @Volatile var mode: LogPersistMode = LogPersistMode.ERRORS
    @Volatile var retention: LogRetention = LogRetention.D3
        set(value) { field = value; io.execute { prune() } }

    /**
     * Запись — в одном фоновом потоке с ОГРАНИЧЕННОЙ очередью (Фаза 7e).
     * Раньше очередь была неограниченной (`newSingleThreadExecutor`): всплеск
     * лога ядра копился в куче, пока диск не догонит. Теперь при переполнении
     * задача молча отбрасывается (`DiscardPolicy`) — потерять несколько строк
     * лога всегда лучше, чем раздуть память работающего VPN-клиента.
     */
    private val io = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue(QUEUE_CAPACITY),
        { r -> Thread(r, "Hydra-LogStore").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.DiscardPolicy(),
    )
    @Volatile private var dir: File? = null

    fun init(filesDir: File) {
        dir = File(filesDir, "logs").apply { mkdirs() }
        io.execute { prune() }
    }

    fun append(line: String, level: LogLevel) {
        val m = mode
        if (m == LogPersistMode.OFF || (m == LogPersistMode.ERRORS && level < LogLevel.WARN)) return
        val d = dir ?: return
        io.execute {
            val file = File(d, LogFiles.nameFor(LocalDate.now()))
            if (file.length() > DAY_LIMIT_BYTES && level < LogLevel.WARN) return@execute
            runCatching { file.appendText("${level.name.first()} $line\n") }
        }
    }

    /** Все сохранённые дни, от старых к новым, одним текстом — для «Сохранить в файл». */
    fun readAll(): String = files().joinToString("") { f ->
        "===== ${f.name} =====\n" + runCatching { f.readText() }.getOrDefault("")
    }

    fun sizeBytes(): Long = files().sumOf { it.length() }

    fun clear() { io.execute { files().forEach { it.delete() } } }

    /**
     * Дождаться, пока очередь записи дойдёт до диска (Фаза 7e). Нужно ровно
     * одному вызывающему — обработчику аварийного завершения в `HydraApp`:
     * процесс вот-вот умрёт, а последняя записанная строка — как раз стек
     * падения. Возвращает false, если не дождались за [timeoutMs].
     */
    fun flushBlocking(timeoutMs: Long): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        // Маркер встаёт в ту же очередь, что и строки: дождались его — значит
        // всё, что было записано раньше, уже на диске. Если очередь переполнена,
        // маркер молча отбрасывается (DiscardPolicy) и мы просто выйдем по
        // таймауту — на пути аварийного завершения это приемлемо.
        io.execute { done.countDown() }
        return runCatching { done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
    }

    private fun files(): List<File> =
        dir?.listFiles { f -> f.name.startsWith("hydra-") && f.name.endsWith(".log") }
            ?.sortedBy { it.name }.orEmpty()

    private fun prune() {
        val d = dir ?: return
        val names = d.list()?.toList().orEmpty()
        LogFiles.expired(names, LocalDate.now(), retention.days).forEach { File(d, it).delete() }
    }
}
