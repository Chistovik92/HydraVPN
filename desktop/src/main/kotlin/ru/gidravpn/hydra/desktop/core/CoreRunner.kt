package ru.gidravpn.hydra.desktop.core

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import ru.gidravpn.hydra.desktop.ConnectionMode
import ru.gidravpn.hydra.desktop.Os
import ru.gidravpn.hydra.desktop.Platform
import ru.gidravpn.hydra.desktop.Store
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread


/**
 * Клиент «исполняемый файл → локальный SOCKS5» (olcRTC, OpenFlux; 0.7.4). Запускается ДО sing-box: когда SOCKS5 готов
 * (клиент сначала устанавливает сессию с транспортом — это может занять десятки секунд), sing-box поднимается мостом
 * к нему. [secrets] — файлы с ключами, их удаляет [CoreRunner.stop].
 */
class Sidecar(
    val name: String,
    val exe: File,
    val args: List<String>,
    val socksPort: Int,
    val readyTimeoutMs: Long = 45_000,
    val secrets: List<File> = emptyList(),
)
/** Нужен перезапуск Hydra от имени администратора (TUN на Windows). */
class NeedsElevation : IllegalStateException("Режим TUN на Windows требует запуска Hydra от имени администратора")

/**
 * Процессы ядер. sing-box — всегда (tun/прокси, DNS, маршрутизация); Xray-core — когда
 * профиль обслуживает движок Xray: он поднимается первым как обычный процесс
 * пользователя (ему нужен только socks на 127.0.0.1), sing-box становится мостом к нему.
 *
 * Права sing-box для TUN:
 *  - Windows — сам Hydra запущен от администратора ([Elevation]), ядро наследует токен;
 *  - Linux — копия ядра в каталоге данных с capabilities (однократно через pkexec setcap);
 *  - macOS — ядро запускается от root через osascript (системный запрос пароля).
 * В режиме PROXY ядро всегда обычный процесс пользователя.
 *
 * Колбэки несут номер сеанса: выход ядра прошлого подключения не должен ронять текущее.
 */
