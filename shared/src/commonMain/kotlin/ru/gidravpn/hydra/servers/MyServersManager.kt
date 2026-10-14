package ru.gidravpn.hydra.servers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** Анкета нового сервера: только доступ. Что ставить, выбирается уже после входа ([ServiceForm]). */
class ServerForm(
    val name: String,
    val target: SshTarget,
    /** Запомнить пароль/ключ (зашифрованно) — тогда управление сервером не спрашивает его каждый раз. */
    val remember: Boolean = true,
)

/** Служба, которую можно поставить на свой сервер. */
enum class ServerKind {
    AMNEZIAWG, OPENFLUX, PANEL_3XUI, PANEL_SUI, PANEL_XUI;

    /** Вид панели для служб-панелей; null — не панель. */
    val panel: PanelKind? get() = when (this) { PANEL_3XUI -> PanelKind.XUI3; PANEL_SUI -> PanelKind.SUI; PANEL_XUI -> PanelKind.XUI; else -> null }

    companion object {
        fun ofPanel(k: PanelKind) = when (k) { PanelKind.XUI3 -> PANEL_3XUI; PanelKind.SUI -> PANEL_SUI; PanelKind.XUI -> PANEL_XUI }
    }
}

/** Параметры установки службы. */
class ServiceForm(
    val kind: ServerKind = ServerKind.AMNEZIAWG,
    val generation: AwgGeneration = AwgGeneration.V2_0,
    val awgPort: Int = 51820,
    val openFirewall: Boolean = false,
    /** Узел OpenFlux: имя канала и необязательные транспорты (direct есть всегда). */
    val channel: String = "hydra",
    val vyandex: String = "",
    val mailru: String = "",
    val cupsonline: String = "",
    val autoupdate: Boolean = true,
    /** Панель 3x-ui: порт доступа (инбаунд VLESS+Reality), по умолчанию 443. */
    val accessPort: Int = 443,
    /** Панель 3x-ui: HTTPS сертификатом Let's Encrypt на IP (0.8.1-rc1). */
    val sslIp: Boolean = true,
    /** Панель 3x-ui: под какой сайт маскируется Reality (0.8.1-rc1). */
    val mask: XuiPanel.RealityMask = XuiPanel.RealityMask.VK,
    /** Панель 3x-ui: транспорт инбаунда (0.8.1-rc1), по умолчанию gRPC. */
    val transport: XuiPanel.Transport = XuiPanel.Transport.GRPC,
)

/** Мастер добавления сервера. */
enum class Stage { IDLE, CONFIRM_FINGERPRINT, DONE, FAILED }

/** Установка службы на уже добавленный сервер. */
enum class InstallStage { CHECKING, READY, RUNNING, DONE, FAILED }

data class InstallUi(
    val serverId: String,
    val form: ServiceForm,
    val stage: InstallStage = InstallStage.CHECKING,
    val preflight: Preflight? = null,
    /** Что будет выполнено — показывается до запуска. */
    val plan: List<Step> = emptyList(),
)

/** Нужен пароль/ключ: сохранённого нет или он не подошёл. Экран показывает окно входа, ответ — [MyServersManager.provideLogin]. */
data class LoginRequest(val serverId: String, val title: String, val error: String? = null)

data class MyServersUi(
    val stage: Stage = Stage.IDLE,
    val busy: Boolean = false,
    val fingerprint: String = "",
    val preflight: Preflight? = null,
    val log: List<String> = emptyList(),
    val progress: String = "",
    val error: String? = null,
    /** Сервер, к которому относятся текущая операция, журнал и ошибка (null — мастер добавления). */
    val opServer: String? = null,
    /** id сервера, только что добавленного мастером. */
    val addedId: String? = null,
    val install: InstallUi? = null,
    val login: LoginRequest? = null,
    /** Последняя проверка каждого сервера. */
    val status: Map<String, ServerStatus> = emptyMap(),
    /** Серверы с запомненными данными входа. */
    val saved: Set<String> = emptySet(),
    /** Панели 3x-ui: id сервера → email пользователей (после «Пользователи»). */
    val panelClients: Map<String, List<String>> = emptyMap(),
    /** Что показываем в окне: имя → конфиг клиента или ссылка. */
    val shown: Pair<String, String>? = null,
)

