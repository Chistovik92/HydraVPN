package ru.gidravpn.hydra.desktop

/** Файловая блокировка в каталоге данных — один экземпляр на пользователя. */
internal object SingleInstance {
    private var channel: java.nio.channels.FileChannel? = null
    private var lock: java.nio.channels.FileLock? = null

    fun acquire(): Boolean = runCatching {
        val f = java.io.File(Platform.runDir, "hydra.lock")
        val ch = java.io.RandomAccessFile(f, "rw").channel
        // Ожидание: при перезапуске от администратора старый экземпляр ещё останавливает ядра (до ~10 с, а с зависшей
        // внешней командой дольше), когда стартует новый. 15 с не хватало — новый выходил с «уже запущена», старый уже закрылся.
        var l = ch.tryLock()
        val deadline = System.currentTimeMillis() + 60_000
        while (l == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            l = ch.tryLock()
        }
        if (l == null) { ch.close(); return false }
        channel = ch; lock = l
        true
    }.getOrDefault(true)

    fun release() {
        runCatching { lock?.release(); channel?.close() }
    }
}
