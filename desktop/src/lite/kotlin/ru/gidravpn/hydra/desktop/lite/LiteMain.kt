package ru.gidravpn.hydra.desktop.lite

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.SingleInstance
import ru.gidravpn.hydra.desktop.core.Autostart
import ru.gidravpn.hydra.desktop.tray.TrayController
import java.awt.EventQueue
import javax.imageio.ImageIO
import javax.swing.JOptionPane
import javax.swing.UIManager
import kotlin.system.exitProcess

/**
 * Hydra Classic (0.7.5): оболочка на Swing для систем, где нет Compose Desktop, — 32-битные Windows и Linux
 * (x86, armv7) и Windows 7. Ядра, настройки, маршрутизация, трей и плашка подключения — те же, что у обычной Hydra
 * (общий [AppController]); отличается только окно.
 */
fun main(args: Array<String>) {
    // До первого обращения к Platform: он читает флаг при инициализации.
    System.setProperty("hydra.classic", "true")
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    // hydra://crypt1/… из зарегистрированной схемы ОС (0.8.4-rc1): ссылку забирает запущенный экземпляр.
    val deepLink = args.firstOrNull { it.startsWith("hydra://", ignoreCase = true) }
    // Второй экземпляр запустил бы второе ядро на тех же портах.
    if (!SingleInstance.acquire()) {
        if (deepLink != null) ru.gidravpn.hydra.desktop.core.LinkHandoff.offer(deepLink)
        else JOptionPane.showMessageDialog(null, "Hydra уже запущена.", "Hydra", JOptionPane.INFORMATION_MESSAGE)
        exitProcess(0)
    }
    EventQueue.invokeLater { start(args) }
}

private fun start(args: Array<String>) {
    // hydra://crypt1/… из аргументов запуска: main() уже отбросил второй экземпляр — ссылка наша.
    val deepLink = args.firstOrNull { it.startsWith("hydra://", ignoreCase = true) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    val controller = AppController(scope)
    val iconBytes = AppController::class.java.getResourceAsStream("/hydra-icon.png")!!.use { it.readBytes() }
    val image = ImageIO.read(iconBytes.inputStream())
    liteTheme = controller.ui.value.data.settings.theme

    lateinit var window: LiteWindow
    lateinit var tray: TrayController

    fun quit() {
        controller.shutdownBounded()
        SingleInstance.release()
        tray.remove()
        window.dispose()
        exitProcess(0)
    }

    tray = TrayController(controller, scope, image, onOpen = {
        window.isVisible = true
        window.state = java.awt.Frame.NORMAL
        window.toFront()
    }, onQuit = ::quit)
    val trayOk = tray.install()

    window = LiteWindow(controller, scope, image, onRelaunchAdmin = { controller.relaunchElevated() }, onClose = {
        // С активным VPN окно уходит в трей; без трея или без VPN — выход.
        if (trayOk && controller.ui.value.active) window.isVisible = false else quit()
    })

    // Иконка окна — по выбору в «Оформлении» (0.7.9); значок трея меняет сам TrayController.
    scope.launch {
        var last: String? = null
        controller.ui.collect { u ->
            val res = u.data.settings.appIcon.resource(u.data.settings.theme)
            if (res != last) {
                last = res
                runCatching { AppController::class.java.getResourceAsStream(res)?.use { ImageIO.read(it) } }
                    .getOrNull()?.let { window.iconImage = it }
                val ic = u.data.settings.appIcon; val th = u.data.settings.theme
                Thread { runCatching { ru.gidravpn.hydra.desktop.core.LauncherIcon.apply(ic, th) } }.apply { isDaemon = true }.start()
            }
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread { controller.shutdown() })
    controller.quitHandler = ::quit
    // Автозапуск при входе в систему — сразу в трей (если трей есть).
    window.isVisible = !(trayOk && Autostart.MINIMIZED_ARG in args)
    controller.onStartup(ru.gidravpn.hydra.desktop.core.Elevation.RECONNECT_ARG in args)
    // Схема hydra:// в ОС и передача ссылки от второго экземпляра (0.8.4-rc1).
    Thread { runCatching { ru.gidravpn.hydra.desktop.core.UrlScheme.register() } }.apply { isDaemon = true }.start()
    deepLink?.let { scope.launch { controller.import(it) } }
    ru.gidravpn.hydra.desktop.core.LinkHandoff.watch { link -> scope.launch { controller.import(link) } }
}
