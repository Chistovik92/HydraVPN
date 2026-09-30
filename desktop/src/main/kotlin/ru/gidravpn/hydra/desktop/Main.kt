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
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ru.gidravpn.hydra.desktop.ui.HydraApp
import java.awt.SystemTray
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
        val trayOk = remember { runCatching { SystemTray.isSupported() }.getOrDefault(false) }
        // Автозапуск при входе в систему — сразу в трей (если трей есть).
        var visible by remember { mutableStateOf(!(trayOk && ru.gidravpn.hydra.desktop.core.Autostart.MINIMIZED_ARG in args)) }
        val icon = remember {
            val bytes = AppController::class.java.getResourceAsStream("/hydra-icon.png")!!.use { it.readBytes() }
            BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
        }

        fun quit() {
            controller.shutdown()
            SingleInstance.release()
            exitApplication()
        }

        LaunchedEffect(Unit) {
            Runtime.getRuntime().addShutdownHook(Thread { controller.shutdown() })
            controller.onStartup()
        }

        if (trayOk) {
            Tray(
                icon = icon,
                tooltip = "Hydra — ${ui.statusText}",
                onAction = { visible = true },
                menu = {
                    Item("Открыть Hydra", onClick = { visible = true })
                    Item(if (ui.active || ui.blocked) "Отключить" else "Подключить", onClick = { if (ui.blocked && !ui.active) controller.disconnect() else controller.toggle() })
                    Separator()
                    Item("Выход", onClick = { quit() })
                },
            )
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

/** Файловая блокировка в каталоге данных — один экземпляр на пользователя. */
private object SingleInstance {
    private var channel: java.nio.channels.FileChannel? = null
    private var lock: java.nio.channels.FileLock? = null

    fun acquire(): Boolean = runCatching {
        val f = java.io.File(Platform.runDir, "hydra.lock")
        val ch = java.io.RandomAccessFile(f, "rw").channel
        // Несколько секунд ожидания: при перезапуске от администратора старый
        // экземпляр ещё закрывается, когда стартует новый.
        var l = ch.tryLock()
        val deadline = System.currentTimeMillis() + 4000
        while (l == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            l = ch.tryLock()
        }
        if (l == null) { ch.close(); return false }
        channel = ch; lock = l
        true
    }.getOrDefault(true)

    fun release() {
        runCatching { lock?.release(); channel?.close() }
    }
}
