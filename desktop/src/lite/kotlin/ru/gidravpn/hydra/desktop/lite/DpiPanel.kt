package ru.gidravpn.hydra.desktop.lite

import ru.gidravpn.hydra.data.dpi.DpiProbe
import ru.gidravpn.hydra.data.dpi.DpiStrategies
import ru.gidravpn.hydra.data.geo.CustomGeoSource
import ru.gidravpn.hydra.data.geo.GeoKind
import ru.gidravpn.hydra.data.geo.GeoSources
import ru.gidravpn.hydra.data.model.Engine
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.routing.RouteGroup
import ru.gidravpn.hydra.data.routing.RouteKind
import ru.gidravpn.hydra.data.routing.RouteRule
import ru.gidravpn.hydra.data.routing.RouteTarget
import ru.gidravpn.hydra.data.routing.viaOf
import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.DpiProbeUi
import ru.gidravpn.hydra.desktop.Platform
import ru.gidravpn.hydra.desktop.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.awt.Component
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel

/**
 * Hydra Classic, вкладка «Обход DPI»: подбор обхода ByeDPI, правила «что → через какой выход», группы, цепочки и geo-базы
 * (0.7.13). Те же настройки и тот же код, что в обычной Hydra, — только интерфейс на Swing.
 */
internal class DpiPanel(private val c: AppController) : Renderable {
    private val quiet = Quiet()
    private var servers: List<ServerProfile> = emptyList()
    private var groups: List<RouteGroup> = emptyList()
    private var uiNow: UiState? = null

    private fun targetName(t: String): String = when (t) {
        RouteTarget.PROXY -> "VPN"
        RouteTarget.DIRECT -> "Напрямую"
        RouteTarget.DPI -> "Обход DPI"
        RouteTarget.TGWS -> "Telegram по WebSocket"
        RouteTarget.BLOCK -> "Блок"
        else -> RouteTarget.nodeId(t)?.let { id -> servers.firstOrNull { it.id == id }?.name } ?: groups.firstOrNull { it.tag == t }?.let { "◎ ${it.name}" } ?: t
    }

    private val kinds = RouteKind.entries.filter { it != RouteKind.APP }
    private val kindLabels = mapOf(
        RouteKind.PROCESS to "Программа", RouteKind.DOMAIN to "Домен", RouteKind.SUFFIX to "Суффикс домена", RouteKind.KEYWORD to "Ключевое слово",
        RouteKind.REGEX to "Regex", RouteKind.CIDR to "Диапазон IP", RouteKind.SRC_CIDR to "IP клиента", RouteKind.PORT to "Порт",
        RouteKind.PROTOCOL to "Протокол", RouteKind.NETWORK to "Сеть (tcp/udp)", RouteKind.GEOIP to "Страна (IP)", RouteKind.GEOSITE to "Страна (домены)",
    )
    private fun nodes() = servers.filter { it.protocol?.engine == Engine.SINGBOX || it.protocol?.engine == Engine.OPENFLUX || it.protocol?.engine == Engine.OLCRTC }
    private fun allTargets() = listOf(RouteTarget.PROXY, RouteTarget.DIRECT, RouteTarget.DPI, RouteTarget.TGWS, RouteTarget.BLOCK) + groups.map { it.tag } + nodes().map { RouteTarget.node(it.id) }

    // ---- мастер подбора
    private val wizGroups = linkedMapOf(
        "youtube" to JCheckBox("YouTube", true), "discord" to JCheckBox("Discord", true),
        "telegram" to JCheckBox("Telegram", true), "general" to JCheckBox("Общие", false),
    )
    private val wizSite = Field(24, quiet)
    private val wizFull = JCheckBox("Тщательно (все стратегии, дольше)")
    private val wizStatus = note("")
    private val wizBaseline = note("")
    private val resModel = DefaultListModel<DpiProbe.Result>()
    private val resList = JList(resModel).apply { visibleRowCount = 3; selectionMode = ListSelectionModel.SINGLE_SELECTION }
    private val wizWarn = note("ByeDPI (ciadpi) не вошёл в эту сборку — подбор недоступен.", Warn)

