package ru.gidravpn.hydra.desktop.core

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import org.json.JSONObject
import ru.gidravpn.hydra.desktop.Os
import ru.gidravpn.hydra.desktop.Platform
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Системный прокси для режима PROXY. Не через `set_system_proxy` sing-box: на
 * Windows процесс ядра завершается TerminateProcess и сам ничего не откатит, а на
 * macOS ошибка networksetup роняет inbound целиком. Здесь — сохраняем прежние
 * настройки в файл, ставим свои, по отключению (или при следующем запуске после
 * сбоя) возвращаем как было.
 */
object SystemProxy {

    private val backupFile get() = File(Platform.runDir, "proxy-backup.json")

    /** Прокси Hydra сейчас стоит в системе (есть резервная копия прежних настроек). */
    val engaged: Boolean get() = backupFile.exists()

    /** Включает прокси 127.0.0.1:[port]. Ошибка — текст для UI (соединение при этом работает). */
    fun enable(port: Int): Result<Unit> = runCatching {
        if (!backupFile.exists()) {
            backupFile.writeText(backup().put("pid", ProcessHandle.current().pid()).put("port", port).toString())
        }
        when (Platform.os) {
            Os.WINDOWS -> { Windows.set(true, "127.0.0.1:$port"); Windows.startGuard(backupFile) }
            Os.MACOS -> MacOs.enable(port)
            Os.LINUX -> Linux.enable(port)
        }
    }

    /**
     * Возвращает сохранённые настройки; без резервной копии — ничего не трогает. Копия удаляется только
     * после успеха: раньше сбой отката (нет сессии dbus, занят реестр) стирал её, и прокси оставался навсегда.
     */
    fun restore() {
        val f = backupFile
        if (!f.exists()) return
        val b = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        val ok = runCatching {
            when (Platform.os) {
                Os.WINDOWS -> Windows.restore(b)
                Os.MACOS -> MacOs.restore(b)
                Os.LINUX -> Linux.restore(b)
            }
        }.isSuccess
        if (ok) f.delete()
    }

    private fun backup(): JSONObject = when (Platform.os) {
        Os.WINDOWS -> Windows.backup()
        Os.MACOS -> MacOs.backup()
        Os.LINUX -> Linux.backup()
    }

    private const val BYPASS = "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>"

    // ---------------------------------------------------------------- Windows
    private object Windows {
        private const val KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"

        @Suppress("FunctionName")
        private interface WinInet : StdCallLibrary {
            fun InternetSetOptionW(h: Pointer?, option: Int, buffer: Pointer?, length: Int): Boolean
        }

        private val wininet by lazy { Native.load("wininet", WinInet::class.java, W32APIOptions.DEFAULT_OPTIONS) }

        fun backup(): JSONObject = JSONObject()
            .put("enable", readInt("ProxyEnable"))
            .put("server", readString("ProxyServer"))
            .put("override", readString("ProxyOverride"))

        fun set(enable: Boolean, server: String?, override: String? = BYPASS) {
            Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyEnable", if (enable) 1 else 0)
            if (server != null) Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyServer", server)
            if (override != null) Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyOverride", override)
            // INTERNET_OPTION_SETTINGS_CHANGED + INTERNET_OPTION_REFRESH — иначе браузеры
            // подхватят изменения только после перезапуска.
            wininet.InternetSetOptionW(null, 39, null, 0)
            wininet.InternetSetOptionW(null, 37, null, 0)
        }

        fun restore(b: JSONObject) {
            // Копия, снятая поверх нашего же прокси (сбой прошлого запуска), вернула бы мёртвый 127.0.0.1 — выключаем.
            val server = b.optString("server", "")
            val was = b.optInt("enable", 0) == 1
            val ours = was && server == "127.0.0.1:" + b.optInt("port", -1)
            set(was && !ours, server, b.optString("override", ""))
        }

