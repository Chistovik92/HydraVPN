package ru.gidravpn.hydra.servers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.InetAddress

/** Анкета нового сервера. Пароль и ключ живут только в памяти, пока идёт мастер. */
class ServerForm(
    val name: String,
    val target: SshTarget,
    val generation: AwgGeneration = AwgGeneration.V2_0,
    val awgPort: Int = 51820,
    val openFirewall: Boolean = false,
    val kind: ServerKind = ServerKind.AMNEZIAWG,
    /** Узел OpenFlux: имя канала и необязательные транспорты (direct есть всегда). */
    val channel: String = "hydra",
    val vyandex: String = "",
    val mailru: String = "",
    val cupsonline: String = "",
    val autoupdate: Boolean = true,
    /** Панель 3x-ui: порт доступа (инбаунд VLESS+Reality), по умолчанию 443. */
    val accessPort: Int = 443,
)

enum class ServerKind { AMNEZIAWG, OPENFLUX, PANEL_3XUI }

enum class Stage { IDLE, CONFIRM_FINGERPRINT, READY, RUNNING, DONE, FAILED }

data class MyServersUi(
    val stage: Stage = Stage.IDLE,
    val busy: Boolean = false,
    val fingerprint: String = "",
    val preflight: Preflight? = null,
    /** Что будет выполнено — показывается до запуска. */
    val plan: List<Step> = emptyList(),
    val log: List<String> = emptyList(),
    val progress: String = "",
    /** Панели 3x-ui: id сервера → email клиентов (после «Клиенты»). */
    val panelClients: Map<String, List<String>> = emptyMap(),
    val error: String? = null,
    /** Клиент, чей .conf показываем (имя сервера → клиент). */
    val shown: Pair<String, String>? = null,
)

/**
 * Мастер и управление своими серверами (Android и ПК используют один и тот же код).
 * [bundleFor] — получить серверный пакет по архитектуре; [importConf] — добавить клиентский .conf в список серверов Hydra.
 */
