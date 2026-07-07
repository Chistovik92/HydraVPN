package com.shadowlink.vpn.core

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File
import kotlin.concurrent.thread

/** Одноразовая инициализация libbox + опрос статистики. */
object SingBoxRuntime {
    @Volatile private var isSetup = false
    @Volatile private var polling = false
    private val baseDir: File get() = com.shadowlink.AppCtx.filesDir

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
            // Реальный опрос — через Libbox CommandClient (status/groups).
            // Здесь оставлен каркас; подключите CommandClient к вашей версии libbox.
            while (polling) { Thread.sleep(1000) }
        }
    }

    fun stopStatsPolling() { polling = false }
}
