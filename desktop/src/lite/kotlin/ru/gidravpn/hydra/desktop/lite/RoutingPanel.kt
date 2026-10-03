package ru.gidravpn.hydra.desktop.lite

import ru.gidravpn.hydra.data.model.GeoRoutingMode
import ru.gidravpn.hydra.data.model.NetRuleType
import ru.gidravpn.hydra.data.model.NetworkRule
import ru.gidravpn.hydra.data.model.SplitTunnelMode
import ru.gidravpn.hydra.data.model.countryName
import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.Platform
import ru.gidravpn.hydra.desktop.RoutingProfile
import ru.gidravpn.hydra.desktop.UiState
import ru.gidravpn.hydra.desktop.core.Processes
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel

/**
 * Маршрутизация: по приложениям (процессам), по сайтам и IP, по странам, профили — то же, что экран «Маршруты»
 * в обычной Hydra. Правила сохраняются в тех же файлах, поэтому настройки переносятся между сборками.
 */
internal class RoutingPanel(private val c: AppController) : Renderable {
    private val quiet = Quiet()
    private val hint = note("", Warn)

    // ---- по приложениям
    private val appRadios = modeRadios("Все программы через VPN", "Только выбранные через VPN", "Выбранные — мимо VPN") { m -> c.setAppMode(m) }
    private val appModel = DefaultListModel<String>()
    private val appList = JList(appModel).apply { visibleRowCount = 5 }
    private val appInput = Field(26, quiet)
    private val appBox = column()
    private val appEmpty = note("Список пуст — правило не действует.", Warn)

    // ---- по сайтам и IP
    private val netRadios = modeRadios("Выключено", "Только эти — через VPN", "Эти — мимо VPN") { m -> c.setNetMode(m) }
    private val netModel = DefaultListModel<NetworkRule>()
    private val netList = JList(netModel).apply { visibleRowCount = 5 }
    private val netInput = Field(26, quiet)
    private val netTypes = listOf<Pair<NetRuleType?, String>>(
        null to "Авто", NetRuleType.DOMAIN_SUFFIX to "Домен + поддомены", NetRuleType.DOMAIN to "Точный домен",
        NetRuleType.DOMAIN_KEYWORD to "Слово в домене", NetRuleType.IP_CIDR to "IP / подсеть",
    )
    private val netType = JComboBox(netTypes.map { it.second }.toTypedArray())
    private val netBox = column()
    private val netEmpty = note("Правил нет — фильтр не действует.", Warn)

    // ---- по странам
    private val geoDir = Platform.geoDir()
    private val geoCodes: List<Pair<String, String>> = geoDir?.let { File(it, "geoip").list()?.filter { n -> n.endsWith(".srs") }?.map { n -> n.removeSuffix(".srs") } }
        .orEmpty().sorted().map { it to countryName(it) }
    private val geoDomains: Set<String> = geoDir?.let { File(it, "geosite").list()?.map { n -> n.removeSuffix(".srs") }?.toSet() }.orEmpty()
    private val geoRadios = listOf(
        Radio("Выключено", quiet) { c.updateRouting { it.copy(geoMode = GeoRoutingMode.OFF) } },
        Radio("Эти страны — мимо VPN", quiet) { c.updateRouting { it.copy(geoMode = GeoRoutingMode.DIRECT) } },
        Radio("Только эти страны — через VPN", quiet) { c.updateRouting { it.copy(geoMode = GeoRoutingMode.VIA_PROXY) } },
    )
    private val geoSearch = Field(20, quiet) { refreshGeo() }
    private val geoModel = DefaultListModel<String>()
    private val geoList = JList(geoModel).apply { visibleRowCount = 7 }
    private val geoChosen = note("")
    private val geoBox = column()
    private var uiNow: UiState? = null

    // ---- профили
    private val profModel = DefaultListModel<RoutingProfile>()
    private val profList = JList(profModel).apply { visibleRowCount = 4; selectionMode = ListSelectionModel.SINGLE_SELECTION }