class MyServersManager(
    private val scope: CoroutineScope,
    private val servers: () -> List<MyServer>,
    private val save: (List<MyServer>) -> Unit,
    bundleFor: (String) -> AwgBundle,
    private val importConf: (name: String, conf: String) -> Unit,
) {
    private val service = MyServersService(bundleFor)
    private val _ui = MutableStateFlow(MyServersUi())
    val ui: StateFlow<MyServersUi> = _ui

    /** Подставить состояние без сети — для скриншотов и тестов отрисовки. */
    fun previewState(state: MyServersUi) { _ui.value = state }

    private var form: ServerForm? = null
    private var session: SshSession? = null
    private var installer: Installer? = null
    private var xuiTag = ""

    private fun log(l: String) = _ui.update { it.copy(log = (it.log + l).takeLast(400)) }
    private fun fail(msg: String) {
        closeSession()
        _ui.update { it.copy(stage = Stage.FAILED, busy = false, error = msg) }
    }
    private fun closeSession() { runCatching { session?.close() }; session = null }

    fun reset() { closeSession(); form = null; _ui.value = MyServersUi() }

    /** Шаг 1: узнать отпечаток хоста и показать его пользователю. */
    fun begin(f: ServerForm) {
        form = f
        _ui.value = MyServersUi(busy = true, progress = "Подключаюсь к ${f.target.host}…")
        scope.launch(Dispatchers.IO) {
            runCatching { SshConnector.fingerprint(f.target) }
                .onSuccess { fp -> _ui.update { it.copy(stage = Stage.CONFIRM_FINGERPRINT, busy = false, fingerprint = fp, progress = "") } }
                .onFailure { fail("Не удалось подключиться по SSH: ${it.message}") }
        }
    }

    /** Шаг 2: пользователь подтвердил отпечаток — входим, проверяем сервер и показываем план. */
    fun confirmFingerprint() {
        val f = form ?: return
        val fp = _ui.value.fingerprint
        _ui.update { it.copy(busy = true, progress = "Вхожу и проверяю сервер…") }
        scope.launch(Dispatchers.IO) {
            try {
                val s = SshConnector.connect(f.target, fp); session = s
                val pre = service.preflight(s, if (f.kind == ServerKind.AMNEZIAWG) f.awgPort else 0)
                val preview = when (f.kind) {
                    ServerKind.AMNEZIAWG -> service.planPreview(AwgServerSpec.create(f.target.host, f.awgPort, f.generation), pre, f.openFirewall)
                    ServerKind.OPENFLUX -> service.planPreviewOfx(OfxNodeSpec(f.target.host, f.channel, 0, "•".repeat(64), f.vyandex, f.mailru, f.cupsonline, f.autoupdate))
                    ServerKind.PANEL_3XUI -> {
                        _ui.update { it.copy(progress = "Узнаю последнюю версию 3x-ui…") }
                        xuiTag = XuiPanel.latestTag()
                        service.planPreviewXui(xuiTag, f.openFirewall, pre)
                    }
                }
                _ui.update { it.copy(stage = Stage.READY, busy = false, preflight = pre, plan = preview, progress = "") }
            } catch (e: Exception) { fail("Вход не удался: ${e.message}") }
        }
    }

    /** Шаг 3: выполнить план. */
    fun install() {
        val f = form ?: return
        val s = session ?: return
        val pre = _ui.value.preflight ?: return
        val fp = _ui.value.fingerprint
        _ui.update { it.copy(stage = Stage.RUNNING, busy = true, log = emptyList(), error = null) }
        scope.launch(Dispatchers.IO) {
            val mk: (SshSession, Collection<String>) -> Installer = { ssh, secrets -> Installer(ssh, secrets, ::onEvent).also { installer = it } }
            val res = when (f.kind) {
                ServerKind.AMNEZIAWG -> service.deployAwg(s, f.target, fp, f.name, f.generation, f.awgPort, f.openFirewall, pre, mk)
                ServerKind.OPENFLUX -> service.deployOfx(s, f.target, fp, f.name, f.channel, f.vyandex, f.mailru, f.cupsonline, f.autoupdate, mk, ::log)
                ServerKind.PANEL_3XUI -> service.deployXui(s, f.target, fp, f.name, xuiTag, f.accessPort, f.openFirewall, pre, mk, ::log)
            }
            when (res) {
                is DeployResult.Ok -> {
                    save(servers() + res.server)
                    res.server.awg?.let { spec -> importConf(res.server.name, spec.clientConf(res.server.clients.first())) }
                    res.server.ofx?.let { importConf(res.server.name, it.shareLink(res.server.name)) }
                    res.links.forEach { importConf(res.server.name, it) }
                    closeSession(); form = null
                    _ui.update { it.copy(stage = Stage.DONE, busy = false, progress = "Готово: сервер добавлен в «Серверы»") }
                }
                is DeployResult.Failed -> if (_ui.value.stage != Stage.FAILED) fail(_ui.value.error ?: res.reason)
            }
        }
    }

    private fun onEvent(e: InstallEvent) {
        when (e) {
            is InstallEvent.StepStart -> _ui.update { it.copy(progress = "${e.index}/${e.total}: ${e.step.title}") }.also { log("▶ ${e.step.title}") }
            is InstallEvent.Output -> log("  " + e.line)
            is InstallEvent.StepOk -> log("✔ ${e.step.title}")
            is InstallEvent.StepFailed -> { log("✘ ${e.step.title}: ${e.reason}"); _ui.update { it.copy(error = "${e.step.title}: ${e.reason}") } }
            InstallEvent.Done -> log("Готово")
        }
    }

    fun stop() { installer?.cancelled = true }

    // ------------------------------------------------------------- Управление развёрнутым сервером
    private fun withServer(id: String, pwd: SshTarget, work: (MyServer, SshSession, Installer) -> MyServer?) {
        val srv = servers().firstOrNull { it.id == id } ?: return
        _ui.update { it.copy(busy = true, error = null, log = emptyList()) }
        scope.launch(Dispatchers.IO) {
            try {
                SshConnector.connect(pwd, srv.fingerprint).use { ssh ->
                    val inst = Installer(ssh, listOf(pwd.password.orEmpty(), pwd.privateKey.orEmpty()), ::onEvent)
                    val upd = work(srv, ssh, inst)
                    if (upd != null) save(servers().map { if (it.id == id) upd else it })
                    else _ui.update { it.copy(error = it.error ?: "Операция не выполнена") }
                }
            } catch (e: HostKeyMismatch) { _ui.update { it.copy(error = e.message) } }
            catch (e: Exception) { _ui.update { it.copy(error = "Ошибка SSH: ${e.message}") } }
            _ui.update { it.copy(busy = false) }
        }
    }

    fun addClient(id: String, login: SshTarget, name: String) = withServer(id, login) { s, ssh, inst ->
        service.addClient(ssh, s, name, inst)?.let { (upd, c) ->
            _ui.update { it.copy(shown = name to upd.awg!!.clientConf(c)) }
            upd
        }
    }

    fun removeClient(id: String, login: SshTarget, name: String) = withServer(id, login) { s, _, inst -> service.removeClient(s, name, inst) }

    // ---------------------------------------------------------- Панель 3x-ui: клиенты по токену API (без входа по SSH)

    private fun withPanel(id: String, work: (MyServer, XuiApi) -> Unit) {
        val srv = servers().firstOrNull { it.id == id }?.takeIf { it.panel != null } ?: return
        val panel = srv.panel!!
        _ui.update { it.copy(busy = true, error = null) }
        scope.launch(Dispatchers.IO) {
            try { work(srv, XuiApi(DirectXuiTransport(panel.baseUrl, panel.token))) }
            catch (e: XuiApiException) { _ui.update { it.copy(error = "Панель: ${e.message}") } }
            catch (e: Exception) { _ui.update { it.copy(error = "Панель недоступна по сети (порт закрыт файрволом?): ${e.message}") } }
            _ui.update { it.copy(busy = false) }
        }
    }

    /**
     * Подключить уже стоящую панель 3x-ui по адресу и токену — без SSH и без установки (0.8.1-a).
     * Токен проверяется запросом настроек; запись панели попадает в «Мои серверы» и управляется так же, как поставленная мастером
     * (пользователи, ссылки). Запрос идёт обычным путём Hydra: при включённом VPN — через туннель (0.8.0-rc3).
     */
    fun connectPanel(name: String, address: String, token: String) {
        val base = XuiPanel.normalizeUrl(address) ?: return _ui.update { it.copy(error = "Не похоже на адрес панели. Пример: http://203.0.113.5:2053/путь") }
        val tok = token.trim()
        if (tok.isEmpty()) return _ui.update { it.copy(error = "Вставьте токен доступа к панели") }
        _ui.update { it.copy(busy = true, error = null, progress = "Проверяю панель…") }
        scope.launch(Dispatchers.IO) {
            try {
                val api = XuiApi(DirectXuiTransport(base, tok))
                api.settings()   // неверный токен или не та панель — исключение
                val ins = api.inbounds()
                val inboundId = (0 until ins.length()).mapNotNull { ins.optJSONObject(it) }
                    .let { l -> l.firstOrNull { it.optString("protocol") == "vless" } ?: l.firstOrNull() }?.optInt("id") ?: 0
                val host = java.net.URL(base).host
                val panel = PanelInstance(PanelKind.XUI3, base, "", "", tok, "", inboundId, "")
                val srv = MyServer(java.util.UUID.randomUUID().toString(), name.ifBlank { "Моя панель" }, host, fingerprint = "", panel = panel)
                save(servers() + srv)
                _ui.update { it.copy(busy = false, progress = "Панель подключена") }
            } catch (e: XuiApiException) {
                _ui.update { it.copy(busy = false, progress = "", error = "Панель ответила отказом: ${e.message}. Проверьте токен.") }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, progress = "", error = "Панель не открывается по адресу $base (${e.message}). Проверьте адрес, порт и что панель включена.") }
            }
        }
    }

    /** Клиенты панели (email) — для списка в карточке. */
    fun loadPanelClients(id: String) = withPanel(id) { s, api ->
        val emails = api.clientEmails()
        _ui.update { it.copy(panelClients = it.panelClients + (s.id to emails)) }
    }

    /** Новый клиент панели: ссылка показывается в окне, её можно добавить в «Серверы». */
    fun addPanelClient(id: String, email: String) = withPanel(id) { s, api ->
        val inbound = s.panel!!.inboundId.takeIf { it > 0 } ?: throw XuiApiException("У панели нет инбаунда Hydra")
        api.addClient(email, XuiPanel.random(14), listOf(inbound))
        val links = api.links(email)
        _ui.update { it.copy(shown = email to links.joinToString("\n"), panelClients = it.panelClients + (s.id to api.clientEmails())) }
    }

    /** Ссылки уже созданного клиента панели. */
    fun showPanelLinks(id: String, email: String) = withPanel(id) { _, api ->
        val links = api.links(email)
        if (links.isEmpty()) throw XuiApiException("Панель не выдала ссылок для $email")
        _ui.update { it.copy(shown = email to links.joinToString("\n")) }
    }

    fun removePanelClient(id: String, email: String) = withPanel(id) { s, api ->
        api.delClient(email)
        _ui.update { it.copy(panelClients = it.panelClients + (s.id to api.clientEmails())) }
    }

    /** Удалить сервер: со своей машины снимаем службу и файлы, из приложения — запись. */
    fun uninstall(id: String, login: SshTarget) {
        val srv = servers().firstOrNull { it.id == id } ?: return
        _ui.update { it.copy(busy = true, error = null, log = emptyList()) }
        scope.launch(Dispatchers.IO) {
            try {
                SshConnector.connect(login, srv.fingerprint).use { ssh ->
                    val inst = Installer(ssh, listOf(login.password.orEmpty(), srv.ofx?.key.orEmpty(), srv.panel?.token.orEmpty(), srv.panel?.password.orEmpty()), ::onEvent)
                    val done = when { srv.ofx != null -> service.uninstallOfx(srv, inst); srv.panel != null -> service.uninstallXui(inst); else -> service.uninstall(srv, inst) }
                    if (done) save(servers().filterNot { it.id == id })
                    else _ui.update { it.copy(error = it.error ?: "Не удалось удалить") }
                }
            } catch (e: Exception) { _ui.update { it.copy(error = "Ошибка SSH: ${e.message}") } }
            _ui.update { it.copy(busy = false) }
        }
    }

    /** Убрать только запись (сервер недоступен или уже удалён вручную). */
    fun forget(id: String) = save(servers().filterNot { it.id == id })

    fun clientConf(id: String, clientName: String): String? {
        val s = servers().firstOrNull { it.id == id } ?: return null
        s.ofx?.let { return it.shareLink(s.name) }
        val c = s.clients.firstOrNull { it.name == clientName } ?: return null
        return s.awg?.clientConf(c)
    }

    fun showConf(name: String, conf: String) = _ui.update { it.copy(shown = name to conf) }
    fun hideShown() = _ui.update { it.copy(shown = null) }

    companion object {
        /** Грубая проверка адреса до сети: IP или имя хоста. */
        fun validHost(h: String) = h.isNotBlank() && h.length < 254 && !h.any { it.isWhitespace() || it == '/' } &&
            (runCatching { InetAddress.getByName(h); true }.getOrDefault(false))
    }
}

private typealias MyServersService = MyServerService
