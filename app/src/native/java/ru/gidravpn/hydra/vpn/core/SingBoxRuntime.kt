package ru.gidravpn.hydra.vpn.core

import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import java.io.File

/**
 * Одноразовая инициализация libbox 1.14 и владение VPN-сервисом через CommandServer.
 *
 * В 1.14 отдельного BoxService больше нет: CommandServer запускает сервис, а CommandClient
 * получает статистику по локальному gRPC-сокету. Обрыв клиента статистики по-прежнему не
 * считается смертью ядра; CommandServerHandler.serviceStop() обрабатывает внешнюю
 * RPC-команду остановки, а не асинхронное завершение sing-box.
 */
object SingBoxRuntime {

    @Volatile private var isSetup = false
    private val baseDir: File get() = ru.gidravpn.hydra.AppCtx.filesDir

    @Volatile private var commandServer: CommandServer? = null
    @Volatile var onServiceClosed: ((String) -> Unit)? = null
    @Volatile private var stopping = false
    @Volatile private var statsClient: CommandClient? = null

    @Synchronized
    fun ensureSetup() {
        if (isSetup) return
        val opts = SetupOptions().apply {
            basePath = baseDir.absolutePath
            workingPath = File(baseDir, "work").apply { mkdirs() }.absolutePath
            tempPath = File(baseDir, "tmp").apply { mkdirs() }.absolutePath
        }
        Libbox.setup(opts)
        isSetup = true
    }

    /** Поднимает CommandServer и запускает принадлежащий ему VPN-сервис. */
    @Synchronized
    fun startService(config: String, platform: PlatformInterface, onLog: (String) -> Unit) {
        stopping = true
        stopStatsPolling()
        commandServer?.let { old ->
            runCatching { old.closeService() }
            runCatching { old.close() }
            commandServer = null
        }
        stopping = false
        val handler = object : CommandServerHandler {
            override fun getSystemProxyStatus(): SystemProxyStatus =
                SystemProxyStatus().apply { available = false; enabled = false }

            override fun serviceStop() {
                if (!stopping) onServiceClosed?.invoke("sing-box остановил сервис")
            }

            override fun serviceReload() {}
            override fun setSystemProxyEnabled(isEnabled: Boolean) {}
            override fun triggerNativeCrash() {
                throw UnsupportedOperationException("native crash не поддерживается Hydra")
            }
            override fun writeDebugMessage(message: String?) {
                if (!message.isNullOrBlank()) onLog("sing-box: $message")
            }
            override fun connectSSHAgent(): Int {
                throw UnsupportedOperationException("SSH agent не поддерживается Android-клиентом Hydra")
            }
        }
        val server = Libbox.newCommandServer(handler, platform)
        try {
            server.start()
            commandServer = server
            server.startOrReloadService(config, OverrideOptions())
        } catch (e: Throwable) {
            commandServer = null
            runCatching { server.closeService() }
            runCatching { server.close() }
            throw e
        }
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
                    onLog("sing-box: статистика подключена (traffic_available=${m.trafficAvailable})")
                }
                onStats(TrafficStats(downBytes = m.downlinkTotal, upBytes = m.uplinkTotal))
            }

            override fun connected() {}
            override fun disconnected(message: String?) {
                onLog("sing-box: канал статистики отключён (${message ?: "без сообщения"})")
            }
            override fun setDefaultLogLevel(level: Int) {}
            override fun clearLogs() {}
            override fun initializeClashMode(modes: StringIterator?, currentMode: String?) {}
            override fun updateClashMode(newMode: String?) {}
            override fun writeConnectionEvents(events: ConnectionEvents?) {}
            override fun writeGroups(message: OutboundGroupIterator?) {}
            override fun writeOutbounds(message: OutboundGroupItemIterator?) {}
            override fun writeLogs(messageList: LogIterator?) {}
        }
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            statusInterval = 1_000_000_000L
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

    /** Штатная остановка: сначала сервис, затем gRPC-сервер. */
    @Synchronized
    fun shutdown() {
        stopping = true
        stopStatsPolling()
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
    }
}
