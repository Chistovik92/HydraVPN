package ru.gidravpn.hydra.desktop.lite

import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.UiState
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

/** Журнал ядра. */
internal class LogPanel(private val c: AppController) : JPanel(BorderLayout()), Renderable {
    private val area = JTextArea().apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 12); lineWrap = false }
    private val copy = button("Копировать") { setClipboard(area.text) }
    private val clear = button("Очистить") { c.clearLog() }
    private var shown = 0

    init {
        border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
        add(row(copy, clear), BorderLayout.NORTH)
        add(JScrollPane(area), BorderLayout.CENTER)
    }

    override fun render(ui: UiState) {
        copy.isEnabled = ui.log.isNotEmpty(); clear.isEnabled = ui.log.isNotEmpty()
        // Журнал только растёт (или очищается целиком): дописываем новое, а не перерисовываем всё.
        if (ui.log.size < shown) { area.text = ""; shown = 0 }
        if (ui.log.size == shown) return
        ui.log.drop(shown).forEach { area.append(it + "\n") }
        shown = ui.log.size
        area.caretPosition = area.document.length
    }
}