    private val root: javax.swing.JScrollPane = page(
        hint,
        section("Профили маршрутизации",
            note("Снимок всех настроек маршрутизации (DNS, страны, MTU, фрагментация, IPv6, правила по программам и сайтам) — «Дом», «Работа», «Поездка»."),
            JScrollPane(profList).apply { alignmentX = Component.LEFT_ALIGNMENT; preferredSize = Dimension(300, 70) },
            row(
                button("Применить") { profList.selectedValue?.let { c.applyProfile(it.name) } },
                button("Удалить") { profList.selectedValue?.let { c.deleteProfile(it.name) } },
                button("Сохранить текущие настройки как профиль…") {
                    val n = JOptionPane.showInputDialog(root, "Название профиля (например, «Дом»):", "Профиль маршрутизации", JOptionPane.PLAIN_MESSAGE)
                    if (!n.isNullOrBlank()) c.saveProfile(n.trim().take(40))
                },
            ),
        ),
        section("По приложениям",
            note("Какие программы идут через VPN. Укажите имя процесса (chrome.exe, firefox) или полный путь. " +
                "В режиме «Системный прокси» правило действует только для программ, которые сами ходят через прокси; для всех программ выберите режим TUN."),
            *appRadios.first.toTypedArray(),
            appBox,
        ),
        section("По сайтам и IP-адресам",
            note("Домены (youtube.com — вместе с поддоменами), ключевые слова и IP/подсети. Работает в обоих режимах и с обоими движками."),
            *netRadios.first.toTypedArray(),
            netBox,
        ),
        section("По странам (geoip/geosite)",
            *(if (geoDir == null) arrayOf<Component>(note("Базы geo не найдены в пакете — функция недоступна.", Warn))
            else arrayOf<Component>(*geoRadios.toTypedArray(), geoBox)),
        ),
    )
    val component: Component get() = root

