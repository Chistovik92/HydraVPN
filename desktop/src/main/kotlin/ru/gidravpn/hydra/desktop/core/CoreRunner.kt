package ru.gidravpn.hydra.desktop.core

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import ru.gidravpn.hydra.desktop.ConnectionMode
import ru.gidravpn.hydra.desktop.Os
import ru.gidravpn.hydra.desktop.Platform
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Нужен перезапуск Hydra от имени администратора (TUN на Windows). */
class NeedsElevation : IllegalStateException("Режим TUN на Windows требует запуска Hydra от имени администратора")

/**
 * Процесс ядра sing-box. Права для TUN:
 *  - Windows — сам Hydra запущен от администратора ([Elevation]), ядро наследует токен;
 *  - Linux — копия ядра в каталоге данных с capabilities (однократно через pkexec setcap);
 *  - macOS — ядро запускается от root через osascript (системный запрос пароля).
 * В режиме PROXY ядро всегда обычный процесс пользователя.
 */
class CoreRunner(
    private val onLog: (String) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    private interface Handle {
        val alive: Boolean
        fun stop()
    }

    @Volatile private var handle: Handle? = null

    val isAlive: Boolean get() = handle?.alive == true

    fun start(configJson: String, mode: ConnectionMode) {
        check(!isAlive) { "ядро уже запущено" }
        val core = Platform.bundledCore()
            ?: error("Не найдено ядро sing-box в пакете приложения — переустановите Hydra")
        val dir = Platform.runDir
        val config = File(dir, "config.json")
        writePrivate(config, configJson)

        handle = when {
            mode == ConnectionMode.PROXY -> spawn(listOf(core.absolutePath, "run", "-c", config.absolutePath, "-D", dir.absolutePath))
            Platform.os == Os.WINDOWS -> {
                if (!Elevation.isAdmin()) throw NeedsElevation()
                spawn(listOf(core.absolutePath, "run", "-c", config.absolutePath, "-D", dir.absolutePath))
            }
            Platform.os == Os.LINUX -> {
                val capCore = LinuxCaps.prepare(core, onLog)
                spawn(listOf(capCore.absolutePath, "run", "-c", config.absolutePath, "-D", dir.absolutePath))
            }
            else -> startMacRoot(core, config, dir)
        }
    }

    fun stop() {
        handle?.stop()
        handle = null
    }

    companion object {
        private val pidFile get() = File(Platform.runDir, "core.pid")

        /**
         * На Windows дочерний процесс переживает падение родителя: ядро прошлого
         * запуска держало бы порт прокси. Гасим его, если pid всё ещё принадлежит sing-box.
         */
        fun killStale() {
            val pid = runCatching { pidFile.readText().trim().toLong() }.getOrNull() ?: return
            ProcessHandle.of(pid).filter { h ->
                h.info().command().map { File(it).name.startsWith("sing-box") }.orElse(false)
            }.ifPresent { h ->
                h.destroy()
                runCatching { h.onExit().get(3, TimeUnit.SECONDS) }
                if (h.isAlive) h.destroyForcibly()
            }
            pidFile.delete()
        }
    }

    private fun spawn(cmd: List<String>): Handle {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        runCatching { pidFile.writeText(p.pid().toString()) }
        thread(isDaemon = true, name = "sing-box-log") {
            runCatching { p.inputStream.bufferedReader().forEachLine(onLog) }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            onExit(code)
        }
        return object : Handle {
            override val alive get() = p.isAlive
            override fun stop() {
                if (!p.isAlive) return
                p.destroy()   // SIGTERM на Linux/macOS — sing-box снимает маршруты сам
                if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly().waitFor(3, TimeUnit.SECONDS)
            }
        }
    }

    /** macOS: root-процесс через osascript; лог — в файл, который читаем «хвостом». */
    private inner class MacRootHandle(val pid: Long, val log: File) : Handle {
        @Volatile private var stopped = false
        override val alive get() = !stopped && ProcessBuilder("ps", "-p", "$pid").start().waitFor() == 0

        init {
            thread(isDaemon = true, name = "sing-box-log") {
                var pos = 0L
                while (true) {
                    if (log.length() > pos) {
                        log.inputStream().use { s ->
                            s.skip(pos)
                            val bytes = s.readBytes()
                            pos += bytes.size
                            bytes.decodeToString().lines().filter { it.isNotBlank() }.forEach(onLog)
                        }
                    }
                    if (!alive) { onExit(0); break }
                    Thread.sleep(500)
                }
            }
        }

        override fun stop() {
            if (stopped) return
            osascript("do shell script \"kill $pid\" with administrator privileges")
            stopped = true
        }
    }

    private fun startMacRoot(core: File, config: File, dir: File): Handle {
        val log = File(dir, "sing-box.log").apply { writeText("") }
        fun q(f: File) = "'" + f.absolutePath.replace("'", "'\\''") + "'"
        val shell = "${q(core)} run -c ${q(config)} -D ${q(dir)} > ${q(log)} 2>&1 & echo \$!"
        val out = osascript("do shell script \"${shell.replace("\\", "\\\\").replace("\"", "\\\"")}\" with administrator privileges")
        val pid = out.trim().toLongOrNull() ?: error("Не удалось запустить ядро с правами администратора: ${out.trim()}")
        return MacRootHandle(pid, log)
    }

    private fun osascript(script: String): String {
        val p = ProcessBuilder("osascript", "-e", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(120, TimeUnit.SECONDS)
        if (p.exitValue() != 0) error(if ("-128" in out) "Запрос прав администратора отменён" else out.trim())
        return out
    }

    private fun writePrivate(f: File, text: String) {
        f.writeText(text)
        if (Platform.os != Os.WINDOWS) {
            runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) }
        }
    }
}

