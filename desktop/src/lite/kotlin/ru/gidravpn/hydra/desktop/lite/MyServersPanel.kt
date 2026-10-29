package ru.gidravpn.hydra.desktop.lite

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.UiState
import ru.gidravpn.hydra.servers.AwgGeneration
import ru.gidravpn.hydra.servers.FriendlyText
import ru.gidravpn.hydra.servers.InstallStage
import ru.gidravpn.hydra.servers.MyServer
import ru.gidravpn.hydra.servers.MyServersUi
import ru.gidravpn.hydra.servers.OfxNodeSpec
import ru.gidravpn.hydra.servers.PanelNodes
import ru.gidravpn.hydra.servers.ServerForm
import ru.gidravpn.hydra.servers.ServerKind
import ru.gidravpn.hydra.servers.ServiceForm
import ru.gidravpn.hydra.servers.SshTarget
import ru.gidravpn.hydra.servers.Stage
import ru.gidravpn.hydra.servers.UiLang
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

/** «Мои серверы» в Classic: только Swing и общий MyServersManager, без Compose/JavaFX. */
internal class MyServersPanel(private val c: AppController, scope: CoroutineScope) : JPanel(BorderLayout()), Renderable {
    private val m = c.myServers
    private val content = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private var app: UiState? = null
    private var state = MyServersUi()
    private var loginShown: String? = null
    private var shownConfig: String? = null
    private var openWhenReady: String? = null

    init {
        add(JScrollPane(JPanel(BorderLayout()).apply { add(content, BorderLayout.NORTH) }).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBar.unitIncrement = 16
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)
        scope.launch(Dispatchers.Swing) { m.ui.collect { state = it; rebuild(); dialogs(); openReadyTunnel() } }
    }

    override fun render(ui: UiState) {
        val old = app
        app = ui
        if (old?.data?.myServers != ui.data.myServers) rebuild()
    }

    private fun rebuild() {
        val servers = app?.data?.myServers.orEmpty()
        content.removeAll()
        content.add(column(
            row(button("Добавить сервер") { addServer() }, button("У меня уже есть панель") { connectPanel() }),
            note("Свои VPS: установка AmneziaWG, OpenFlux, панелей и нод через общий менеджер Hydra. Данные входа хранятся зашифрованными только на этом компьютере."),
            gap = 8,
        ).apply { border = BorderFactory.createEmptyBorder(12, 14, 6, 14) })
        if (state.stage != Stage.IDLE && state.opServer == null) content.add(section("Добавление сервера", wizard()))
        if (servers.isEmpty() && state.stage == Stage.IDLE) content.add(note("Серверов пока нет. Нужен VPS с Debian или Ubuntu и вход по SSH."))
        servers.forEach { content.add(serverCard(it)) }
        state.error?.takeIf { state.opServer == null && state.stage == Stage.IDLE }?.let { content.add(errorArea(it)) }
        content.revalidate()
        content.repaint()
    }

    private fun wizard(): Component = when (state.stage) {
        Stage.CONFIRM_FINGERPRINT -> column(
            note("Проверьте отпечаток SSH. Дальше Hydra будет доверять только ему."),
            JTextArea(state.fingerprint).apply { isEditable = false; lineWrap = true },
            if (state.previousFingerprint.isBlank()) JLabel("") else note("ВНИМАНИЕ: отпечаток изменился! Раньше: ${state.previousFingerprint}", Danger),
            row(button("Доверять и войти") { m.confirmFingerprint() }, button("Отмена") { m.reset() }),
        )
        Stage.DONE -> column(note(state.progress), button("Готово") { m.reset() })
        else -> column(note(state.progress.ifBlank { state.error.orEmpty() }), button("Закрыть") { m.reset() })
    }