    init {
        ButtonGroup().also { g -> geoRadios.forEach { g.add(it) } }
        // приложения
        appBox.add(row(appInput, button("Добавить") { c.addApps(listOf(appInput.text)); appInput.text = "" },
            button("Выбрать из запущенных…") { pickProcess() }))
        appBox.add(JScrollPane(appList).apply { alignmentX = Component.LEFT_ALIGNMENT; preferredSize = Dimension(300, 90) })
        appBox.add(row(button("Убрать выбранное") { appList.selectedValuesList.forEach { c.removeApp(it) } }))
        appBox.add(appEmpty)
        // сайты и IP
        netBox.add(row(JLabel("Тип:"), netType))
        netBox.add(row(netInput, button("Добавить") {
            val type = netTypes[netType.selectedIndex].first
            val parts = netInput.text.split(',', ' ', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            val failed = parts.filterNot { c.addNetRule(type, it) }
            netInput.text = failed.joinToString(", ")
        }))
        netBox.add(JScrollPane(netList).apply { alignmentX = Component.LEFT_ALIGNMENT; preferredSize = Dimension(300, 90) })
        netBox.add(row(button("Убрать выбранное") { netList.selectedValuesList.forEach { c.removeNetRule(it) } }))
        netBox.add(netEmpty)
        netList.cellRenderer = javax.swing.ListCellRenderer { _, r, _, sel, _ ->
            val label = netTypes.firstOrNull { it.first == r.type }?.second ?: r.type.name
            JLabel("${r.value}   ($label)").apply {
                isOpaque = true
                background = if (sel) netList.selectionBackground else netList.background
            }
        }
        // страны
        geoBox.add(geoChosen)
        geoBox.add(note("По доменам (geosite), кроме IP: ${geoDomains.sorted().joinToString(", ") { countryName(it) }}; для остальных стран — только IP-адреса."))
        geoBox.add(row(JLabel("Найти страну:"), geoSearch))
        geoBox.add(JScrollPane(geoList).apply { alignmentX = Component.LEFT_ALIGNMENT; preferredSize = Dimension(300, 130) })
        geoList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val i = geoList.locationToIndex(e.point).takeIf { it >= 0 } ?: return
                val code = geoModel.getElementAt(i).substringAfterLast('[').substringBefore(']').lowercase()
                if (code.isNotEmpty()) c.toggleGeoCountry(code)
            }
        })
        profList.cellRenderer = javax.swing.ListCellRenderer { _, p, _, sel, _ ->
            JLabel(p.name).apply { isOpaque = true; background = if (sel) profList.selectionBackground else profList.background }
        }
    }

    private fun refreshGeo() {
        val ui = uiNow ?: return
        val r = ui.data.settings.routing
        val q = geoSearch.text.trim()
        val shown = if (q.isEmpty()) geoCodes.filter { it.first in r.geoCountries }
        else geoCodes.filter { (code, name) -> name.contains(q, true) || code.equals(q, true) }.sortedBy { it.second }.take(40)
        geoModel.clear()
        shown.forEach { (code, name) ->
            geoModel.addElement((if (code in r.geoCountries) "☑ " else "☐ ") + name + (if (code in geoDomains) " — IP+домены" else " — IP") + "  [" + code.uppercase() + "]")
        }
    }

    private fun pickProcess() {
        val all = Processes.running()
        val model = DefaultListModel<Processes.Proc>().apply { all.distinctBy { it.name.lowercase() }.forEach { addElement(it) } }
        val list = JList(model).apply {
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            cellRenderer = javax.swing.ListCellRenderer { _, p, _, sel, _ ->
                JLabel("<html><b>${p.name}</b> <font color='#667075' size='-1'>${p.path}</font></html>").apply {
                    isOpaque = true; background = if (sel) selectionBackground else java.awt.Color.WHITE
                }
            }
        }
        if (all.isEmpty()) { JOptionPane.showMessageDialog(root, "Список процессов недоступен — впишите имя вручную."); return }
        val ok = JOptionPane.showConfirmDialog(root, JScrollPane(list).apply { preferredSize = Dimension(520, 320) },
            "Запущенные программы", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (ok == JOptionPane.OK_OPTION) c.addApps(list.selectedValuesList.map { it.name })
    }

    /** Три переключателя режима [SplitTunnelMode]; возвращает кнопки и функцию установки. */
    private fun modeRadios(off: String, include: String, exclude: String, onPick: (SplitTunnelMode) -> Unit): Pair<List<Radio>, List<SplitTunnelMode>> {
        val modes = listOf(SplitTunnelMode.OFF, SplitTunnelMode.INCLUDE, SplitTunnelMode.EXCLUDE)
        val radios = listOf(off, include, exclude).mapIndexed { i, label -> Radio(label, quiet) { onPick(modes[i]) } }
        ButtonGroup().also { g -> radios.forEach { g.add(it) } }
        return radios to modes
    }

    override fun render(ui: UiState) {
        uiNow = ui
        val s = ui.data.settings
        val r = s.routing
        hint.text = if (ui.active) "Изменения применятся при следующем подключении." else ""
        hint.isVisible = ui.active

        appRadios.second.forEachIndexed { i, m -> appRadios.first[i].set(r.appMode == m) }
        appBox.isVisible = r.appMode != SplitTunnelMode.OFF
        appEmpty.isVisible = r.apps.isEmpty()
        fill(appModel, r.apps)
        netRadios.second.forEachIndexed { i, m -> netRadios.first[i].set(r.netMode == m) }
        netBox.isVisible = r.netMode != SplitTunnelMode.OFF
        netEmpty.isVisible = r.netRules.isEmpty()
        fill(netModel, r.netRules)
        fill(profModel, ui.data.profiles)

        val modes = listOf(GeoRoutingMode.OFF, GeoRoutingMode.DIRECT, GeoRoutingMode.VIA_PROXY)
        geoRadios.forEachIndexed { i, rb -> rb.set(r.geoMode == modes[i]) }
        geoBox.isVisible = r.geoMode != GeoRoutingMode.OFF
        geoChosen.text = if (r.geoCountries.isEmpty()) "Страны не выбраны" else r.geoCountries.sorted().joinToString(", ") { countryName(it) }
        geoChosen.foreground = if (r.geoCountries.isEmpty()) Danger else Accent
        refreshGeo()
    }

    private fun <T> fill(model: DefaultListModel<T>, items: List<T>) {
        if (model.size() == items.size && items.indices.all { model.getElementAt(it) == items[it] }) return
        model.clear(); items.forEach { model.addElement(it) }
    }
}
