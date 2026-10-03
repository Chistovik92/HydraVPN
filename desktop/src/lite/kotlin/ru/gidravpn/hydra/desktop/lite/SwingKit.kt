package ru.gidravpn.hydra.desktop.lite

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.Toolkit
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Мелочи для интерфейса Classic. Всё построено на «родных» компонентах Swing без внешних библиотек —
 * Hydra Classic должна запускаться на Java 11 в 32-битной Windows 7 и на Linux armv7.
 */

internal val Accent = Color(0x1F, 0x9E, 0x92)
internal val Danger = Color(0xC6, 0x28, 0x28)
internal val Warn = Color(0xB7, 0x79, 0x1F)
internal val Muted = Color(0x66, 0x70, 0x75)

/** Вертикальный стек компонентов, прижатых влево. */
internal fun column(vararg parts: Component, gap: Int = 6): JPanel = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    parts.forEachIndexed { i, p ->
        (p as? JComponent)?.alignmentX = Component.LEFT_ALIGNMENT
        add(p)
        if (i < parts.lastIndex) add(Box.createVerticalStrut(gap))
    }
}

/** Горизонтальный ряд, прижатый влево. */
internal fun row(vararg parts: Component, gap: Int = 8): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, gap, 0)).apply {
    parts.forEach { add(it) }
    alignmentX = Component.LEFT_ALIGNMENT
}

/** Рамка с заголовком — раздел настроек. */
internal fun section(title: String, vararg parts: Component): JPanel = column(*parts).apply {
    border = BorderFactory.createCompoundBorder(
        BorderFactory.createTitledBorder(title),
        BorderFactory.createEmptyBorder(4, 8, 8, 8),
    )
    alignmentX = Component.LEFT_ALIGNMENT
}

/** Пояснение мелким серым текстом; переносится по словам. */
internal fun note(text: String, color: Color = Muted): JTextArea = JTextArea(text).apply {
    isEditable = false; isFocusable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
    foreground = color
    font = (javax.swing.UIManager.getFont("Label.font") ?: font).deriveFont(Font.PLAIN, 11.5f)
    border = BorderFactory.createEmptyBorder()
    alignmentX = Component.LEFT_ALIGNMENT
}

internal fun bold(text: String, size: Float = 13f) = JLabel(text).apply { font = font.deriveFont(Font.BOLD, size) }

internal fun button(text: String, onClick: () -> Unit) = JButton(text).apply { addActionListener { onClick() } }

/** Прокручиваемая страница из секций: на небольших экранах (1024×600, ТВ-приставки) всё остаётся доступным. */
internal fun page(vararg parts: Component): JScrollPane {
    val content = column(*parts, gap = 10).apply { border = BorderFactory.createEmptyBorder(12, 14, 12, 14) }
    // Ширина — по окну: BorderLayout.NORTH не растягивает по вертикали, а по горизонтали — да.
    val holder = JPanel(BorderLayout()).apply { add(content, BorderLayout.NORTH) }
    return JScrollPane(holder).apply {
        border = BorderFactory.createEmptyBorder()
        verticalScrollBar.unitIncrement = 16
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }
}

internal fun clipboard(): String = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
}.getOrDefault("")

internal fun setClipboard(text: String) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}

internal fun openUrl(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}

internal fun bytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f ГБ".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f МБ".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f КБ".format(b / 1024.0)
    else -> "$b Б"
}

internal fun speed(b: Long) = bytes(b) + "/с"

internal fun dateOf(ms: Long, time: Boolean = false): String =
    java.text.SimpleDateFormat(if (time) "dd.MM.yyyy HH:mm" else "dd.MM.yyyy").format(java.util.Date(ms))

/** Защита от петли «обновили поле из состояния → сработал слушатель → записал состояние». */
internal class Quiet {
    var on = false
    inline fun run(block: () -> Unit) { val prev = on; on = true; try { block() } finally { on = prev } }
}

/** Переключатель, связанный с настройкой: [render] ставит значение из состояния, щелчок вызывает [onChange]. */
internal class Check(text: String, private val quiet: Quiet, private val onChange: (Boolean) -> Unit) : JCheckBox(text) {
    init { addActionListener { if (!quiet.on) onChange(isSelected) } }
    fun set(value: Boolean, enabled: Boolean = true) = quiet.run { isSelected = value; isEnabled = enabled }
}

internal class Radio(text: String, private val quiet: Quiet, private val onPick: () -> Unit) : JRadioButton(text) {
    init { addActionListener { if (!quiet.on) onPick() } }
    fun set(selected: Boolean, enabled: Boolean = true) = quiet.run { isSelected = selected; isEnabled = enabled }
}

/** Текстовое поле: [onText] вызывается при каждом изменении пользователем, а не при программной подстановке. */
internal class Field(columns: Int, private val quiet: Quiet, private val onText: (String) -> Unit = {}) : JTextField(columns) {
    init {
        document.addDocumentListener(object : DocumentListener {
            private fun changed() { if (!quiet.on) onText(text) }
            override fun insertUpdate(e: DocumentEvent?) = changed()
            override fun removeUpdate(e: DocumentEvent?) = changed()
            override fun changedUpdate(e: DocumentEvent?) = changed()
        })
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    /** Не трогаем поле, пока в нём курсор: пользователь печатает, состояние эхом вернулось бы с задержкой. */
    fun sync(value: String) { if (!isFocusOwner && text != value) quiet.run { text = value } }
}

/** Подтверждение необратимого удаления (по умолчанию выбрана «Отмена»). */
internal fun confirmDelete(text: String): Boolean {
    val options = arrayOf<Any>("Удалить", "Отмена")
    return javax.swing.JOptionPane.showOptionDialog(
        null, text, "Hydra", javax.swing.JOptionPane.DEFAULT_OPTION,
        javax.swing.JOptionPane.WARNING_MESSAGE, null, options, options[1],
    ) == 0
}
