package ru.gidravpn.hydra.desktop.core

import ru.gidravpn.hydra.desktop.Platform
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY

/**
 * Передача hydra://-ссылки уже запущенному экземпляру (0.8.4-rc1). ОС открывает зарегистрированную
 * схему новым процессом; SingleInstance не пускает второй экземпляр — поэтому второй атомарно пишет
 * ссылку в служебный файл и молча выходит, а первый забирает её через WatchService (события, не
 * поллинг). Файл лежит в закрытом на чтение runDir — другие пользователи ОС ссылку не подменят.
 */
internal object LinkHandoff {
    private const val NAME = "import-link.txt"

    private fun file() = File(Platform.runDir, NAME)

    /** Второй экземпляр оставляет ссылку первому; запись атомарна — первый не прочитает половину. */
    fun offer(link: String) {
        runCatching {
            val tmp = File(Platform.runDir, "$NAME.tmp")
            tmp.writeText(link)
            java.nio.file.Files.move(tmp.toPath(), file().toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        }
    }

    /**
     * Первый экземпляр: сначала забирает ссылку, оставленную до старта наблюдения (гонка «предложили
     * между acquire() и watch()»), затем следит за каталогом. [onLink] вызывается из потока
     * наблюдателя — в UI должен перепрыгнуть вызывающий. Ошибка наблюдения не фатальна: ссылку можно
     * вставить в поле импорта руками.
     */
    fun watch(onLink: (String) -> Unit) {
        drain()?.let(onLink)
        Thread {
            runCatching {
                FileSystems.getDefault().newWatchService().use { ws ->
                    Platform.runDir.toPath().register(ws, ENTRY_CREATE, ENTRY_MODIFY)
                    while (true) {
                        val key = try {
                            ws.take()
                        } catch (_: InterruptedException) {
                            break
                        } ?: break
                        key.pollEvents() // до reset(): непрочитанные события держали бы ключ «сигнальным»
                        drain()?.let(onLink)
                        if (!key.reset()) break
                    }
                }
            }
        }.apply { isDaemon = true; name = "hydra-link-handoff" }.start()
    }

    /** Читает и удаляет файл; null — файла нет, он пуст или это не hydra://-ссылка. */
    private fun drain(): String? {
        val f = file()
        val link = runCatching { if (f.isFile) f.readText().trim() else null }.getOrNull() ?: return null
        runCatching { f.delete() }
        return link.takeIf { it.startsWith("hydra://", ignoreCase = true) }
    }
}