    // ---- обход DPI
    private val dpiOn = Check("Пускать трафик «напрямую» через ByeDPI", quiet) { v -> c.setDpi { it.copy(enabled = v) } }
    private val strategy = Field(40, quiet)
    private val presets = JComboBox(DpiStrategies.PRESETS.mapIndexed { i, s -> "${i + 1}. ${s.take(70)}" }.toTypedArray())

    // ---- правила
    private val ruleModel = DefaultListModel<RouteRule>()
    private val ruleList = JList(ruleModel).apply { visibleRowCount = 6 }
    private val ruleKind = JComboBox(kinds.map { kindLabels.getValue(it) }.toTypedArray())
    private val ruleValue = Field(22, quiet)
    private val ruleTarget = JComboBox<String>()
    private val ruleNot = JCheckBox("НЕ")
    private val ruleGroup = Field(8, quiet)

    // ---- группы
    private val groupModel = DefaultListModel<RouteGroup>()
    private val groupList = JList(groupModel).apply { visibleRowCount = 3 }
    private val groupName = Field(14, quiet)
    private val groupType = JComboBox(arrayOf("Авто (самый быстрый)", "Ручной"))
    private val memberModel = DefaultListModel<String>()
    private val memberList = JList(memberModel).apply { visibleRowCount = 5; selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION }

    // ---- цепочки
    private val chainServer = JComboBox<String>()
    private val chainVia = JComboBox<String>()
    private val chainInfo = note("")

    // ---- geo
    private val geoAuto = Check("Автообновление", quiet) { v -> c.setGeo { it.copy(autoUpdate = v) } }
    private val geoHours = JComboBox(arrayOf("6", "12", "24", "72", "168"))
    private val geoIp = JComboBox(GeoSources.ALL.map { it.title }.toTypedArray())
    private val geoSite = JComboBox(GeoSources.ALL.map { it.title }.toTypedArray())
    private val geoName = Field(16, quiet)
    private val geoExtraInfo = note("")
    private val geoEntries = DefaultListModel<String>()
    private val geoEntryList = JList(geoEntries).apply { visibleRowCount = 5 }
    private val customKind = JComboBox(arrayOf("geoip", "geosite"))
    private val customType = JComboBox(arrayOf(CustomGeoSource.TYPE_LIST, CustomGeoSource.TYPE_SRS, CustomGeoSource.TYPE_DAT))
    private val customName = Field(12, quiet)
    private val customUrl = Field(30, quiet)

    private val hint = note("", Warn)