    private fun serverCard(srv: MyServer): Component {
        val st = state.status[srv.id]
        val busy = state.busy
        val parts = mutableListOf<Component>()
        parts += row(bold(srv.name), JLabel(if (st?.error != null) "недоступен" else if (st != null) "на связи" else "не проверен"))
        parts += note("${srv.sshUser}@${srv.host}:${srv.sshPort}" + (srv.nodeOf?.let { " · нода ${it.kind.title} панели «${it.mainName}»" }.orEmpty()))
        st?.takeIf { it.error == null && it.uptimeSec > 0 }?.let { parts += note("Нагрузка ${it.load} · память ${it.mem} · диск ${it.disk}") }
        parts += row(
            button("Проверить") { m.refresh(srv.id) }.apply { isEnabled = !busy },
            button("Установить") { install(srv) }.apply { isEnabled = !busy && srv.nodeOf == null && !srv.panelOnly },
            button("Установить ноду") { installNode(srv) }.apply { isEnabled = !busy && !srv.hasServices && srv.nodeOf == null && !srv.panelOnly },
            button("Данные входа") { login(srv) }.apply { isEnabled = !busy && !srv.panelOnly },
        )
        srv.awg?.let { awg ->
            parts += note("AmneziaWG ${awg.generation.label} · UDP ${awg.port} · устройств: ${srv.clients.size}")
            parts += row(button("Добавить устройство") { addClient(srv) }.apply { isEnabled = !busy },
                button("Перезапустить AWG") { m.restartService(srv.id, ServerKind.AMNEZIAWG) }.apply { isEnabled = !busy })
            srv.clients.forEach { cl -> parts += row(JLabel("• ${cl.name} · ${cl.address}"),
                button("Конфиг") { m.clientConf(srv.id, cl.name)?.let { m.showConf(cl.name, it) } }.apply { isEnabled = cl.privateKey.isNotBlank() }) }
        }
        srv.ofx?.let { ofx ->
            parts += note("OpenFlux · канал ${ofx.channel} · TCP ${ofx.port}")
            parts += row(button("Ссылка") { m.ofxLink(srv.id)?.let { m.showConf(srv.name, it) } },
                button("Перезапустить OpenFlux") { m.restartService(srv.id, ServerKind.OPENFLUX) }.apply { isEnabled = !busy })
        }
        srv.panel?.let { p ->
            parts += note("Панель ${p.kind.title} ${p.version} · ${p.baseUrl}")
            parts += row(button("Открыть панель") { openPanel(srv) },
                button("Добавить пользователя") { addPanelUser(srv) }.apply { isEnabled = !busy },
                button("Пользователи") { m.loadPanelClients(srv.id) }.apply { isEnabled = !busy },
                button("Подключить ноду") { attachNode(srv) }.apply { isEnabled = !busy && srv.fingerprint.isNotBlank() && PanelNodes.supports(p.kind) })
            p.nodes.forEach { n -> parts += row(JLabel("• нода ${n.name} (${n.host})"), button("Убрать") {
                if (confirmDelete("Убрать ноду «${n.name}» и удалить её службы?")) m.removePanelNode(srv.id, n.serverId)
            }.apply { isEnabled = !busy }) }
            state.panelClients[srv.id]?.forEach { email -> parts += row(JLabel("• $email"), button("Ссылка") { m.showPanelLinks(srv.id, email) }) }
        }
        if (srv.broken) parts += note("Запись повреждена. Уберите её и подключите панель заново.", Danger)
        val install = state.install?.takeIf { it.serverId == srv.id }
        if (install != null) parts += installPanel(install)
        if (state.opServer == srv.id) {
            if (busy && install == null) parts += note(state.progress)
            state.error?.takeIf { install == null }?.let { parts += errorArea(it) }
            if (state.log.isNotEmpty() && install == null) parts += logArea(state.log)
        }
        parts += row(button("Перезагрузить") {
            if (confirmDelete("Перезагрузить сервер «${srv.name}»?")) m.reboot(srv.id)
        }.apply { isEnabled = !busy && !srv.panelOnly }, button("Очистить сервер") {
            if (confirmDelete("Удалить с сервера всё, что ставила Hydra?")) m.wipe(srv.id)
        }.apply { isEnabled = !busy && !srv.panelOnly; foreground = Danger }, button("Убрать из списка") {
            if (confirmDelete("Убрать запись из приложения? На сервере ничего не изменится.")) m.forget(srv.id)
        }.apply { isEnabled = !busy })
        return section(srv.name, *parts.toTypedArray()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            border = BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12), border)
        }
    }

    private fun installPanel(i: ru.gidravpn.hydra.servers.InstallUi): Component {
        val parts = mutableListOf<Component>(bold("Установка ${i.form.kind.panel?.title ?: i.form.kind.name}"))
        when (i.stage) {
            InstallStage.CHECKING -> parts += note(state.progress)
            InstallStage.READY -> {
                i.preflight?.let { pre -> FriendlyText.checklist(pre).forEach { parts += note((if (it.ok) "✓ " else "✗ ") + it.title + if (it.ok) "" else " — ${it.help}", if (it.ok) Muted else Danger) } }
                FriendlyText.planSentences(i.form.kind, i.form.openFirewall, UiLang.RU, https = i.form.sslIp).forEach { parts += note("• $it") }
                i.plan.forEach { parts += note("Команда: ${it.title}") }
                parts += row(button("Установить") { m.runInstall() }.apply { isEnabled = i.preflight?.ok == true && !state.busy }, button("Отмена") { m.closeInstall() })
            }
            InstallStage.RUNNING -> { parts += note(state.progress); parts += logArea(state.log); parts += button("Остановить") { m.stop() } }
            InstallStage.DONE -> { parts += note("Готово."); parts += logArea(state.log); parts += button("Закрыть") { m.closeInstall() } }
            InstallStage.FAILED -> { state.error?.let { parts += errorArea(it) }; parts += logArea(state.log); parts += button("Закрыть") { m.closeInstall() } }
        }
        return section("План установки", *parts.toTypedArray())
    }

    private fun addServer() {
        val host = JTextField(22); val port = JTextField("22", 5); val user = JTextField("root", 10)
        val password = JPasswordField(16); val remember = JCheckBox("Запомнить данные входа", true)
        val form = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); add(row(JLabel("Адрес:"), host)); add(row(JLabel("SSH-порт:"), port, JLabel("Пользователь:"), user)); add(row(JLabel("Пароль:"), password)); add(remember) }
        if (JOptionPane.showConfirmDialog(this, form, "Добавить сервер", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
        val p = port.text.toIntOrNull() ?: 0
        if (!ru.gidravpn.hydra.servers.MyServersManager.validHost(host.text.trim()) || p !in 1..65535 || password.password.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Укажите адрес, SSH-порт и пароль."); return
        }
        m.reset(); m.begin(ServerForm(host.text.trim(), SshTarget(host.text.trim(), p, user.text.ifBlank { "root" }, String(password.password)), remember.isSelected))
    }

    private fun login(srv: MyServer) {
        val user = JTextField(srv.sshUser, 12); val password = JPasswordField(18); val remember = JCheckBox("Запомнить", m.hasSavedLogin(srv.id))
        if (JOptionPane.showConfirmDialog(this, row(JLabel("Пользователь:"), user, JLabel("Пароль:"), password, remember), "Данные входа", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
            m.changeLogin(srv.id, SshTarget(srv.host, srv.sshPort, user.text, String(password.password)), remember.isSelected)
    }

    private fun connectPanel() {
        val address = JTextField(28); val token = JTextField(28); val name = JTextField(16)
        val form = column(row(JLabel("Название:"), name), row(JLabel("Адрес 3x-ui:"), address), row(JLabel("API-токен:"), token))
        if (JOptionPane.showConfirmDialog(this, form, "У меня уже есть панель", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
            m.connectPanel(name.text, address.text, token.text)
    }

    private fun install(srv: MyServer) {
        val kinds = ServerKind.entries.toTypedArray()
        val choice = JComboBox(kinds)
        val port = JTextField("51820", 6); val channel = JTextField("hydra", 12); val fw = JCheckBox("Открыть порт в файрволе")
        if (JOptionPane.showConfirmDialog(this, column(row(JLabel("Служба:"), choice), row(JLabel("Порт AWG:"), port, JLabel("Канал OpenFlux:"), channel), fw), "Установить на ${srv.name}", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
        val kind = choice.selectedItem as ServerKind
        if (kind == ServerKind.OPENFLUX && !OfxNodeSpec.validChannel(channel.text)) { JOptionPane.showMessageDialog(this, "Некорректное имя канала"); return }
        m.prepareInstall(srv.id, ServiceForm(kind = kind, generation = AwgGeneration.V2_0, awgPort = port.text.toIntOrNull() ?: 51820, openFirewall = fw.isSelected, channel = channel.text))
    }

    private fun installNode(srv: MyServer) {
        val panels = m.nodePanels(srv.id)
        if (panels.isEmpty()) { JOptionPane.showMessageDialog(this, "Сначала установите 3x-ui, PasarGuard, Remnawave или Nexora на другом сервере."); return }
        choose("Установить ноду", panels, "Hydra установит ноду и откроет её порты только для главного сервера.") { m.installNode(srv.id, it.id) }
    }

    private fun attachNode(srv: MyServer) {
        val candidates = m.nodeCandidates(srv.id)
        if (candidates.isEmpty()) { JOptionPane.showMessageDialog(this, "Нет свободного сервера. Добавьте второй сервер без служб Hydra."); return }
        choose("Подключить ноду к ${srv.panel?.kind?.title}", candidates, "Выберите сервер будущей ноды.") { m.addPanelNode(srv.id, it.id) }
    }

    private fun choose(title: String, servers: List<MyServer>, text: String, action: (MyServer) -> Unit) {
        val selected = JOptionPane.showInputDialog(this, text, title, JOptionPane.PLAIN_MESSAGE, null, servers.map { "${it.name} (${it.host})" }.toTypedArray(), null) as? String ?: return
        servers.firstOrNull { "${it.name} (${it.host})" == selected }?.let(action)
    }

    private fun addClient(srv: MyServer) {
        val name = JOptionPane.showInputDialog(this, "Имя устройства латиницей:")?.trim().orEmpty()
        if (name.matches(Regex("[A-Za-z0-9_-]{1,24}")) && srv.clients.none { it.name == name }) m.addClient(srv.id, name)
        else if (name.isNotEmpty()) JOptionPane.showMessageDialog(this, "Некорректное или занятое имя")
    }

    private fun addPanelUser(srv: MyServer) {
        val name = JOptionPane.showInputDialog(this, "Имя пользователя:")?.trim().orEmpty()
        if (name.isNotBlank()) m.addPanelClient(srv.id, name)
    }

    private fun openPanel(srv: MyServer) {
        val p = srv.panel ?: return
        if (!p.local || srv.fingerprint.isBlank()) return openUrl(p.baseUrl)
        state.tunnels[srv.id]?.let { return openUrl(it) }
        openWhenReady = srv.id
        m.openPanelTunnel(srv.id)
    }

    private fun openReadyTunnel() {
        val id = openWhenReady ?: return
        if (id !in (app?.data?.myServers.orEmpty().map { it.id })) { openWhenReady = null; return }
        val error = state.error
        if (state.opServer == id && error != null) { openWhenReady = null; return }
        state.tunnels[id]?.let { openWhenReady = null; openUrl(it) }
    }

    private fun dialogs() {
        state.login?.let { req ->
            if (loginShown == req.serverId + req.title + req.error) return
            loginShown = req.serverId + req.title + req.error
            SwingUtilities.invokeLater {
                val password = JPasswordField(20); val remember = JCheckBox("Запомнить", true)
                val ok = JOptionPane.showConfirmDialog(this, column(note(req.error ?: req.title), row(JLabel("Пароль:"), password), remember), "Вход на сервер", JOptionPane.OK_CANCEL_OPTION)
                val srv = app?.data?.myServers?.firstOrNull { it.id == req.serverId }
                if (ok == JOptionPane.OK_OPTION && srv != null) m.provideLogin(SshTarget(srv.host, srv.sshPort, srv.sshUser, String(password.password)), remember.isSelected) else m.cancelLogin()
            }
        }
        if (state.login == null) loginShown = null
        state.shown?.let { (name, conf) ->
            if (shownConfig == conf) return
            shownConfig = conf
            SwingUtilities.invokeLater {
                val area = JTextArea(conf, 16, 60).apply { isEditable = false; lineWrap = true }
                JOptionPane.showMessageDialog(this, JScrollPane(area), "Подключение «$name» — ключ доступа, не публикуйте", JOptionPane.INFORMATION_MESSAGE)
                m.hideShown()
            }
        }
        if (state.shown == null) shownConfig = null
    }

    private fun logArea(lines: List<String>) = JScrollPane(JTextArea(lines.takeLast(30).joinToString("\n"), 8, 65).apply { isEditable = false; lineWrap = true }).apply {
        alignmentX = Component.LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, 150)
    }
    private fun errorArea(text: String) = note(FriendlyText.explain(text).let { "${it.title}. ${it.advice}" }, Danger)
}
