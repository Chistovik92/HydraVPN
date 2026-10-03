package ru.gidravpn.hydra.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import ru.gidravpn.hydra.desktop.tray.TrayController
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ru.gidravpn.hydra.desktop.ui.HydraApp
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Второй экземпляр запустил бы второе ядро на тех же портах.
    if (!SingleInstance.acquire()) {
        javax.swing.JOptionPane.showMessageDialog(null, "Hydra уже запущена.", "Hydra", javax.swing.JOptionPane.INFORMATION_MESSAGE)
        exitProcess(0)
    }
    application(exitProcessOnExit = true) {
        val scope = rememberCoroutineScope()
        val controller = remember { AppController(scope) }
        val ui by controller.ui.collectAsState()
        val iconBytes = remember { AppController::class.java.getResourceAsStream("/hydra-icon.png")!!.use { it.readBytes() } }
        val icon = remember { BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(iconBytes).toComposeImageBitmap()) }
        // Автозапуск при входе в систему — сразу в трей (если трей есть).
        var visible by remember { mutableStateOf(!(runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false) && ru.gidravpn.hydra.desktop.core.Autostart.MINIMIZED_ARG in args)) }

        fun quit() {
            controller.shutdown()
            SingleInstance.release()
            exitApplication()
        }

        // Значок в трее и плашка со сведениями о подключении (0.7.5): общая с Classic, на AWT.
        val tray = remember {
            TrayController(controller, scope, javax.imageio.ImageIO.read(iconBytes.inputStream()), onOpen = { visible = true }, onQuit = ::quit)
        }
        val trayOk = remember { tray.install() }
        DisposableEffect(Unit) { onDispose { tray.remove() } }

        LaunchedEffect(Unit) {
            Runtime.getRuntime().addShutdownHook(Thread { controller.shutdown() })
            controller.quitHandler = ::quit
            controller.onStartup()
        }

        Window(
            onCloseRequest = {
                // С активным VPN окно уходит в трей; без трея или без VPN — выход.
                if (trayOk && ui.active) visible = false else quit()
            },
            visible = visible,
            title = "Hydra ${Platform.version}",
            icon = icon,
            state = rememberWindowState(size = DpSize(980.dp, 680.dp)),
        ) {
            window.minimumSize = java.awt.Dimension(820, 560)
            HydraApp(controller, ui, onRelaunchAdmin = {
                controller.shutdown()
                SingleInstance.release()
                if (controller.relaunchAsAdmin()) exitApplication()
                else {
                    SingleInstance.acquire()
                    controller.toast("Запрос прав администратора отклонён")
                }
            })
        }
    }
}