    private val root: JScrollPane = page(
        hint,
        section("Подобрать обход за меня",
            note("Отметьте, что должно открываться. Hydra переберёт готовые стратегии ByeDPI на вашем подключении и покажет лучшие."),
            wizWarn,
            row(*wizGroups.values.toTypedArray()),
            row(JLabel("Свой сайт:"), wizSite),
            row(wizFull),
            row(button("Начать подбор") { startProbe() }, button("Остановить") { c.cancelProbe() }),
            wizStatus, wizBaseline,
            JScrollPane(resList).apply { alignmentX = Component.LEFT_ALIGNMENT; preferredSize = Dimension(300, 70) },
            row(button("Использовать выбранный") { resList.selectedValue?.let { c.useDpiStrategy(it.strategy) } },
                button("Сохранить профилем") { resList.selectedValue?.let { c.addByeDpiProfile(it.strategy) } }),
        ),
        section("Обход DPI для трафика напрямую",
            note("ByeDPI — локальный прокси: режет и подделывает первые пакеты соединения, чтобы DPI провайдера не узнал сайт. Это не VPN: ваш IP он не скрывает."),
            dpiOn,
            row(JLabel("Стратегия:"), strategy),
            row(button("Применить") { c.setDpi { it.copy(strategy = strategy.text.trim().ifBlank { DpiStrategies.DEFAULT }) } },
                button("Профиль «Обход DPI»") { c.addByeDpiProfile(uiNow?.data?.settings?.routing?.routes?.dpi?.strategy ?: DpiStrategies.DEFAULT) }),
            row(presets, button("Взять готовую") { c.setDpi { it.copy(strategy = DpiStrategies.PRESETS[presets.selectedIndex]) } }),
        ),
        section("Правила маршрутизации",
            note("Разные программы, сайты, адреса и страны — через разные выходы одновременно. Условия с одной меткой группы объединяются по «И»; «НЕ» инвертирует условие."),
            JScrollPane(ruleList).apply { alignmentX = Component.LEFT_ALIGNMENT; preferredSize = Dimension(300, 100) },
            row(button("Убрать выбранное") { ruleList.selectedValuesList.forEach { c.removeRouteRule(it) } }),
            row(JLabel("Условие:"), ruleKind, ruleValue),
            row(JLabel("Выход:"), ruleTarget, ruleNot, JLabel("метка «И»:"), ruleGroup),
            row(button("Добавить правило") { addRule() }, button("Заблокированное в РФ → обход DPI") { c.applyBlockedRuPreset() }, button("Telegram → WebSocket (вкл/выкл)") { c.toggleTgWsPreset() }),
        ),
        section("Группы выходов",
            note("Авто — самый быстрый живой выход с переключением при сбое; ручной — выбираете сами. Группа может быть целью правила."),
            JScrollPane(groupList).apply { alignmentX = Component.LEFT_ALIGNMENT; preferredSize = Dimension(300, 60) },
            row(button("Удалить выбранную") { groupList.selectedValuesList.forEach { c.removeRouteGroup(it) } }),
            row(JLabel("Название:"), groupName, groupType),
            JScrollPane(memberList).apply { alignmentX = Component.LEFT_ALIGNMENT; preferredSize = Dimension(300, 90) },
            row(button("Создать группу из выбранных") { addGroup() }),
        ),
        section("Цепочки «протокол внутри протокола»",
            note("Пускать соединение самого сервера через обход DPI или через другой сервер (только TCP: VLESS, VMess, Trojan, Shadowsocks). Глубина любая."),
            row(JLabel("Сервер:"), chainServer),
            row(JLabel("через:"), chainVia, button("Применить") { applyChain() }),
            chainInfo,
        ),
        section("Geo-базы",
            note("Списки стран и сайтов Hydra скачивает сама и заменяет атомарно. Вшитые списки — запасные: на первый запуск и офлайн."),
            row(geoAuto, JLabel("раз в"), geoHours, JLabel("ч")),
            row(JLabel("IP из:"), geoIp),
            row(JLabel("Домены из:"), geoSite),
            row(button("Обновить сейчас") { c.updateGeo(manual = true) }),
            row(JLabel("Набор:"), geoName, button("+ IP") { addExtra(false) }, button("+ домены") { addExtra(true) }),
            geoExtraInfo,
            note("Свой список: прямая ссылка https — текстовый список (CIDR/домены), готовый .srs или запись из .dat."),
            row(customKind, customType, JLabel("имя:"), customName),
            row(JLabel("URL:"), customUrl, button("Сохранить") { addCustom() }),
            JScrollPane(geoEntryList).apply { alignmentX = Component.LEFT_ALIGNMENT; preferredSize = Dimension(300, 90) },
            row(button("Откат выбранной") { selectedGeo()?.let { (k, n) -> c.rollbackGeo(k, n) } },
                button("Удалить выбранную") { selectedGeo()?.let { (k, n) -> c.removeGeo(k, n) } }),
        ),
    )
    val component: Component get() = root

