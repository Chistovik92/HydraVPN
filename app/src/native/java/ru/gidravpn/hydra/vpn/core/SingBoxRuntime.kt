package ru.gidravpn.hydra.vpn.core

import android.content.Context
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File
import kotlin.concurrent.thread

/**
 * Одноразовая инициализация libbox + опрос статистики.
 *
 * Статистика: sing-box отдаёт её через CommandClient (Clash API поверх
 * box service). Референс — SagerNet/sing-box-for-android: GroupUI /
 * CommandClient с CommandClientOptions{command = Libbox.COMMAND_STATUS}.
 * Пока .aar не собран — поток-каркас, чтобы не блокировать разработку UI.
 */
object SingBoxRuntime {

    /** Контекст для платформенных методов HydraPlatformInterface. */
    private val appContext: Context? get() = ru.gidravpn.hydra.AppCtx.appContext

    @Volatile private var isSetup = false
    @Volatile private var polling = false
    private val baseDir: File get() = ru.gidravpn.hydra.AppCtx.filesDir

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

    fun startStatsPolling(onStats: (TrafficStats) -> Unit) {
        polling = true
        thread(name = "singbox-stats") {
            // TODO(libbox.aar): реальный опрос через CommandClient:
            //   val client = Libbox.newCommandClient(CommandClientOptions().apply {
            //       command = Libbox.COMMAND_STATUS
            //       interval = 1000  // мс между опросами
            //   })
            //   client.start(object : io.nekohasekai.libbox.CommandClientHandler {
            //       override fun onChanged(message: String?) {
            //           // StatusMessage → TrafficStats(downBytes/upBytes)
            //       }
            //   })
            // Сигнатуры зависят от версии libbox — сверить после сборки .aar.
            while (polling) { Thread.sleep(1000) }
        }
    }

    fun stopStatsPolling() { polling = false }
}
