package ru.gidravpn.hydra.desktop.core

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import ru.gidravpn.hydra.desktop.Os
import ru.gidravpn.hydra.desktop.Platform
import java.io.File

/**
 * Запуск Hydra при входе в систему (аналог «Подключаться при загрузке» на Android) —
 * только для текущего пользователя, без прав администратора:
 *  - Windows — HKCU\…\Run;
 *  - Linux — ~/.config/autostart/hydra.desktop (XDG);
 *  - macOS — ~/Library/LaunchAgents/ru.gidravpn.hydra.plist.
 * Hydra стартует свёрнутой в трей ([MINIMIZED_ARG]).
 */
object Autostart {
    const val MINIMIZED_ARG = "--minimized"
    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "Hydra"

    /** Лаунчер установленного приложения; в `gradle run` это java — автозапуск не настраиваем. */
    private fun launcher(): String? = Platform.selfExecutable?.takeUnless {
        File(it).name.lowercase().let { n -> n == "java" || n == "java.exe" || n == "javaw.exe" }
    }

    val available: Boolean get() = launcher() != null

    fun set(enabled: Boolean): Result<Unit> = runCatching {
        val exe = launcher() ?: error("автозапуск доступен только в установленной Hydra")
        when (Platform.os) {
            Os.WINDOWS ->
                // Classic запускается сценарием Hydra.vbs — через Windows Script Host (есть и в Windows 7).
                if (enabled) Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME,
                    (if (Platform.classic) "wscript.exe " else "") + "\"$exe\" $MINIMIZED_ARG")
                else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME))
                    Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME)
            Os.LINUX -> {
                val f = File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.config", "autostart/hydra.desktop")
                if (enabled) {
                    f.parentFile.mkdirs()
                    f.writeText("[Desktop Entry]\nType=Application\nName=Hydra\nComment=Hydra VPN client\n" +
                        "Exec=${desktopQuote(exe)} $MINIMIZED_ARG\nTerminal=false\nX-GNOME-Autostart-enabled=true\n")
                } else f.delete()
            }
            Os.MACOS -> {
                val f = File(System.getProperty("user.home"), "Library/LaunchAgents/ru.gidravpn.hydra.plist")
                if (enabled) {
                    f.parentFile.mkdirs()
                    f.writeText("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                        <plist version="1.0"><dict>
                          <key>Label</key><string>ru.gidravpn.hydra</string>
                          <key>ProgramArguments</key><array><string>${xml(exe)}</string><string>$MINIMIZED_ARG</string></array>
                          <key>RunAtLoad</key><true/>
                        </dict></plist>
                    """.trimIndent() + "\n")
                } else f.delete()
            }
        }
    }

    /** Кавычки .desktop Exec: внутри "…" экранируются " ` $ \. */
    private fun desktopQuote(s: String) = "\"" + s.replace(Regex("([\"`$\\\\])"), "\\\\$1") + "\""

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
