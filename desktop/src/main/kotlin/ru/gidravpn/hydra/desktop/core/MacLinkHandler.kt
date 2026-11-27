package ru.gidravpn.hydra.desktop.core

import ru.gidravpn.hydra.desktop.Os
import ru.gidravpn.hydra.desktop.Platform

/**
 * Приём hydra://-ссылок на macOS (0.8.4-rc2). LaunchServices доставляет клик по ссылке `hydra://crypt1/…`
 * работающему экземпляру через Apple Event (kAEGetURL), а com.apple.eawt отдаёт его в OpenURIHandler;
 * при холодном старте событие копится и приходит сразу после регистрации обработчика. Второго процесса
 * macOS не запускает (в отличие от Windows/Linux), поэтому [LinkHandoff] здесь не нужен.
 *
 * Классов eawt нет на Windows/Linux — доступ через java.lang.reflect, чтобы код собирался на всех ОС;
 * сама схема объявляется в Info.plist пакета при сборке (scripts/package-desktop.sh), из кода её не добавить.
 */
object MacLinkHandler {
    /** Повесить обработчик ссылок; на не-macOS — пустая операция. true — обработчик встал. */
    fun attach(onLink: (String) -> Unit): Boolean {
        if (Platform.os != Os.MACOS) return false
        return runCatching {
            val appClass = Class.forName("com.apple.eawt.Application")
            val app = appClass.getMethod("getApplication").invoke(null)
            val handlerIf = Class.forName("com.apple.eawt.OpenURIHandler")
            val handler = java.lang.reflect.Proxy.newProxyInstance(
                handlerIf.classLoader, arrayOf(handlerIf),
            ) { _, method, args ->
                if (method.name == "openURI" && args != null && args.isNotEmpty()) {
                    val uri = args[0].javaClass.getMethod("getURI").invoke(args[0]) as? java.net.URI
                    uri?.let { onLink(it.toString()) }
                }
                null
            }
            appClass.getMethod("setOpenURIHandler", handlerIf).invoke(app, handler)
            true
        }.getOrDefault(false)
    }
}
