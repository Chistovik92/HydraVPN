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

    /** Включает прокси 127.0.0.1:[port]. Ошибка — текст для UI (соединение при этом работает). */
    fun enable(port: Int): Result<Unit> = runCatching {
        if (!backupFile.exists()) backupFile.writeText(backup().toString())
        when (Platform.os) {
            Os.WINDOWS -> Windows.set(true, "127.0.0.1:$port")
            Os.MACOS -> MacOs.enable(port)
            Os.LINUX -> Linux.enable(port)
        }
    }

    /** Возвращает сохранённые настройки; без резервной копии — ничего не трогает. */
    fun restore() {
        val f = backupFile
        if (!f.exists()) return
        runCatching {
            val b = JSONObject(f.readText())
            when (Platform.os) {
                Os.WINDOWS -> Windows.restore(b)
                Os.MACOS -> MacOs.restore(b)
                Os.LINUX -> Linux.restore(b)
            }
        }
        f.delete()
    }

    private fun backup(): JSONObject = when (Platform.os) {
        Os.WINDOWS -> Windows.backup()
        Os.MACOS -> MacOs.backup()
        Os.LINUX -> Linux.backup()
    }

    private const val BYPASS = "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.2*;172.30.*;172.31.*;192.168.*;<local>"

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

        fun restore(b: JSONObject) = set(
            b.optInt("enable", 0) == 1,
            b.optString("server", ""),
            b.optString("override", ""),
        )

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
            b.keys().forEach { svc ->
                val o = b.getJSONObject(svc)
                kinds.forEach { k ->
                    val p = o.optJSONObject(k) ?: return@forEach
                    if (p.optBoolean("enabled") && p.optString("server").isNotEmpty()) {
                        exec("networksetup", "-set$k", svc, p.optString("server"), p.optString("port"))
                    } else {
                        exec("networksetup", "-set${k}state", svc, "off")
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

        fun backup(): JSONObject = JSONObject().apply {
            if (hasGsettings) put("gnomeMode", exec("gsettings", "get", G, "mode").trim().trim('\''))
            kread?.let { put("kdeType", exec(it, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "ProxyType").trim()) }
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
            if (hasGsettings) exec("gsettings", "set", G, "mode", b.optString("gnomeMode").ifEmpty { "none" })
            kwrite?.let { kw ->
                exec(kw, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "ProxyType", b.optString("kdeType").ifEmpty { "0" })
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
