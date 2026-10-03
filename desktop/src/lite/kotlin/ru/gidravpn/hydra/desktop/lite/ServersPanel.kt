package ru.gidravpn.hydra.desktop.lite

import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.UiState
import ru.gidravpn.hydra.desktop.core.DesktopConfig
import ru.gidravpn.hydra.desktop.tray.TrayController
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

/** Серверы: список (с группировкой по подписке), вставка из буфера, пинг, выбор, ссылка, удаление. */
internal class ServersPanel(private val c: AppController) : JPanel(BorderLayout()), Renderable {
    private val quiet = Quiet()
    private val model = DefaultListModel<ServerProfile>()
    private val list = JList(model)
    private val input = Field(40, quiet)
    private val ping = button("Пинг всех") { c.pingAll() }
    private val select = button("Выбрать") { chosen()?.let { c.select(it.id) } }
    private val share = button("Скопировать ссылку") {
        chosen()?.let { s ->
            val link = c.shareLink(s.id)
            if (link == null) c.toast("Для этого протокола нет формата ссылки")
            else {
                setClipboard(link)
                c.toast("Ссылка на «${s.name}» скопирована в буфер обмена.\nВ ней пароль сервера — передавайте только тем, кому доверяете.")
            }
        }
    }
    private val delete = button("Удалить") { chosen()?.let { c.deleteServer(it.id) } }
    private var last: UiState? = null
    private var subNames: Map<Long, String> = emptyMap()
    private var selectedId: Long? = null
    private var active = false
    private var ui: UiState? = null

    private fun chosen(): ServerProfile? = list.selectedValue

    init {
        border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = ListCellRenderer<ServerProfile> { _, s, _, isSel, _ -> cell(s, isSel) }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) chosen()?.let { if (!active) c.select(it.id) } }
        })
        list.addListSelectionListener { updateButtons() }

        val addBtn = button("Добавить") { c.import(input.text); input.text = "" }
        input.addActionListener { addBtn.doClick() }
        val top = column(
            row(button("Вставить из буфера") { c.import(clipboard()) }, ping),
            row(javax.swing.JLabel("Ссылка или адрес подписки:"), input, addBtn),
            gap = 4,
        )
        add(top, BorderLayout.NORTH)
        add(JScrollPane(list), BorderLayout.CENTER)
        add(row(select, share, delete), BorderLayout.SOUTH)
    }

    private fun cell(s: ServerProfile, isSel: Boolean): Component {
        val ui = ui
        val supported = DesktopConfig.isSupported(s)
        val engine = ui?.let { DesktopConfig.engineFor(s, it.data.settings, c.xrayAvailable) }
        val picked = s.id == selectedId
        val pingText = when {
            s.pingMs == -2 -> "нет ответа"
            s.pingMs >= 0 -> "${s.pingMs} мс"
            else -> ""
        }
        val group = s.subscriptionId?.let { subNames[it] ?: "Подписка" } ?: "Добавлены вручную"
        val details = (s.protocol?.displayName ?: s.protocolId) + " · " + s.address + ":" + s.port + " · " +
            (if (!supported) "на ПК недоступно" else engine?.let { TrayController.engineLabel(it) } ?: "ядро выключено") + " · " + group
        return JLabel("<html>${if (picked) "● " else ""}<b>${esc("${s.flag} ${s.name}")}</b>" +
            (if (pingText.isNotEmpty()) "  <font color='#1F9E92'>$pingText</font>" else "") +
            "<br><font color='#667075' size='-1'>${esc(details)}</font></html>").apply {
            isOpaque = true
            border = BorderFactory.createEmptyBorder(3, 6, 3, 6)
            background = if (isSel) list.selectionBackground else list.background
            foreground = if (supported) list.foreground else Muted
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun updateButtons() {
        val s = chosen()
        select.isEnabled = s != null && !active && DesktopConfig.isSupported(s)
        share.isEnabled = s != null
        delete.isEnabled = s != null && !(active && ui?.connectedId == s.id)
    }

    override fun render(ui: UiState) {
        this.ui = ui
        active = ui.active
        selectedId = ui.data.settings.selectedServerId
        subNames = ui.data.subscriptions.associate { it.id to it.displayName }
        ping.isEnabled = !ui.pinging && ui.data.servers.isNotEmpty()
        ping.text = if (ui.pinging) "Пинг…" else "Пинг всех"

        // Порядок как в обычной Hydra: по группам подписок. Перестраиваем модель, только если что-то поменялось.
        val servers = ui.data.servers.groupBy { it.subscriptionId }.values.flatten()
        val prev = last
        if (prev == null || prev.data.servers != ui.data.servers || prev.data.subscriptions != ui.data.subscriptions ||
            prev.data.settings.selectedServerId != selectedId || prev.active != ui.active) {
            val keep = chosen()?.id
            model.clear()
            servers.forEach { model.addElement(it) }
            keep?.let { id -> servers.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { list.selectedIndex = it } }
        }
        last = ui
        updateButtons()
    }
}
