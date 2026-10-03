package ru.gidravpn.hydra.desktop.lite

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.Platform
import ru.gidravpn.hydra.desktop.UiState
import java.awt.Dimension
import java.awt.Image
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * Главное окно Hydra Classic. Вкладки те же, что у обычной Hydra (кроме «Роутеров»); каждая перерисовывается
 * по состоянию [AppController.ui], так что поведение и данные общие — отличается только оболочка.
 */
internal class LiteWindow(
    private val c: AppController,
    scope: CoroutineScope,
    icon: Image?,
    onRelaunchAdmin: () -> Unit,
    private val onClose: () -> Unit,
) : JFrame("Hydra ${Platform.version}") {

    private val tabs = JTabbedPane()
    private val renderables = mutableListOf<Renderable>()
    private var showingMessage = false

    init {
        icon?.let { iconImage = it }
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent?) = onClose()
        })

        val home = HomePanel(c, onRelaunchAdmin) { tabs.selectedIndex = 1 }
        val servers = ServersPanel(c)
        val subs = SubsPanel(c)
        val routing = RoutingPanel(c)
        val settings = SettingsPanel(c)
        val log = LogPanel(c)
        fun tab(title: String, comp: java.awt.Component, r: Renderable) { tabs.addTab(title, comp); renderables += r }
        tab("Главная", home, home)
        tab("Серверы", servers, servers)
        tab("Подписки", subs, subs)
        tab("Маршруты", routing.component, routing)
        tab("Настройки", settings.component, settings)
        tab("Журнал", log, log)
        contentPane.add(tabs)

        // 820×560 влезает в экран 1024×600 (ТВ-приставки, нетбуки).
        minimumSize = Dimension(640, 460)
        size = Dimension(820, 560)
        setLocationRelativeTo(null)

        scope.launch(Dispatchers.Swing) { c.ui.collect { render(it) } }
        // Прокрутка страниц настроек — к началу (иначе открываются на последнем сфокусированном поле).
        SwingUtilities.invokeLater { scrollToTop(tabs) }
    }

    private fun render(ui: UiState) {
        renderables.forEach { it.render(ui) }
        title = "Hydra ${Platform.version} — ${ui.statusText}"
        val msg = ui.message
        if (msg != null && !showingMessage) {
            showingMessage = true
            c.toast(null)
            // Диалог — вне обработчика обновления состояния.
            SwingUtilities.invokeLater {
                JOptionPane.showMessageDialog(this, msg, "Hydra", JOptionPane.INFORMATION_MESSAGE)
                showingMessage = false
            }
        }
    }
}

private fun scrollToTop(c: java.awt.Container) {
    c.components.forEach {
        if (it is javax.swing.JScrollPane) it.verticalScrollBar.value = 0
        if (it is java.awt.Container) scrollToTop(it)
    }
}