/** Права администратора на Windows. */
object Elevation {
    @Suppress("FunctionName")
    private interface Shell32Admin : StdCallLibrary {
        fun IsUserAnAdmin(): Boolean
    }

    fun isAdmin(): Boolean = Platform.os == Os.WINDOWS && runCatching {
        Native.load("shell32", Shell32Admin::class.java).IsUserAnAdmin()
    }.getOrDefault(false)

    /** Запускает эту же программу с запросом UAC. true — запрос ушёл, текущий экземпляр можно закрыть. */
    fun relaunchAsAdmin(): Boolean {
        val info = ProcessHandle.current().info()
        val cmd = info.command().orElse(null) ?: return false
        val args = info.arguments().orElse(emptyArray())
        fun ps(s: String) = "'" + s.replace("'", "''") + "'"
        val argList = if (args.isEmpty()) "" else " -ArgumentList " + args.joinToString(",") { ps(if (' ' in it) "\"$it\"" else it) }
        val script = "Start-Process -FilePath ${ps(cmd)}$argList -Verb RunAs"
        val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        return p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0
    }
}

/**
 * Linux: TUN и маршруты требуют CAP_NET_ADMIN. Вместо запуска ядра от root —
 * копия ядра в ~/.config/hydra/bin с file capabilities (ставятся один раз через
 * pkexec/polkit). Запись в файл сбрасывает capabilities, поэтому подменить копию
 * без повторного запроса пароля нельзя.
 */
private object LinuxCaps {
    private const val CAPS = "cap_net_admin,cap_net_raw,cap_net_bind_service+ep"

    fun prepare(bundled: File, log: (String) -> Unit): File {
        val bin = File(Platform.dataDir, "bin").apply { mkdirs() }
        val copy = File(bin, "sing-box")
        if (!copy.isFile || copy.length() != bundled.length() || !copy.readBytes().contentEquals(bundled.readBytes())) {
            bundled.copyTo(copy, overwrite = true)
            copy.setExecutable(true, true)
        }
        if (hasCaps(copy)) return copy

        val setcap = listOf("/usr/sbin/setcap", "/sbin/setcap", "/usr/bin/setcap").firstOrNull { File(it).canExecute() }
            ?: error("Не найден setcap (пакет libcap2-bin / libcap). Установите его или выполните:\nsudo setcap $CAPS ${copy.absolutePath}")
        val pkexec = listOf("/usr/bin/pkexec", "/bin/pkexec").firstOrNull { File(it).canExecute() }
            ?: error("Не найден pkexec (polkit). Выполните вручную:\nsudo $setcap $CAPS ${copy.absolutePath}")
        log("Hydra: запрашиваю права на TUN (pkexec setcap)…")
        val p = ProcessBuilder(pkexec, setcap, CAPS, copy.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(180, TimeUnit.SECONDS) && p.exitValue() == 0) {
            if (p.exitValue() == 126) "Запрос прав отменён" else "setcap не удался: ${out.trim()}"
        }
        check(hasCaps(copy)) {
            "Capabilities не применились (раздел смонтирован с nosuid?). Выполните:\nsudo $setcap $CAPS ${copy.absolutePath}"
        }
        return copy
    }

    private fun hasCaps(f: File): Boolean {
        val getcap = listOf("/usr/sbin/getcap", "/sbin/getcap", "/usr/bin/getcap").firstOrNull { File(it).canExecute() } ?: return false
        val p = ProcessBuilder(getcap, f.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.SECONDS)
        return "cap_net_admin" in out
    }
}