    init {
        ruleList.cellRenderer = javax.swing.ListCellRenderer { _, r, _, sel, _ ->
            JLabel((if (r.invert) "НЕ " else "") + "${kindLabels[r.kind]}  ${r.value}" + (if (r.group.isNotBlank()) " [${r.group}]" else "") + "  →  ${targetName(r.target)}").apply {
                isOpaque = true; background = if (sel) ruleList.selectionBackground else ruleList.background
            }
        }
        groupList.cellRenderer = javax.swing.ListCellRenderer { _, g, _, sel, _ ->
            JLabel("◎ ${g.name} · ${if (g.type == RouteGroup.TYPE_URLTEST) "авто" else "ручной"}: " + g.members.joinToString { targetName(it) }).apply {
                isOpaque = true; background = if (sel) groupList.selectionBackground else groupList.background
            }
        }
        resList.cellRenderer = javax.swing.ListCellRenderer { _, r, _, sel, _ ->
            JLabel("<html><b>${r.ok} из ${r.total} (${r.percent}%)</b> " + r.groups.joinToString(" · ") { "${it.name} ${it.ok}/${it.total}" } + "<br><font size='-2' color='#667075'>${r.strategy.take(90)}</font></html>").apply {
                isOpaque = true; background = if (sel) resList.selectionBackground else resList.background
            }
        }
        geoHours.addActionListener { if (!quiet.on) c.setGeo { it.copy(intervalHours = (geoHours.selectedItem as String).toInt()) } }
        geoIp.addActionListener { if (!quiet.on) c.setGeo { it.copy(ipSource = GeoSources.ALL[geoIp.selectedIndex].id) } }
        geoSite.addActionListener { if (!quiet.on) c.setGeo { it.copy(siteSource = GeoSources.ALL[geoSite.selectedIndex].id) } }
        scopeLaunchProbeCollector()
    }

    private fun scopeLaunchProbeCollector() {
        // Состояние мастера обновляется потоком контроллера — подписываемся в потоке Swing.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Swing).launch { c.dpiProbe.collect { st -> renderProbe(st) } }
    }

    private fun renderProbe(st: DpiProbeUi) {
        wizStatus.text = when {
            st.error != null -> st.error
            st.running -> "Проверено ${st.done} из ${st.total}…"
            st.finished -> if ((st.results.firstOrNull()?.ok ?: 0) == 0) "Ничего не помогло. Попробуйте «Тщательно» или другое подключение." else "Готово. Выберите вариант и нажмите «Использовать»."
            else -> ""
        }
        wizBaseline.text = st.baseline?.let { b ->
            if (b.percent >= 95) "Без обхода уже открывается ${b.percent}% сайтов — скорее всего, обход вам не нужен." else "Без обхода открывается только ${b.percent}% сайтов."
        }.orEmpty()
        resModel.clear(); st.results.forEach { resModel.addElement(it) }
    }

    private fun startProbe() {
        val picked = wizGroups.filter { it.value.isSelected }.keys
        c.probeDpi(wizSite.text, wizFull.isSelected, picked)
    }

    private fun addRule() {
        val v = ruleValue.text.trim()
        if (v.isEmpty()) return
        val target = allTargets().getOrNull(ruleTarget.selectedIndex) ?: return
        c.addRouteRule(RouteRule(kinds[ruleKind.selectedIndex], v, target, ruleNot.isSelected, ruleGroup.text.trim()))
        ruleValue.text = ""
    }

    private fun addGroup() {
        val name = groupName.text.trim()
        val members = memberList.selectedValuesList.mapNotNull { label -> membersFor().firstOrNull { targetName(it) == label } }
        if (name.isEmpty() || members.isEmpty()) return
        c.addRouteGroup(RouteGroup(name, if (groupType.selectedIndex == 0) RouteGroup.TYPE_URLTEST else RouteGroup.TYPE_SELECTOR, members))
        groupName.text = ""
    }

    private fun membersFor() = listOf(RouteTarget.PROXY, RouteTarget.DIRECT, RouteTarget.DPI) + nodes().map { RouteTarget.node(it.id) }

    private fun chainable() = servers.filter { it.protocolId in setOf("vless", "vmess", "trojan", "ss") }

    private fun applyChain() {
        val s = chainable().getOrNull(chainServer.selectedIndex) ?: return
        val options = listOf<String?>(null, RouteTarget.DPI) + nodes().filter { it.id != s.id }.map { RouteTarget.node(it.id) }
        c.setServerVia(s, options.getOrNull(chainVia.selectedIndex))
    }

