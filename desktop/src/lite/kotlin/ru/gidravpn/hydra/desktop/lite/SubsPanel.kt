package ru.gidravpn.hydra.desktop.lite

import ru.gidravpn.hydra.data.model.Subscription
import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.UiState
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

/** Подписки: адрес выдаёт панель (Remnawave, Marzban, 3x-ui и др.). */
internal class SubsPanel(private val c: AppController) : JPanel(BorderLayout()), Renderable {
    private val quiet = Quiet()
    private val model = DefaultListModel<Subscription>()
    private val list = JList(model)
    private val url = Field(30, quiet)
    private val name = Field(14, quiet)
    private val refreshOne = button("Обновить") { chosen()?.let { c.refreshSubscription(it.id) } }
    private val refreshAll = button("Обновить все") { c.refreshAll() }
    private val delete = button("Удалить") {
        chosen()?.let { s -> if (confirmDelete("Удалить подписку «${s.displayName}» вместе с её серверами?")) c.deleteSubscription(s.id) }
    }
    private val auto = Check("Обновлять автоматически (раз в 12 ч)", quiet) { on -> chosen()?.let { c.setSubscriptionAutoUpdate(it.id, if (on) 12 else 0) } }
    private var last: UiState? = null
    private var ui: UiState? = null

    private fun chosen(): Subscription? = list.selectedValue

    init {
        border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = ListCellRenderer<Subscription> { _, s, _, isSel, _ -> cell(s, isSel) }
        list.addListSelectionListener { updateButtons() }
        val addBtn = button("Добавить") { c.addSubscription(url.text, name.text); url.text = ""; name.text = "" }
        url.addActionListener { addBtn.doClick() }
        add(
            column(
                row(JLabel("Адрес подписки (https://…):"), url),
                row(JLabel("Название:"), name, addBtn),
                gap = 4,
            ),
            BorderLayout.NORTH,
        )
        add(JScrollPane(list), BorderLayout.CENTER)
        add(column(auto, row(refreshOne, refreshAll, delete), gap = 2), BorderLayout.SOUTH)
    }

    private fun cell(s: Subscription, isSel: Boolean): Component {
        val count = ui?.data?.servers?.count { it.subscriptionId == s.id } ?: 0
        val parts = buildList {
            add("серверов: $count")
            if (s.totalBytes > 0) add("трафик ${bytes(s.usedBytes)} из ${bytes(s.totalBytes)}")
            else if (s.usedBytes > 0) add("трафик ${bytes(s.usedBytes)}")
            if (s.expireAt > 0) add("до ${dateOf(s.expireAt * 1000)}")
            if (s.lastUpdated > 0) add("обновлена ${dateOf(s.lastUpdated, time = true)}")
            add(if (s.autoUpdateHours > 0) "автообновление: ${s.autoUpdateHours} ч" else "без автообновления")
        }
        val busy = ui?.updatingSubs?.contains(s.id) == true
        val err = if (s.lastError.isNotEmpty()) "<br><font color='#C62828' size='-1'>Ошибка: ${esc(s.lastError)}</font>" else ""
        return JLabel("<html><b>${esc(s.displayName)}</b>${if (busy) "  <i>обновляется…</i>" else ""}" +
            "<br><font color='#667075' size='-1'>${esc(parts.joinToString(" · "))}</font>$err</html>").apply {
            isOpaque = true
            border = BorderFactory.createEmptyBorder(3, 6, 3, 6)
            background = if (isSel) list.selectionBackground else list.background
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun updateButtons() {
        val s = chosen()
        refreshAll.isEnabled = model.size() > 0
        refreshOne.isEnabled = s != null && ui?.updatingSubs?.contains(s.id) != true
        delete.isEnabled = s != null && ui?.active != true
        auto.set(s != null && s.autoUpdateHours > 0, s != null)
    }

    override fun render(ui: UiState) {
        this.ui = ui
        val prev = last
        if (prev == null || prev.data.subscriptions != ui.data.subscriptions || prev.data.servers.size != ui.data.servers.size ||
            prev.updatingSubs != ui.updatingSubs) {
            val keep = chosen()?.id
            model.clear()
            ui.data.subscriptions.forEach { model.addElement(it) }
            keep?.let { id -> ui.data.subscriptions.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { list.selectedIndex = it } }
        }
        last = ui
        updateButtons()
    }
}
