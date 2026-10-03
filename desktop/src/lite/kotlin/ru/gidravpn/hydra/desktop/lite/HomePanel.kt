package ru.gidravpn.hydra.desktop.lite

import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.ConnectionMode
import ru.gidravpn.hydra.desktop.Status
import ru.gidravpn.hydra.desktop.UiState
import ru.gidravpn.hydra.desktop.core.DesktopConfig
import ru.gidravpn.hydra.desktop.tray.TrayController
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar

/** Главная: большая кнопка, состояние, выбранный сервер, режим, скорость. */
internal class HomePanel(
    private val c: AppController,
    private val onRelaunchAdmin: () -> Unit,
    private val openServers: () -> Unit,
) : JPanel(), Renderable {
    private val quiet = Quiet()
    private fun centered(vararg parts: Component): JPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 8, 0)).apply { parts.forEach { add(it) }; alignmentX = Component.CENTER_ALIGNMENT }

    private val status = bold("", 20f)
    private val delay = JLabel(" ").apply { foreground = Accent }
    private val connect = JButton("Подключить").apply {
        font = font.deriveFont(Font.BOLD, 18f)
        preferredSize = Dimension(260, 56)
        maximumSize = preferredSize
        addActionListener { c.toggle() }
    }
    private val server = bold("")
    private val serverInfo = JLabel(" ").apply { foreground = Muted }
    private val change = button("Выбрать сервер…") { openServers() }
    private val proxy = Radio("Системный прокси", quiet) { c.updateSettings { it.copy(mode = ConnectionMode.PROXY) } }
    private val tun = Radio("TUN (весь трафик)", quiet) { c.updateSettings { it.copy(mode = ConnectionMode.TUN) } }
    private val down = bold("", 15f)
    private val up = bold("", 15f)
    private val totals = JLabel(" ").apply { foreground = Muted }
    private val unblock = button("Снять блокировку (kill switch)") { c.disconnect() }
    private val elev = section(
        "Нужны права администратора",
        note("Для режима TUN Hydra нужно запустить от имени администратора (так Windows разрешает создать сетевой адаптер)."),
        row(
            button("Перезапустить от администратора") { onRelaunchAdmin() },
            button("Подключить как прокси") { c.updateSettings { it.copy(mode = ConnectionMode.PROXY) }; c.connect() },
        ),
    )
    private val updateLabel = JLabel(" ").apply { foreground = Accent }
    private val updateButton = button("Обновить") { c.installUpdate() }
    private val updateNews = JButton("Что нового")
    private val updateProgress = JProgressBar(0, 100).apply { isStringPainted = true; maximumSize = Dimension(320, 18) }
    private val update = column(updateLabel, centered(updateButton, updateNews), updateProgress)
    private val account = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    init {
        border = BorderFactory.createEmptyBorder(18, 24, 18, 24)
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        val items: List<JComponent> = listOf(
            status, delay, connect, unblock, update, server, serverInfo, change, centered(proxy, tun), account, centered(down, up), totals, elev,
        )
        items.forEach {
            it.alignmentX = Component.CENTER_ALIGNMENT
            add(it)
            add(Box.createVerticalStrut(8))
        }
        ButtonGroup().apply { add(proxy); add(tun) }
    }

    override fun render(ui: UiState) {
        val s = ui.data.settings
        status.text = ui.statusText
        status.foreground = when (ui.status) {
            Status.ERROR -> Danger
            Status.CONNECTED -> if (ui.delayMs != null) Accent else Warn
            Status.CONNECTING, Status.STOPPING -> Warn
            Status.DISCONNECTED -> Color.DARK_GRAY
        }
        delay.text = ui.delayMs?.let { "Проверка через туннель: $it мс" } ?: " "
        connect.text = if (ui.active) "Отключить" else "Подключить"
        connect.isEnabled = ui.status != Status.STOPPING
        unblock.isVisible = ui.blocked

        val sel = ui.selected
        if (sel == null) {
            server.text = "Сервер не выбран"
            serverInfo.text = "Добавьте ссылку или подписку на вкладке «Серверы»"
        } else {
            server.text = "${sel.flag} ${sel.name}"
            val engine = DesktopConfig.engineFor(sel, s, c.xrayAvailable)
            serverInfo.text = sel.summary + " · " + (engine?.let { TrayController.engineLabel(it) } ?: "ядро выключено")
            serverInfo.foreground = if (engine == null) Danger else Muted
        }
        change.isEnabled = !ui.active
        proxy.set(s.mode == ConnectionMode.PROXY, !ui.active)
        tun.set(s.mode == ConnectionMode.TUN, !ui.active)

        down.isVisible = ui.active; up.isVisible = ui.active; totals.isVisible = ui.active
        down.text = "↓ ${speed(ui.downSpeed)}"
        up.text = "   ↑ ${speed(ui.upSpeed)}"
        totals.text = "Всего: ↓ ${bytes(ui.downTotal)}   ↑ ${bytes(ui.upTotal)}"
        elev.isVisible = ui.needsElevation

        val u = ui.update
        update.isVisible = u != null
        if (u != null) {
            val p = ui.updateProgress
            updateProgress.isVisible = p != null
            if (p != null) {
                updateProgress.value = (p * 100).toInt()
                updateLabel.text = "Загрузка Hydra ${u.version}…"
            } else {
                updateLabel.text = if (u.asset != null) "Доступна Hydra ${u.version}" else "Доступна Hydra ${u.version} — скачайте со страницы релиза"
            }
            updateButton.isVisible = u.asset != null && p == null
            updateNews.isVisible = p == null
            updateNews.actionListeners.forEach { updateNews.removeActionListener(it) }
            updateNews.addActionListener { openUrl(u.pageUrl) }
        }

        // Срок и трафик подписок, выданных ботом.
        val subs = ui.data.subscriptions.filter { it.botPanel.isNotEmpty() }
        account.removeAll()
        subs.forEach { sub ->
            val text = buildString {
                append(sub.displayName)
                if (sub.expireAt > 0) append(" · до ").append(dateOf(sub.expireAt * 1000))
                if (sub.totalBytes > 0) append(" · ").append(bytes(sub.usedBytes)).append(" / ").append(bytes(sub.totalBytes))
                else if (sub.usedBytes > 0) append(" · ").append(bytes(sub.usedBytes))
            }
            account.add(JLabel(text).apply {
                alignmentX = Component.CENTER_ALIGNMENT
                foreground = if (sub.expireAt > 0 && sub.expireAt * 1000 < System.currentTimeMillis()) Danger else Muted
            })
        }
        account.isVisible = subs.isNotEmpty()
        account.revalidate()
    }
}
