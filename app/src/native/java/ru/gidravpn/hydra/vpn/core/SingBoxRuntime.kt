package ru.gidravpn.hydra.vpn.core

import android.content.Context
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Connections
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import java.io.File

/**
 * Одноразовая инициализация libbox + живая статистика трафика.
 *
 * Как это устроено в libbox: счётчики отдаёт не сам [BoxService], а пара
 * CommandServer ↔ CommandClient поверх локального сокета (тот же механизм,
 * что использует sing-box-for-android). Порядок обязателен:
 *   1. [startCommandServer] — до создания сервиса;
 *   2. [attachService] — сразу после `Libbox.newService(...)`;
 *   3. [startStatsPolling] — клиент подписывается на COMMAND_STATUS.
 *
 * Требование к конфигу: `experimental.clash_api` — без него libbox отдаёт
 * `trafficAvailable = false` и нули (см. SingBoxConfigBuilder).
 */
object SingBoxRuntime {

    /** Контекст для платформенных методов HydraPlatformInterface. */
    private val appContext: Context? get() = ru.gidravpn.hydra.AppCtx.appContext

    @Volatile private var isSetup = false
    private val baseDir: File get() = ru.gidravpn.hydra.AppCtx.filesDir

    @Volatile private var commandServer: CommandServer? = null
    @Volatile private var statsClient: CommandClient? = null

    @Synchronized
    fun ensureSetup() {
        if (isSetup) return
        // Сигнатура Setup зависит от версии; в 1.11+ используется SetupOptions.
        val opts = SetupOptions().apply {
            basePath = baseDir.absolutePath
            workingPath = File(baseDir, "work").apply { mkdirs() }.absolutePath
            tempPath = File(baseDir, "tmp").apply { mkdirs() }.absolutePath
        }
        Libbox.setup(opts)
        isSetup = true
    }

    /** Поднимает command-server. Вызывать ДО `Libbox.newService(...)`. */
    @Synchronized
    fun startCommandServer(onLog: (String) -> Unit) {
        if (commandServer != null) return
        val handler = object : CommandServerHandler {
            // Системный прокси нам не нужен — VPN идёт через tun.
            override fun getSystemProxyStatus(): SystemProxyStatus =
                SystemProxyStatus().apply { available = false; enabled = false }

            override fun postServiceClose() {}
            override fun serviceReload() {}
            override fun setSystemProxyEnabled(isEnabled: Boolean) {}
        }
        runCatching {
            val server = Libbox.newCommandServer(handler, 300)
            server.start()
            commandServer = server
        }.onFailure {
            // Не фатально: туннель работает и без счётчиков.
            onLog("sing-box: командный сервер не поднялся (${it.message}) — счётчики трафика будут нулевыми")
        }
    }

    /** Связывает поднятый сервис с command-server'ом, иначе статус будет пустым. */
    fun attachService(service: BoxService) {
        runCatching { commandServer?.setService(service) }
    }

    /** Подписывается на COMMAND_STATUS и отдаёт наверх суммарные счётчики. */
    fun startStatsPolling(onStats: (TrafficStats) -> Unit, onLog: (String) -> Unit = {}) {
        if (statsClient != null) return
        var firstStatus = true
        val handler = object : CommandClientHandler {
            override fun writeStatus(message: StatusMessage?) {
                val m = message ?: return
                if (firstStatus) {
                    firstStatus = false
                    // Разовая отметка: видно, что канал статистики реально живой,
                    // а не молча висит (частая причина вечных 0,0 MB).
                    onLog("sing-box: статистика подключена (traffic_available=${m.trafficAvailable})")
                }
                onStats(TrafficStats(downBytes = m.downlinkTotal, upBytes = m.uplinkTotal))
            }

            override fun connected() {}
            override fun disconnected(message: String?) {}
            override fun clearLogs() {}
            override fun initializeClashMode(modes: StringIterator?, currentMode: String?) {}
            override fun updateClashMode(newMode: String?) {}
            override fun writeConnections(message: Connections?) {}
            override fun writeGroups(message: OutboundGroupIterator?) {}
            override fun writeLogs(messageList: StringIterator?) {}
        }
        val options = CommandClientOptions().apply {
            command = Libbox.CommandStatus
            statusInterval = 1_000_000_000L   // go time.Duration → наносекунды, 1 с
        }
        runCatching {
            val client = CommandClient(handler, options)
            client.connect()
            statsClient = client
        }.onFailure {
            onLog("sing-box: не удалось подписаться на статистику (${it.message})")
        }
    }

    fun stopStatsPolling() {
        runCatching { statsClient?.disconnect() }
        statsClient = null
    }

    /** Полное гашение: клиент + command-server. Вызывается при остановке ядра. */
    @Synchronized
    fun shutdown() {
        stopStatsPolling()
        runCatching { commandServer?.close() }
        commandServer = null
    }
}