        /**
         * Страж (0.7.16): скрытый PowerShell ждёт выхода Hydra и, если резервная копия этого экземпляра осталась
         * (завершение процесса, сбой, выключение ПК), сам возвращает прокси. Копия чужого pid не трогается.
         */
        fun startGuard(backup: File) {
            runCatching {
                val script = File(Platform.runDir, "proxy-guard.ps1")
                script.writeText(GUARD)
                ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass",
                    "-File", script.absolutePath, ProcessHandle.current().pid().toString(), backup.absolutePath)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            }
        }

        private val GUARD = """
            param([int]@ParentPid, [string]@Backup)
            try { Wait-Process -Id @ParentPid -ErrorAction Stop } catch {}
            Start-Sleep -Seconds 2
            if (-not (Test-Path @Backup)) { exit }
            @b = Get-Content @Backup -Raw | ConvertFrom-Json
            if (@b.pid -ne @ParentPid) { exit }
            @k = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
            @on = [int]@b.enable
            if (@on -eq 1 -and @b.server -eq ('127.0.0.1:' + @b.port)) { @on = 0 }
            Set-ItemProperty @k ProxyEnable @on
            Set-ItemProperty @k ProxyServer ([string]@b.server)
            Set-ItemProperty @k ProxyOverride ([string]@b.override)
            Add-Type -Namespace H -Name W -MemberDefinition '[DllImport("wininet.dll")] public static extern bool InternetSetOption(IntPtr h, int o, IntPtr b, int l);'
            [H.W]::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0) | Out-Null
            [H.W]::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0) | Out-Null
            Remove-Item @Backup -Force
        """.trimIndent().replace("@", "${'$'}")

