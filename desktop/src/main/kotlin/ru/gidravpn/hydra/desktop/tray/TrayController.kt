package ru.gidravpn.hydra.desktop.tray

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.Status
import ru.gidravpn.hydra.desktop.UiState
import ru.gidravpn.hydra.desktop.core.DesktopConfig
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MenuItem
import java.awt.Point
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities

/**
 * Значок в трее и плашка со сведениями о подключении (0.7.5) — аналог уведомления Hydra на Android:
 * состояние, сервер и движок, скорость и объём трафика, время соединения, кнопки «Отключить» и «Открыть».
 *
 * Только AWT/Swing, без Compose: одна и та же плашка работает и в обычной Hydra, и в Classic (32-бит, Windows 7).
 * Левый щелчок по значку открывает плашку, правый — меню; о подключении, обрыве и отключении сообщает
 * всплывающее уведомление ОС. Все обращения к окнам идут из потока Swing.
 */
class TrayController(
    private val controller: AppController,
    private val scope: CoroutineScope,
    private val image: Image,
    private val onOpen: () -> Unit,
    private val onQuit: () -> Unit,
) {
    private var icon: TrayIcon? = null
    /** Ресурс текущей картинки значка: выбор «Иконка» в настройках меняет её на лету (0.7.9). */
    private var iconRes: String? = null
    private var plaque: Plaque? = null
    private var job: Job? = null
    private var connectedSince = 0L
    private var last: UiState? = null
    private lateinit var toggleItem: MenuItem

    /** false — трея нет (GNOME без расширения, голый X11): окно тогда закрывается как обычно. */
    fun install(): Boolean {
        if (!runCatching { SystemTray.isSupported() }.getOrDefault(false)) return false
        val menu = PopupMenu()
        MenuItem("Открыть Hydra").also { it.addActionListener { onOpen() }; menu.add(it) }
        toggleItem = MenuItem("Подключить").also { it.addActionListener { toggle() }; menu.add(it) }
        if (controller.elevationAvailable && !controller.isElevated) {
            MenuItem("Перезапустить от администратора").also { it.addActionListener { controller.relaunchElevated() }; menu.add(it) }
        }
        menu.addSeparator()
        MenuItem("Выход").also { it.addActionListener { onQuit() }; menu.add(it) }
        val ti = TrayIcon(image, "Hydra", menu).apply { isImageAutoSize = true }
        ti.addMouseListener(object : MouseAdapter() {
            // Левая кнопка — плашка; правая отдаётся системному меню.
            override fun mouseReleased(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) showPlaque(e.locationOnScreen)
            }
        })
        // Двойной щелчок на Windows приходит и как действие — открывает главное окно.
        ti.addActionListener { plaque?.hide(); onOpen() }
        val ok = runCatching { SystemTray.getSystemTray().add(ti) }.isSuccess
        if (!ok) return false
        icon = ti
        job = scope.launch(Dispatchers.Swing) { controller.ui.collect { render(it) } }
        return true
    }

    private fun loadIcon(res: String): Image? = runCatching {
        AppController::class.java.getResourceAsStream(res)?.use { javax.imageio.ImageIO.read(it) }
    }.getOrNull()

    fun remove() {
        job?.cancel()
        plaque?.hide()
        icon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        icon = null
    }

    private fun toggle() {
        val ui = controller.ui.value
        if (ui.blocked && !ui.active) controller.disconnect() else controller.toggle()
    }

    private fun render(ui: UiState) {
        val prev = last
        last = ui
        val res = ui.data.settings.appIcon.resource(ui.data.settings.theme)
        if (res != iconRes) {
            iconRes = res
            loadIcon(res)?.let { img -> icon?.image = img }
        }
        if (ui.status == Status.CONNECTED && prev?.status != Status.CONNECTED) connectedSince = System.currentTimeMillis()
        icon?.toolTip = tooltip(ui)
        toggleItem.label = if (ui.active || ui.blocked) "Отключить" else "Подключить"
        plaque?.update(ui, connectedSince)
        if (prev != null && prev.status != ui.status) notifyChange(prev, ui)
    }

    /** Всплывающее уведомление ОС: подключилось, оборвалось, отключилось. */
    private fun notifyChange(prev: UiState, ui: UiState) {
        val ti = icon ?: return
        when (ui.status) {
            Status.CONNECTED -> ti.displayMessage("Hydra — подключено", serverLine(ui) ?: "Туннель зашифрован", TrayIcon.MessageType.INFO)
            Status.ERROR -> ti.displayMessage("Hydra — ошибка", ui.statusText, TrayIcon.MessageType.ERROR)
            Status.DISCONNECTED -> if (prev.status == Status.CONNECTED || prev.status == Status.STOPPING)
                ti.displayMessage("Hydra — отключено", "Туннель остановлен", TrayIcon.MessageType.NONE)
            else -> {}
        }
    }

    private fun showPlaque(at: Point) {
        val p = plaque ?: Plaque(onToggle = { toggle() }, onOpen = { onOpen() }).also { plaque = it }
        if (p.isShowing) { p.hide(); return }
        p.update(controller.ui.value, connectedSince)
        p.showNear(at)
    }

    companion object {
        /** Подсказка значка: ограничена ~63 символами (Windows). */
        fun tooltip(ui: UiState): String {
            val base = "Hydra — " + ui.statusText
            val t = if (ui.status == Status.CONNECTED) "$base · ↓${speed(ui.downSpeed)} ↑${speed(ui.upSpeed)}" else base
            return if (t.length <= 63) t else t.take(62) + "…"
        }

        fun serverLine(ui: UiState): String? {
            val s = (ui.connectedId?.let { id -> ui.data.servers.firstOrNull { it.id == id } } ?: ui.selected) ?: return null
            val engine = ui.engine?.let { engineLabel(it) }
            return "${s.name}" + (engine?.let { " · $it" } ?: "")
        }

        fun engineLabel(kind: ru.gidravpn.hydra.data.model.EngineToggles.Kind) = when (kind) {
            ru.gidravpn.hydra.data.model.EngineToggles.Kind.XRAY -> "Xray"
            ru.gidravpn.hydra.data.model.EngineToggles.Kind.SINGBOX -> "sing-box"
            ru.gidravpn.hydra.data.model.EngineToggles.Kind.OLCRTC -> "olcRTC"
            ru.gidravpn.hydra.data.model.EngineToggles.Kind.OPENFLUX -> "OpenFlux"
            else -> kind.name
        }

        fun bytes(b: Long): String = when {
            b >= 1L shl 30 -> "%.2f ГБ".format(b / (1L shl 30).toDouble())
            b >= 1L shl 20 -> "%.1f МБ".format(b / (1L shl 20).toDouble())
            b >= 1L shl 10 -> "%.0f КБ".format(b / 1024.0)
            else -> "$b Б"
        }

        fun speed(b: Long) = bytes(b) + "/с"

        /** 3725 с → «1:02:05». */
        fun duration(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        }
    }
}

