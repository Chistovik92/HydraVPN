package ru.gidravpn.hydra.desktop.core

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import ru.gidravpn.hydra.desktop.Os
import ru.gidravpn.hydra.desktop.Platform
import java.io.File

/**
 * Регистрация схемы hydra:// в ОС (0.8.4-rc1): клик по ссылке `hydra://crypt1/…` в чате или письме
 * открывает Hydra, и подписка импортируется. Только для установленного приложения — в `gradle run`
 * схема не регистрируется (как и автозапуск). Идемпотентно: вызывается на каждом старте, чтобы путь
 * в реестре не устарел после обновления на новую версию.
 */
object UrlScheme {
    private const val KEY = "Software\\Classes\\hydra"

    /** true — схема зарегистрирована; false — не нужна (macOS: Info.plist пакета) или запуск в dev-режиме. */
    fun register(): Boolean = runCatching {
        val exe = Platform.selfExecutable?.takeUnless {
            File(it).name.lowercase().let { n -> n == "java" || n == "java.exe" || n == "javaw.exe" }
        } ?: return false
        when (Platform.os) {
            Os.WINDOWS -> {
                Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, KEY)
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "", "URL:Hydra subscription")
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "URL Protocol", "")
                Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, "$KEY\\shell\\open\\command")
                // Classic запускается сценарием Hydra.vbs через Windows Script Host (как автозапуск) —
                // иначе мигало бы окно консоли; сценарий пробрасывает аргументы дальше.
                val base = if (Platform.classic) {
                    Platform.classicLauncher?.let { "wscript.exe \"${it.absolutePath}\"" } ?: "\"$exe\""
                } else "\"$exe\""
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, "$KEY\\shell\\open\\command", "", "$base \"%1\"")
                true
            }
            Os.LINUX -> {
                val f = File(
                    System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                        ?: "${System.getProperty("user.home")}/.local/share",
                    "applications/hydra-link-handler.desktop",
                )
                f.parentFile.mkdirs()
                f.writeText(
                    "[Desktop Entry]\nType=Application\nName=Hydra\nExec=\"$exe\" %u\nNoDisplay=true\n" +
                        "MimeType=x-scheme-handler/hydra;\n",
                )
                runCatching { ProcessBuilder("update-desktop-database", f.parentFile.absolutePath).start().waitFor() }
                true
            }
            Os.MACOS -> false // схема — в Info.plist пакета (PlistBuddy в scripts/package-desktop.sh); приём ссылок — MacLinkHandler через eawt
        }
    }.getOrDefault(false)
}