    private fun addExtra(site: Boolean) {
        val n = geoName.text.trim().lowercase()
        if (n.isEmpty()) return
        c.setGeo { if (site) it.copy(extraSite = (it.extraSite + n).distinct()) else it.copy(extraIp = (it.extraIp + n).distinct()) }
        geoName.text = ""
    }

    private fun addCustom() {
        val n = customName.text.trim().lowercase(); val u = customUrl.text.trim()
        if (n.isEmpty() || !u.startsWith("https://")) return
        val kind = if (customKind.selectedIndex == 0) GeoKind.IP else GeoKind.SITE
        val type = customType.selectedItem as String
        c.setGeo { it.copy(custom = it.custom.filterNot { x -> x.kind == kind && x.name == n } + CustomGeoSource(kind, n, u, type)) }
        customName.text = ""; customUrl.text = ""
    }

    private fun selectedGeo(): Pair<GeoKind, String>? = geoEntryList.selectedValue?.substringBefore(' ')?.let { key ->
        GeoKind.fromDir(key.substringBefore('/'))?.let { it to key.substringAfter('/') }
    }

    override fun render(ui: UiState) {
        uiNow = ui
        servers = ui.data.servers
        val r = ui.data.settings.routing
        groups = r.routes.groups
        hint.text = if (ui.active) "Изменения применятся при следующем подключении." else ""
        hint.isVisible = ui.active
        val dpiOk = Platform.bundledByeDpi() != null
        wizWarn.isVisible = !dpiOk

        dpiOn.set(r.routes.dpi.enabled, dpiOk)
        strategy.sync(r.routes.dpi.strategy)

        fillModel(ruleModel, r.routes.rules.filter { it.kind != RouteKind.APP })
        fillModel(groupModel, r.routes.groups)

        quiet.run {
            val prevT = ruleTarget.selectedIndex
            ruleTarget.removeAllItems(); allTargets().forEach { ruleTarget.addItem(targetName(it)) }
            if (prevT in 0 until ruleTarget.itemCount) ruleTarget.selectedIndex = prevT
            memberModel.clear(); membersFor().forEach { memberModel.addElement(targetName(it)) }
            val prevS = chainServer.selectedIndex
            chainServer.removeAllItems(); chainable().forEach { chainServer.addItem(it.name) }
            if (prevS in 0 until chainServer.itemCount) chainServer.selectedIndex = prevS
            chainVia.removeAllItems()
            chainVia.addItem("Напрямую"); chainVia.addItem("Обход DPI")
            chainable().getOrNull(chainServer.selectedIndex)?.let { s -> nodes().filter { it.id != s.id }.forEach { chainVia.addItem(it.name) } }
            geoHours.selectedItem = r.geo.intervalHours.toString()
            geoIp.selectedIndex = GeoSources.ALL.indexOfFirst { it.id == r.geo.ipSource }.coerceAtLeast(0)
            geoSite.selectedIndex = GeoSources.ALL.indexOfFirst { it.id == r.geo.siteSource }.coerceAtLeast(0)
        }
        chainInfo.text = chainable().joinToString("\n") { s ->
            "${s.name}: ${viaOf(s)?.let { targetName(it) } ?: "напрямую"}"
        }
        geoAuto.set(r.geo.autoUpdate)
        geoExtraInfo.text = (r.geo.extraIp.map { "geoip: $it" } + r.geo.extraSite.map { "geosite: $it" } + r.geo.custom.map { "${it.kind.dir}/${it.name} ← ${it.url}" })
            .joinToString("\n").ifEmpty { "Дополнительных наборов нет." }
        val df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        geoEntries.clear()
        Platform.geoStore.entries().forEach { e ->
            geoEntries.addElement("${e.kind.dir}/${e.name} · ${e.source} · ${e.size / 1024} КБ · ${df.format(java.util.Date(e.updatedAt))}" + if (e.hasPrev) " · есть откат" else "")
        }
    }

    private fun <T> fillModel(model: DefaultListModel<T>, items: List<T>) {
        if ((0 until model.size()).map { model.getElementAt(it) } == items) return
        model.clear(); items.forEach { model.addElement(it) }
    }
}