/** Окно-плашка у значка трея: тёмная карточка без рамки, закрывается при потере фокуса или по Esc. */
private class Plaque(private val onToggle: () -> Unit, private val onOpen: () -> Unit) {
    private val bg = Color(0x15, 0x1B, 0x1F)
    private val fg = Color(0xE3, 0xE8, 0xEA)
    private val dim = Color(0x9F, 0xB0, 0xB6)
    private val window = JWindow().apply { isAlwaysOnTop = true; focusableWindowState = true }

    private val dot = JLabel("●").apply { font = font.deriveFont(Font.BOLD, 16f) }
    private val title = label(bold = true, size = 14f)
    private val server = label(color = fg)
    private val traffic = label(color = fg)
    private val totals = label(color = dim, size = 11f)
    private val check = label(color = dim, size = 11f)
    private val toggle = JButton().apply { addActionListener { this@Plaque.hide(); onToggle() } }
    private val open = JButton("Открыть").apply { addActionListener { this@Plaque.hide(); onOpen() } }

    val isShowing get() = window.isVisible

    init {
        val root = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(0x2A, 0x36, 0x3C)),
                BorderFactory.createEmptyBorder(12, 14, 12, 14),
            )
        }
        val head = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false; add(dot); add(title) }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false; add(toggle); add(open) }
        listOf(head, server, traffic, totals, check, javax.swing.Box.createVerticalStrut(8), buttons).forEach {
            (it as? javax.swing.JComponent)?.alignmentX = 0f
            root.add(it)
        }
        window.contentPane = root
        window.addWindowFocusListener(object : WindowAdapter() {
            override fun windowLostFocus(e: WindowEvent?) = hide()
        })
        val esc = object : KeyAdapter() { override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ESCAPE) hide() } }
        window.addKeyListener(esc); toggle.addKeyListener(esc); open.addKeyListener(esc)
    }

    private fun label(color: Color = Color(0xE3, 0xE8, 0xEA), bold: Boolean = false, size: Float = 12f) = JLabel(" ").apply {
        foreground = color
        font = font.deriveFont(if (bold) Font.BOLD else Font.PLAIN, size)
        border = BorderFactory.createEmptyBorder(2, 0, 0, 0)
    }

    fun update(ui: UiState, connectedSince: Long) {
        dot.foreground = when (ui.status) {
            Status.CONNECTED -> if (ui.delayMs != null) Color(0x2E, 0xC4, 0xB6) else Color(0xF5, 0xA5, 0x24)
            Status.CONNECTING, Status.STOPPING -> Color(0xF5, 0xA5, 0x24)
            Status.ERROR -> Color(0xE5, 0x48, 0x4D)
            Status.DISCONNECTED -> dim
        }
        title.foreground = fg
        title.text = ui.statusText
        server.text = TrayController.serverLine(ui) ?: "Сервер не выбран"
        val live = ui.status == Status.CONNECTED
        traffic.isVisible = live; totals.isVisible = live; check.isVisible = live
        if (live) {
            traffic.text = "↓ ${TrayController.speed(ui.downSpeed)}    ↑ ${TrayController.speed(ui.upSpeed)}"
            totals.text = "Всего: ↓ ${TrayController.bytes(ui.downTotal)}   ↑ ${TrayController.bytes(ui.upTotal)}"
            val time = if (connectedSince > 0) "Соединение: " + TrayController.duration(System.currentTimeMillis() - connectedSince) else ""
            val ping = ui.delayMs?.let { "Проверка: $it мс" } ?: ""
            check.text = listOf(time, ping).filter { it.isNotEmpty() }.joinToString("   ").ifEmpty { " " }
        }
        toggle.text = if (ui.active || ui.blocked) "Отключить" else "Подключить"
        toggle.isEnabled = ui.status != Status.STOPPING
        window.pack()
        window.size = Dimension(maxOf(window.width, 300), window.height)
    }

    fun showNear(click: Point) {
        val gc = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration }.firstOrNull { it.bounds.contains(click) }
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val b = gc.bounds
        val ins = Toolkit.getDefaultToolkit().getScreenInsets(gc)
        val area = java.awt.Rectangle(b.x + ins.left, b.y + ins.top, b.width - ins.left - ins.right, b.height - ins.top - ins.bottom)
        // Трей обычно у нижнего или верхнего края: плашка встаёт у края рабочей области, ближе к значку.
        val x = (click.x - window.width / 2).coerceIn(area.x + 8, area.x + area.width - window.width - 8)
        val y = if (click.y > area.y + area.height / 2) area.y + area.height - window.height - 8 else area.y + 8
        window.location = Point(x, y)
        window.isVisible = true
        window.toFront()
        SwingUtilities.invokeLater { window.requestFocus() }
    }

    fun hide() { window.isVisible = false }
}