/**
 * «Мои серверы» (Android и ПК используют один и тот же код). С 0.8.0-rc4 — сначала доступ к серверу, потом службы:
 *  1. «Добавить сервер»: адрес и пароль/ключ → отпечаток → вход и проверка → сервер в списке (данные входа можно запомнить;
 *     найденные на сервере службы Hydra берутся под управление);
 *  2. на карточке сервера — установка AmneziaWG / узла OpenFlux, клиенты, перезапуск, удаление службы, перезагрузка,
 *     полная очистка сервера от всего, что ставила Hydra, смена/забывание данных входа.
 * Любая операция берёт данные входа из [vault] (или из памяти на время работы приложения); если их нет или они не подошли —
 * ставит [LoginRequest] и повторяет операцию после [provideLogin].
 *
 * [bundleFor] — серверный пакет AmneziaWG по архитектуре; [importConf] — добавить конфиг/ссылку в список серверов Hydra.
 */
class MyServersManager(
    private val scope: CoroutineScope,
    private val servers: () -> List<MyServer>,
    private val save: (List<MyServer>) -> Unit,
    bundleFor: (String) -> AwgBundle,
    vault: CredentialVault = MemoryVault(),
    private val importConf: (name: String, conf: String) -> Unit,
) {
    private val service = MyServerService(bundleFor)
    /** Сбой хранилища (Keystore недоступен, нет места) не роняет операцию: вход тогда помнится до закрытия приложения. */
    private val store: CredentialVault = SafeVault(vault)
    private val _ui = MutableStateFlow(MyServersUi(saved = runCatching { store.ids() }.getOrDefault(emptySet())))
    val ui: StateFlow<MyServersUi> = _ui

    /** Подставить состояние без сети — для скриншотов и тестов отрисовки. */
    fun previewState(state: MyServersUi) { _ui.value = state }

    private var form: ServerForm? = null
    /** Последняя версия панели, узнанная при подготовке установки (показывается в плане и ставится она же). */
    @Volatile private var xuiTag = ""
    @Volatile private var panelTag = ""
    @Volatile private var installer: Installer? = null
    /** Данные входа на время работы приложения (не запомненные пользователем): не спрашивать их на каждую кнопку. */
    private val memLogins = java.util.concurrent.ConcurrentHashMap<String, SshTarget>()
    /** Операция, ждущая ввода пароля. */
    private var pending: ((SshTarget) -> Unit)? = null

    private fun find(id: String) = servers().firstOrNull { it.id == id }
    private fun update(s: MyServer) = save(servers().map { if (it.id == s.id) s else it })
    private fun refreshSaved() = _ui.update { it.copy(saved = runCatching { store.ids() }.getOrDefault(emptySet())) }

    private fun log(l: String) = _ui.update { it.copy(log = (it.log + l).takeLast(400)) }

    fun hasSavedLogin(id: String) = id in _ui.value.saved

    // ================================================================================================ Добавление сервера

    fun reset() {
        form = null
        _ui.update { MyServersUi(saved = it.saved, status = it.status) }
    }

    /** Шаг 1: узнать отпечаток хоста и показать его пользователю. */
    fun begin(f: ServerForm) {
        form = f
        _ui.update { it.copy(stage = Stage.IDLE, busy = true, progress = "Подключаюсь к ${f.target.host}…", error = null, opServer = null, log = emptyList(), addedId = null) }
        scope.launch(Dispatchers.IO) {
            runCatching { SshConnector.fingerprint(f.target) }
                .onSuccess { fp -> _ui.update { it.copy(stage = Stage.CONFIRM_FINGERPRINT, busy = false, fingerprint = fp, progress = "") } }
                .onFailure { e -> _ui.update { it.copy(stage = Stage.FAILED, busy = false, progress = "", error = "Не удалось подключиться по SSH: ${e.message}") } }
        }
    }

    /**
     * Шаг 2: пользователь подтвердил отпечаток — входим, проверяем сервер, ищем уже стоящие службы Hydra и сохраняем сервер.
     * Тот же адрес и порт уже есть в списке — запись обновляется (новый отпечаток, данные входа), службы сохраняются.
     */
    fun confirmFingerprint() {
        val f = form ?: return
        val fp = _ui.value.fingerprint
        _ui.update { it.copy(busy = true, progress = "Вхожу и проверяю сервер…") }
        scope.launch(Dispatchers.IO) {
            try {
                SshConnector.connect(f.target, fp).use { ssh ->
                    val pre = service.preflight(ssh, 0)
                    val st = service.status(ssh)
                    val existing = servers().firstOrNull { it.host.equals(f.target.host, true) && it.sshPort == f.target.port }
                    val base = (existing ?: MyServer(UUID.randomUUID().toString(), f.name, f.target.host, f.target.port, f.target.user, fp))
                        .copy(name = f.name.ifBlank { existing?.name ?: f.target.host }, sshUser = f.target.user, fingerprint = fp)
                    val (srv, writeState) = service.reconcile(base, st)
                    if (writeState) srv.awg?.let { spec -> Installer(ssh, listOf(spec.privateKey)) {}.run(ServerOps.awgStateSteps(spec, srv.clients)) }
                    if (existing == null) save(servers() + srv) else update(srv)
                    if (f.remember) store.save(srv.id, SavedLogin.of(f.target)) else { store.remove(srv.id); memLogins[srv.id] = f.target }
                    refreshSaved()
                    val found = listOfNotNull(srv.awg?.let { "AmneziaWG ${it.generation.label}" }, srv.ofx?.let { "узел OpenFlux «${it.channel}»" })
                    form = null
                    _ui.update {
                        it.copy(stage = Stage.DONE, busy = false, preflight = pre, addedId = srv.id, status = it.status + (srv.id to st),
                            progress = if (found.isEmpty()) "Сервер добавлен" else "Сервер добавлен; уже установлено: ${found.joinToString()}")
                    }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(stage = Stage.FAILED, busy = false, progress = "", error = "Вход не удался: ${e.message}") }
            }
        }
    }

    // ================================================================================================ Вход для операций

    private fun isAuthError(e: Throwable): Boolean {
        val m = (e.message ?: "").lowercase()
        return "auth fail" in m || "auth cancel" in m || "userauth" in m || "too many authentication" in m
    }

    /**
     * Выполнить [work] на сервере [id]: данные входа — сохранённые, из памяти или спросить ([LoginRequest]).
     * [quiet] — без журнала и индикатора «занят» на всём экране (проверка состояния).
     */
    private fun op(id: String, title: String, quiet: Boolean = false, work: (srv: MyServer, ssh: SshSession, inst: Installer) -> Unit) {
        val srv = find(id) ?: return
        if (_ui.value.busy || srv.fingerprint.isBlank()) return   // у подключённой панели (и испорченной записи) нет SSH
        val t = store.load(id)?.target(srv) ?: memLogins[id]
        if (t == null) { askLogin(id, title, null) { login -> run(id, title, login, quiet, work) }; return }
        run(id, title, t, quiet, work)
    }

    private fun askLogin(id: String, title: String, error: String?, retry: (SshTarget) -> Unit) {
        pending = retry
        _ui.update { it.copy(login = LoginRequest(id, title, error), busy = false) }
    }

    /** Ответ на [LoginRequest]: [remember] — сохранить зашифрованно. */
    fun provideLogin(target: SshTarget, remember: Boolean) {
        val req = _ui.value.login ?: return
        val retry = pending ?: return
        pending = null
        _ui.update { it.copy(login = null) }
        if (remember) { store.save(req.serverId, SavedLogin.of(target)); memLogins.remove(req.serverId) } else memLogins[req.serverId] = target
        refreshSaved()
        retry(target)
    }

    fun cancelLogin() { pending = null; _ui.update { it.copy(login = null, install = it.install?.takeIf { i -> i.stage != InstallStage.CHECKING }) } }

    private fun run(id: String, title: String, t: SshTarget, quiet: Boolean, work: (MyServer, SshSession, Installer) -> Unit) {
        val srv = find(id) ?: return
        _ui.update { it.copy(busy = true, opServer = id, error = null, progress = title, log = if (quiet) it.log else emptyList()) }
        scope.launch(Dispatchers.IO) {
            try {
                SshConnector.connect(t, srv.fingerprint).use { ssh ->
                    val inst = Installer(ssh, listOfNotNull(t.password, t.privateKey, t.keyPassphrase), if (quiet) { _ -> } else ::onEvent).also { installer = it }
                    try { work(srv, ssh, inst) } finally {
                        // после любого действия карточка показывает свежее состояние, а не то, что было до него
                        if (!quiet) runCatching { service.status(ssh, find(id)?.awg?.iface ?: "awg0") }.getOrNull()
                            ?.let { st -> _ui.update { it.copy(status = it.status + (id to st)) } }
                    }
                }
            } catch (e: HostKeyMismatch) {
                _ui.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                if (isAuthError(e)) {
                    // сохранённый пароль поменяли на сервере — забываем его и спрашиваем заново
                    store.remove(id); memLogins.remove(id); refreshSaved()
                    _ui.update { it.copy(busy = false, progress = "") }
                    askLogin(id, title, "Сервер не принял сохранённые данные входа — введите новые") { login -> run(id, title, login, quiet, work) }
                    return@launch
                }
                val msg = if (e is OpFailed) e.message else "Ошибка SSH: ${e.message}"
                _ui.update { it.copy(error = msg, status = if (quiet) it.status + (id to ServerStatus(error = msg)) else it.status) }
            } finally {
                installer = null
            }
            _ui.update { it.copy(busy = false, progress = "") }
        }
    }

    private class OpFailed(msg: String) : Exception(msg)

    private fun onEvent(e: InstallEvent) {
        when (e) {
            is InstallEvent.StepStart -> _ui.update { it.copy(progress = "${e.index}/${e.total}: ${e.step.title}") }.also { log("▶ ${e.step.title}") }
            is InstallEvent.Output -> log("  " + e.line)
            // «▶ шаг» заменяется на «✔ шаг», а не дублируется второй строкой
            is InstallEvent.StepOk -> _ui.update { u ->
                val i = u.log.lastIndexOf("▶ ${e.step.title}")
                u.copy(log = if (i >= 0) u.log.toMutableList().also { it[i] = "✔ ${e.step.title}" } else (u.log + "✔ ${e.step.title}").takeLast(400))
            }
            is InstallEvent.StepFailed -> { log("✘ ${e.step.title}: ${e.reason}"); _ui.update { it.copy(error = "${e.step.title}: ${e.reason}") } }
            InstallEvent.Done -> Unit
        }
    }

    /** Остановить выполняющийся план (после текущего шага). */
    fun stop() { installer?.cancelled = true }

    // ================================================================================================ Состояние

    /** Проверить сервер: доступность, система, службы, клиенты; заодно сверить запись с сервером. Панель без SSH — запросом к API. */
    fun refresh(id: String) {
        if (find(id)?.panelOnly == true) return withPanel(id) { s, api ->
            api.settings()
            _ui.update { it.copy(status = it.status + (s.id to ServerStatus(xuiInstalled = true, xuiState = "active")), panelClients = it.panelClients + (s.id to api.clientEmails())) }
        }
        refreshSsh(id)
    }

    private fun refreshSsh(id: String) = op(id, "Проверяю сервер…", quiet = true) { srv, ssh, inst ->
        val st = service.status(ssh, srv.awg?.iface ?: "awg0")
        val (upd, writeState) = service.reconcile(srv, st)
        if (writeState) upd.awg?.let { spec -> inst.addSecret(spec.privateKey); inst.run(ServerOps.awgStateSteps(spec, upd.clients)) }
        if (upd != srv) update(upd)
        _ui.update { it.copy(status = it.status + (srv.id to st)) }
    }

    // ================================================================================================ Установка служб

    /** Подготовить установку: войти, проверить сервер под эту службу, показать план. */
    fun prepareInstall(id: String, f: ServiceForm) {
        if (_ui.value.busy) return
        _ui.update { it.copy(install = InstallUi(id, f), error = null, opServer = id) }
        op(id, "Проверяю сервер перед установкой…") { srv, ssh, _ ->
            val pre = service.preflight(ssh, if (f.kind == ServerKind.AMNEZIAWG) f.awgPort else 0)
            val plan = when (f.kind) {
                ServerKind.AMNEZIAWG -> service.planPreview(AwgServerSpec.create(srv.host, f.awgPort, f.generation), pre, f.openFirewall)
                ServerKind.OPENFLUX -> service.planPreviewOfx(OfxNodeSpec(srv.host, f.channel, 0, "•".repeat(64), f.vyandex, f.mailru, f.cupsonline, f.autoupdate))
                ServerKind.PANEL_3XUI -> {
                    _ui.update { it.copy(progress = "Узнаю последнюю версию 3x-ui…") }
                    xuiTag = XuiPanel.latestTag()
                    service.planPreviewXui(xuiTag, f.openFirewall, pre, f.sslIp, f.mask, f.transport)
                }
                ServerKind.PANEL_SUI, ServerKind.PANEL_XUI -> {
                    val spec = OtherPanels.spec(f.kind.panel!!)!!
                    _ui.update { it.copy(progress = "Узнаю последнюю версию ${spec.kind.title}…") }
                    panelTag = OtherPanels.latestTag(spec)
                    service.planPreviewPanel(spec.kind, panelTag, f.openFirewall, pre)
                }
            }
            update(srv.copy(osName = pre.osName.ifBlank { srv.osName }, arch = pre.arch))
            _ui.update { it.copy(install = InstallUi(id, f, InstallStage.READY, pre, plan)) }
        }
    }

    /** Выполнить подготовленную установку. */
    fun runInstall() {
        val i = _ui.value.install?.takeIf { it.stage == InstallStage.READY } ?: return
        val pre = i.preflight ?: return
        val title = when (i.form.kind) {
            ServerKind.AMNEZIAWG -> "Устанавливаю AmneziaWG…"
            ServerKind.OPENFLUX -> "Устанавливаю узел OpenFlux…"
            else -> "Устанавливаю панель ${i.form.kind.panel!!.title}…"
        }
        op(i.serverId, title) { srv, ssh, inst ->
            _ui.update { it.copy(install = i.copy(stage = InstallStage.RUNNING)) }
            val f = i.form
            val res = when (f.kind) {
                ServerKind.AMNEZIAWG -> service.deployAwg(srv, f.generation, f.awgPort, f.openFirewall, pre, inst, ::log)
                ServerKind.OPENFLUX -> service.deployOfx(ssh, srv, f.channel, f.vyandex, f.mailru, f.cupsonline, f.autoupdate, inst, ::log)
                ServerKind.PANEL_3XUI -> service.deployXui(ssh, srv, xuiTag.ifBlank { XuiPanel.latestTag() }, f.accessPort, f.openFirewall, pre, inst, f.sslIp, f.mask, f.transport, ::log)
                ServerKind.PANEL_SUI, ServerKind.PANEL_XUI -> service.deployPanel(srv, f.kind.panel!!,
                    panelTag.ifBlank { OtherPanels.latestTag(OtherPanels.spec(f.kind.panel!!)!!) }, f.openFirewall, pre, inst)
            }
            when (res) {
                is DeployResult.Ok -> {
                    update(res.server)
                    res.links.forEach { importConf(res.server.name, it) }
                    log(when {
                        res.links.isEmpty() -> "Готово: панель установлена — откройте её на карточке сервера"
                        res.links.any { it.startsWith("http", true) } -> "Готово: подписка добавлена в «Серверы» — обновляется сама"
                        else -> "Готово: подключение добавлено в «Серверы»"
                    })
                    _ui.update { it.copy(install = i.copy(stage = InstallStage.DONE)) }
                }
                is DeployResult.Failed -> {
                    _ui.update { it.copy(install = i.copy(stage = InstallStage.FAILED), error = it.error ?: res.reason) }
                }
            }
        }
    }

    fun closeInstall() { if (_ui.value.install?.stage != InstallStage.RUNNING) _ui.update { it.copy(install = null) } }

    // ================================================================================================ Управление

    fun addClient(id: String, name: String) = op(id, "Добавляю устройство «$name»…") { srv, _, inst ->
        val (upd, c) = service.addClient(srv, name, inst) ?: throw OpFailed(_ui.value.error ?: "Не удалось добавить устройство")
        update(upd)
        _ui.update { it.copy(shown = name to upd.awg!!.clientConf(c)) }
    }

    fun removeClient(id: String, name: String) = op(id, "Удаляю устройство «$name»…") { srv, _, inst ->
        update(service.removeClient(srv, name, inst) ?: throw OpFailed(_ui.value.error ?: "Не удалось удалить устройство"))
    }

    fun restartService(id: String, kind: ServerKind) = op(id, "Перезапускаю службу…") { srv, _, inst ->
        val ok = when (kind) {
            ServerKind.AMNEZIAWG -> service.restartAwg(inst)
            ServerKind.OPENFLUX -> service.restartOfx(srv, inst)
            ServerKind.PANEL_3XUI -> service.restartXui(inst)
            ServerKind.PANEL_SUI, ServerKind.PANEL_XUI -> service.restartPanel(kind.panel!!, inst)
        }
        if (!ok) throw OpFailed(_ui.value.error ?: "Служба не перезапустилась")
        log("Служба перезапущена")
    }

    /** HTTPS для панели 3x-ui на своём сервере: сертификат на IP (0.8.1-rc1). */
    fun enablePanelHttps(id: String, openFirewall: Boolean = true) = op(id, "Получаю сертификат для панели…") { srv, ssh, inst ->
        val pre = service.preflight(ssh, 0)
        val upd = service.enableXuiHttps(srv, pre, openFirewall, inst) ?: throw OpFailed(_ui.value.error ?: "Сертификат получить не удалось")
        update(upd)
        log("Готово: панель и подписка работают по HTTPS — ${upd.panel?.baseUrl}")
    }

    fun uninstallService(id: String, kind: ServerKind) = op(id, "Удаляю службу с сервера…") { srv, _, inst ->
        val upd = when (kind) {
            ServerKind.AMNEZIAWG -> service.uninstallAwg(srv, inst)
            ServerKind.OPENFLUX -> service.uninstallOfx(srv, inst)
            else -> service.uninstallPanel(srv, inst)
        }
        update(upd ?: throw OpFailed(_ui.value.error ?: "Не удалось удалить службу"))
        log("Служба удалена с сервера. Подключения к ней в «Серверах» больше не работают — их можно удалить.")
    }

    /** Убрать с сервера всё, что ставила Hydra. Запись и данные входа остаются. */
    fun wipe(id: String) = op(id, "Очищаю сервер…") { srv, _, inst ->
        update(service.wipe(srv, inst) ?: throw OpFailed(_ui.value.error ?: "Очистка не завершилась"))
        log("Сервер очищен: службы, файлы, правила файрвола и копии настроек Hydra удалены.")
        _ui.update { it.copy(status = it.status - srv.id) }
    }

    fun reboot(id: String) = op(id, "Перезагружаю сервер…") { _, ssh, _ ->
        service.reboot(ssh)
        log("Команда перезагрузки отправлена. Сервер будет недоступен 1–2 минуты.")
        _ui.update { it.copy(status = it.status - id) }
    }

    /** Убрать сервер из приложения (на самом сервере ничего не меняется) и забыть данные входа. */
    fun forget(id: String) {
        save(servers().filterNot { it.id == id })
        store.remove(id); memLogins.remove(id); refreshSaved()
        _ui.update { it.copy(status = it.status - id, opServer = if (it.opServer == id) null else it.opServer) }
    }

    /** Сменить данные входа: проверяются входом на сервер, потом сохраняются (или держатся в памяти). */
    fun changeLogin(id: String, target: SshTarget, remember: Boolean) {
        val srv = find(id) ?: return
        _ui.update { it.copy(busy = true, opServer = id, error = null, progress = "Проверяю данные входа…", log = emptyList()) }
        scope.launch(Dispatchers.IO) {
            try {
                SshConnector.connect(target, srv.fingerprint).use { }
                if (remember) { store.save(id, SavedLogin.of(target)); memLogins.remove(id) } else { store.remove(id); memLogins[id] = target }
                if (target.user != srv.sshUser) update(srv.copy(sshUser = target.user))
                refreshSaved()
                log("Данные входа ${if (remember) "сохранены" else "приняты (до закрытия приложения)"}")
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Вход не удался: ${e.message}") }
            }
            _ui.update { it.copy(busy = false, progress = "") }
        }
    }

    fun forgetLogin(id: String) { store.remove(id); memLogins.remove(id); refreshSaved() }

    fun rename(id: String, name: String) { find(id)?.let { if (name.isNotBlank()) update(it.copy(name = name.trim().take(40))) } }

    // ================================================================================================ Панель 3x-ui: по токену API

    private fun withPanel(id: String, work: (MyServer, XuiApi) -> Unit) {
        val srv = find(id)?.takeIf { it.panel != null } ?: return
        if (_ui.value.busy) return
        val panel = srv.panel!!
        _ui.update { it.copy(busy = true, opServer = id, error = null, log = emptyList()) }
        scope.launch(Dispatchers.IO) {
            try { work(srv, XuiApi(DirectXuiTransport(panel.baseUrl, panel.token))) }
            catch (e: XuiApiException) { _ui.update { it.copy(error = "Панель: ${e.message}") } }
            catch (e: Exception) {
                _ui.update { it.copy(error = "Панель недоступна по сети (порт закрыт файрволом?): ${e.message}",
                    status = if (srv.panelOnly) it.status + (id to ServerStatus(error = e.message)) else it.status) }
            }
            _ui.update { it.copy(busy = false, progress = "") }
        }
    }

    /**
     * Подключить уже стоящую панель 3x-ui по адресу и токену — без SSH и без установки (0.8.1-a).
     * Токен проверяется запросом настроек; панель попадает в «Мои серверы» и управляется так же, как поставленная Hydra.
     */
    fun connectPanel(name: String, address: String, token: String) {
        val base = XuiPanel.normalizeUrl(address) ?: return _ui.update { it.copy(error = "Не похоже на адрес панели. Пример: http://203.0.113.5:2053/путь", opServer = null) }
        val tok = token.trim()
        if (tok.isEmpty()) return _ui.update { it.copy(error = "Вставьте токен доступа к панели", opServer = null) }
        _ui.update { it.copy(busy = true, error = null, opServer = null, progress = "Проверяю панель…") }
        scope.launch(Dispatchers.IO) {
            try {
                val api = XuiApi(DirectXuiTransport(base, tok))
                api.settings()   // неверный токен или не та панель — исключение
                val ins = api.inbounds()
                val inboundId = (0 until ins.length()).mapNotNull { ins.optJSONObject(it) }
                    .let { l -> l.firstOrNull { it.optString("protocol") == "vless" } ?: l.firstOrNull() }?.optInt("id") ?: 0
                val host = java.net.URL(base).host
                val panel = PanelInstance(PanelKind.XUI3, base, "", "", tok, "", inboundId, "")
                val srv = MyServer(UUID.randomUUID().toString(), name.ifBlank { "Моя панель" }, host, fingerprint = "", panel = panel)
                save(servers() + srv)
                _ui.update { it.copy(busy = false, progress = "Панель подключена") }
            } catch (e: XuiApiException) {
                _ui.update { it.copy(busy = false, progress = "", error = "Панель ответила отказом: ${e.message}. Проверьте токен.") }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, progress = "", error = "Панель не открывается по адресу $base (${e.message}). Проверьте адрес, порт и что панель включена.") }
            }
        }
    }

    /** Пользователи панели (email) — для списка в карточке. */
    fun loadPanelClients(id: String) = withPanel(id) { s, api ->
        val emails = api.clientEmails()
        _ui.update { it.copy(panelClients = it.panelClients + (s.id to emails)) }
    }

    /** Новый пользователь панели: ссылки показываются в окне, их можно добавить в «Серверы». */
    fun addPanelClient(id: String, email: String) = withPanel(id) { s, api ->
        // инбаунд Hydra; у панели, взятой под управление с другого устройства, его номер неизвестен — первый VLESS
        val inbound = s.panel!!.inboundId.takeIf { it > 0 } ?: api.inbounds().let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.let { l -> l.firstOrNull { it.optString("protocol") == "vless" } ?: l.firstOrNull() }?.optInt("id")
        }?.takeIf { it > 0 } ?: throw XuiApiException("В панели нет ни одного инбаунда — создайте его на сайте панели")
        val subId = XuiPanel.random(16).lowercase()
        // flow «xtls-rprx-vision» — только у TCP(RAW)-инбаундов; XHTTP-инбаунд Hydra (0.8.1-rc1) его не принимает
        val flow = if (api.inbounds().let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
                .firstOrNull { it.optInt("id") == inbound }?.optString("streamSettings")?.contains("\"tcp\"") == true) "xtls-rprx-vision" else ""
        api.addClient(email, subId, listOf(inbound), flow = flow)
        _ui.update { it.copy(shown = email to accessText(s, api, email, subId), panelClients = it.panelClients + (s.id to api.clientEmails())) }
    }

    /** Ссылки уже созданного пользователя панели. */
    fun showPanelLinks(id: String, email: String) = withPanel(id) { s, api ->
        _ui.update { it.copy(shown = email to accessText(s, api, email, api.subIdOf(email))) }
    }

    /** Что выдать пользователю: адрес подписки (обновляется сам, 0.8.1-rc1), а если подписка в панели выключена — ссылки. */
    private fun accessText(s: MyServer, api: XuiApi, email: String, subId: String?): String {
        val host = runCatching { java.net.URL(s.panel!!.baseUrl).host }.getOrNull()?.ifBlank { null } ?: s.host
        subId?.let { id -> runCatching { api.subscriptionUrl(host.removePrefix("[").removeSuffix("]"), id) }.getOrNull() }?.let { return it }
        val links = api.links(email)
        if (links.isEmpty()) throw XuiApiException("Панель не выдала ссылок для $email")
        return links.joinToString("\n")
    }

    fun removePanelClient(id: String, email: String) = withPanel(id) { s, api ->
        api.delClient(email)
        _ui.update { it.copy(panelClients = it.panelClients + (s.id to api.clientEmails())) }
    }

    fun clientConf(id: String, clientName: String): String? {
        val s = find(id) ?: return null
        val c = s.clients.firstOrNull { it.name == clientName }?.takeIf { it.privateKey.isNotEmpty() } ?: return null
        return s.awg?.clientConf(c)
    }

    fun ofxLink(id: String): String? = find(id)?.let { s -> s.ofx?.shareLink(s.name) }

    fun showConf(name: String, conf: String) = _ui.update { it.copy(shown = name to conf) }
    fun hideShown() = _ui.update { it.copy(shown = null) }
    fun clearError() = _ui.update { it.copy(error = null) }

    companion object {
        /**
         * Проверка адреса без сети: IPv4, IPv6 или имя хоста. Раньше здесь был `InetAddress.getByName` — на Android из потока
         * интерфейса он падал (NetworkOnMainThreadException), и имя хоста вместо IP считалось ошибкой.
         */
        fun validHost(h: String): Boolean {
            if (h.isBlank() || h.length > 253 || h.any { it.isWhitespace() || it == '/' }) return false
            if (IPV4.matches(h)) return h.split('.').all { (it.toIntOrNull() ?: 256) <= 255 }
            if (':' in h) return h.removePrefix("[").removeSuffix("]").all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' } && h.count { it == ':' } >= 2
            return HOST.matches(h) && !h.all { it.isDigit() || it == '.' }
        }
        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        private val HOST = Regex("""^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\.?$""")
    }
}

/** Обёртка хранилища входа: исключения не выходят наружу, при сбое записи вход держится в памяти. */
private class SafeVault(private val inner: CredentialVault) : CredentialVault {
    private val fallback = MemoryVault()
    override fun load(id: String) = fallback.load(id) ?: runCatching { inner.load(id) }.getOrNull()
    override fun save(id: String, login: SavedLogin) {
        if (runCatching { inner.save(id, login) }.isSuccess) fallback.remove(id) else fallback.save(id, login)
    }
    override fun remove(id: String) { fallback.remove(id); runCatching { inner.remove(id) } }
    override fun ids(): Set<String> = fallback.ids() + runCatching { inner.ids() }.getOrDefault(emptySet())
}