class CoreRunner(
    private val onLog: (String) -> Unit,
    private val onExit: (session: Int, code: Int, core: String) -> Unit,
) {
    private interface Handle {
        val alive: Boolean
        fun stop()
    }

    @Volatile private var singBox: Handle? = null
    @Volatile private var xray: Handle? = null
    @Volatile private var sidecarSecrets: List<File> = emptyList()

    /** Все запущенные ядра живы. */
    val isAlive: Boolean get() = singBox?.alive == true && (xray == null || xray?.alive == true)

    @Synchronized
    fun start(session: Int, configJson: String, mode: ConnectionMode, xrayConfig: String? = null, xraySocksPort: Int = 0, sidecar: Sidecar? = null) {
        check(singBox?.alive != true && xray?.alive != true) { "ядро уже запущено" }
        val core = Platform.bundledCore()
            ?: error("Не найдено ядро sing-box в пакете приложения — переустановите Hydra")
        val dir = Platform.runDir

        xray = null
        if (xrayConfig != null) {
            val xr = Platform.bundledXray()
                ?: error("Не найдено ядро Xray в пакете приложения — переустановите Hydra или выключите движок Xray")
            val xcfg = File(dir, "xray.json")
            Store.writePrivateAtomic(xcfg, xrayConfig)
            val h = spawn(session, "xray", listOf(xr.absolutePath, "run", "-c", xcfg.absolutePath), XRAY_PID, dir) { "[xray] $it" }
            xray = h
            // sing-box, стартовавший раньше Xray, первые соединения отдал бы в закрытый порт.
            if (!waitPort(xraySocksPort, h)) {
                stop()
                error("Xray не запустился — подробности в журнале")
            }
        }

        if (sidecar != null) {
            val h = spawn(session, sidecar.name, listOf(sidecar.exe.absolutePath) + sidecar.args, SIDECAR_PID, dir) { "[${sidecar.name}] $it" }
            xray = h
            sidecarSecrets = sidecar.secrets
            // Клиент сначала устанавливает сессию с транспортом, и лишь потом открывает SOCKS5 — ждём долго.
            if (!waitPort(sidecar.socksPort, h, sidecar.readyTimeoutMs)) {
                stop()
                error("${sidecar.name}: SOCKS5 не поднялся за ${sidecar.readyTimeoutMs / 1000} с — проверьте параметры и журнал")
            }
        }

        val config = File(dir, "config.json")
        Store.writePrivateAtomic(config, configJson)
        val cmd = { bin: File -> listOf(bin.absolutePath, "run", "-c", config.absolutePath, "-D", dir.absolutePath) }
        try {
            singBox = when {
                mode == ConnectionMode.PROXY -> spawn(session, "sing-box", cmd(core), CORE_PID, dir)
                Platform.os == Os.WINDOWS -> {
                    if (!Elevation.isAdmin()) throw NeedsElevation()
                    spawn(session, "sing-box", cmd(core), CORE_PID, dir)
                }
                Platform.os == Os.LINUX -> spawn(session, "sing-box", cmd(LinuxCaps.prepare(core, onLog)), CORE_PID, dir)
                else -> startMacRoot(session, core, config, dir)
            }
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    /** Сначала sing-box (снимает маршруты tun), потом Xray. */
    @Synchronized
    fun stop() {
        singBox?.stop()
        singBox = null
        xray?.stop()
        xray = null
        sidecarSecrets.forEach { runCatching { it.delete() } }
        sidecarSecrets = emptyList()
    }

    private fun waitPort(port: Int, h: Handle, timeoutMs: Long = 8000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!h.alive) return false
            val open = runCatching {
                Socket(Proxy.NO_PROXY).use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
            }.isSuccess
            if (open) return true
            Thread.sleep(100)
        }
        return false
    }

    companion object {
        private const val CORE_PID = "core.pid"
        private const val XRAY_PID = "xray.pid"
        private const val SIDECAR_PID = "sidecar.pid"

        /**
         * На Windows дочерний процесс переживает падение родителя: ядро прошлого
         * запуска держало бы порты. Гасим его, если pid всё ещё принадлежит нашему ядру.
         */
        fun killStale() {
            killStale(CORE_PID, "sing-box")
            killStale(XRAY_PID, "xray")
            killStale(SIDECAR_PID, "olcrtc", "openflux")
        }

        private fun killStale(pidName: String, vararg prefixes: String) {
            val pidFile = File(Platform.runDir, pidName)
            val pid = runCatching { pidFile.readText().trim().toLong() }.getOrNull()
            if (pid != null) {
                ProcessHandle.of(pid).filter { h ->
                    h.info().command().map { c -> prefixes.any { File(c).name.startsWith(it) } }.orElse(false)
                }.ifPresent { h ->
                    h.destroy()
                    runCatching { h.onExit().get(3, TimeUnit.SECONDS) }
                    if (h.isAlive) h.destroyForcibly()
                }
            }
            pidFile.delete()
        }
    }

    private fun spawn(session: Int, name: String, cmd: List<String>, pidName: String, dir: File, tag: (String) -> String = { it }): Handle {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true).directory(dir)
        // Своих geo-баз Xray не даём (маршрутизация — в sing-box), но ищет он их здесь.
        pb.environment()["XRAY_LOCATION_ASSET"] = dir.absolutePath
        val p = pb.start()
        val pidFile = File(dir, pidName)
        runCatching { pidFile.writeText(p.pid().toString()) }
        thread(isDaemon = true, name = "$name-log") {
            runCatching { p.inputStream.bufferedReader().forEachLine { onLog(tag(it)) } }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            runCatching { if (pidFile.readText().trim() == p.pid().toString()) pidFile.delete() }
            onExit(session, code, name)
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
    private inner class MacRootHandle(val session: Int, val pid: Long, val log: File) : Handle {
        @Volatile private var stopped = false
        // ProcessHandle видит и процессы root — без запуска `ps` на каждый вопрос «жив ли».
        override val alive get() = !stopped && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

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
                    if (!alive) { onExit(session, 0, "sing-box"); break }
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

    private fun startMacRoot(session: Int, core: File, config: File, dir: File): Handle {
        val log = File(dir, "sing-box.log").apply { writeText("") }
        fun q(f: File) = "'" + f.absolutePath.replace("'", "'\\''") + "'"
        val shell = "${q(core)} run -c ${q(config)} -D ${q(dir)} > ${q(log)} 2>&1 & echo \$!"
        val out = osascript("do shell script \"${shell.replace("\\", "\\\\").replace("\"", "\\\"")}\" with administrator privileges")
        val pid = out.trim().toLongOrNull() ?: error("Не удалось запустить ядро с правами администратора: ${out.trim()}")
        return MacRootHandle(session, pid, log)
    }

    private fun osascript(script: String): String {
        val p = ProcessBuilder("osascript", "-e", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); error("Запрос прав администратора не завершился") }
        if (p.exitValue() != 0) error(if ("-128" in out) "Запрос прав администратора отменён" else out.trim())
        return out
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
        // Каталог только для владельца: копию с CAP_NET_ADMIN не запустят другие пользователи.
        val bin = File(Platform.dataDir, "bin").apply { mkdirs(); Platform.privateDir(this) }
        val copy = File(bin, "sing-box")
        if (!copy.isFile || copy.length() != bundled.length() || Files.mismatch(copy.toPath(), bundled.toPath()) != -1L) {
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
        if (!p.waitFor(180, TimeUnit.SECONDS)) { p.destroyForcibly(); error("Запрос прав не завершился за 3 минуты") }
        check(p.exitValue() == 0) {
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
        if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return false }
        return "cap_net_admin" in out
    }
}