        private fun readInt(name: String) = runCatching {
            Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, KEY, name)
        }.getOrDefault(0)

        private fun readString(name: String) = runCatching {
            Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, KEY, name)
        }.getOrDefault("")
    }

    // ---------------------------------------------------------------- macOS
    private object MacOs {
        private val kinds = listOf("webproxy", "securewebproxy", "socksfirewallproxy")

        private fun services(): List<String> = exec("networksetup", "-listallnetworkservices")
            .lines().drop(1).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("*") }

        fun backup(): JSONObject = JSONObject().apply {
            services().forEach { svc ->
                put(svc, JSONObject().apply {
                    kinds.forEach { k ->
                        val out = exec("networksetup", "-get$k", svc)
                        fun field(n: String) = out.lines().firstOrNull { it.startsWith("$n:") }
                            ?.substringAfter(":")?.trim().orEmpty()
                        put(k, JSONObject().put("enabled", field("Enabled") == "Yes")
                            .put("server", field("Server")).put("port", field("Port")))
                    }
                })
            }
        }

        fun enable(port: Int) {
            val svcs = services()
            check(svcs.isNotEmpty()) { "networksetup не нашёл сетевых служб" }
            svcs.forEach { svc -> kinds.forEach { k -> execChecked("networksetup", "-set$k", svc, "127.0.0.1", "$port") } }
        }

        fun restore(b: JSONObject) {
            // Службы, появившиеся после включения (кабель вместо Wi-Fi), в копии отсутствуют — гасим только наш 127.0.0.1.
            services().filter { !b.has(it) }.forEach { svc ->
                kinds.forEach { k ->
                    val out = exec("networksetup", "-get$k", svc).lines().map { it.trim() }
                    if ("Enabled: Yes" in out && "Server: 127.0.0.1" in out) execChecked("networksetup", "-set${k}state", svc, "off")
                }
            }
            b.keys().asSequence().toList().forEach { svc ->
                val o = b.optJSONObject(svc) ?: return@forEach
                kinds.forEach { k ->
                    val p = o.optJSONObject(k) ?: return@forEach
                    if (p.optBoolean("enabled") && p.optString("server").isNotEmpty()) {
                        execChecked("networksetup", "-set$k", svc, p.optString("server"), p.optString("port"))
                    } else {
                        execChecked("networksetup", "-set${k}state", svc, "off")
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Linux
    /** GNOME (и всё на gsettings: Cinnamon, Budgie, MATE-через-gnome-схему) + KDE Plasma. */
    private object Linux {
        private const val G = "org.gnome.system.proxy"
        private val hasGsettings get() = which("gsettings")
        private val kwrite get() = listOf("kwriteconfig6", "kwriteconfig5").firstOrNull(::which)
        private val kread get() = listOf("kreadconfig6", "kreadconfig5").firstOrNull(::which)

        private val schemas = listOf("http", "https", "socks")
        private val kdeKeys = listOf("httpProxy", "httpsProxy", "socksProxy")

        /** Раньше сохранялся только режим: ручной прокси пользователя (хост, порт, исключения) терялся — теперь всё. */
        fun backup(): JSONObject = JSONObject().apply {
            if (hasGsettings) {
                put("gnomeMode", exec("gsettings", "get", G, "mode").trim().trim('\''))
                put("gnomeIgnore", exec("gsettings", "get", G, "ignore-hosts").trim())
                schemas.forEach { sc ->
                    put("gnome_$sc", JSONObject()
                        .put("host", exec("gsettings", "get", "$G.$sc", "host").trim())
                        .put("port", exec("gsettings", "get", "$G.$sc", "port").trim()))
                }
            }
            kread?.let { r ->
                put("kdeType", exec(r, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "ProxyType").trim())
                kdeKeys.forEach { put("kde_$it", exec(r, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", it).trim()) }
            }
        }

        fun enable(port: Int) {
            var applied = false
            if (hasGsettings) {
                for (schema in listOf("http", "https", "socks")) {
                    exec("gsettings", "set", "$G.$schema", "host", "127.0.0.1")
                    exec("gsettings", "set", "$G.$schema", "port", "$port")
                }
                exec("gsettings", "set", G, "ignore-hosts", "['localhost', '127.0.0.0/8', '::1', '10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16']")
                execChecked("gsettings", "set", G, "mode", "manual")
                applied = true
            }
            kwrite?.let { kw ->
                fun k(key: String, v: String) = exec(kw, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", key, v)
                k("httpProxy", "http://127.0.0.1 $port")
                k("httpsProxy", "http://127.0.0.1 $port")
                k("socksProxy", "socks://127.0.0.1 $port")
                k("ProxyType", "1")
                kdeReparse()
                applied = true
            }
            check(applied) { "не найден ни gsettings, ни kwriteconfig — укажите прокси 127.0.0.1:$port в настройках системы вручную" }
        }

        fun restore(b: JSONObject) {
            if (hasGsettings) {
                schemas.forEach { sc ->
                    b.optJSONObject("gnome_$sc")?.let { o ->
                        o.optString("host").takeIf { it.isNotEmpty() }?.let { execChecked("gsettings", "set", "$G.$sc", "host", it) }
                        o.optString("port").takeIf { it.isNotEmpty() }?.let { execChecked("gsettings", "set", "$G.$sc", "port", it) }
                    }
                }
                b.optString("gnomeIgnore").takeIf { it.isNotEmpty() }?.let { execChecked("gsettings", "set", G, "ignore-hosts", it) }
                execChecked("gsettings", "set", G, "mode", b.optString("gnomeMode").ifEmpty { "none" })
            }
            kwrite?.let { kw ->
                fun k(key: String, v: String) =
                    if (v.isEmpty()) exec(kw, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", key, "--delete")
                    else exec(kw, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", key, v)
                kdeKeys.forEach { k(it, b.optString("kde_$it")) }
                k("ProxyType", b.optString("kdeType").ifEmpty { "0" })
                kdeReparse()
            }
        }

        private fun kdeReparse() {
            exec("dbus-send", "--type=signal", "/KIO/Scheduler", "org.kde.KIO.Scheduler.reparseSlaveConfiguration", "string:")
        }
    }

    // ---------------------------------------------------------------- helpers
    private fun which(cmd: String): Boolean =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, cmd).canExecute() }

    private fun exec(vararg cmd: String): String = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.SECONDS)
        out
    }.getOrDefault("")

    private fun execChecked(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0) {
            "${cmd.first()}: ${out.trim().ifEmpty { "код ${runCatching { p.exitValue() }.getOrNull()}" }}"
        }
    }
}
