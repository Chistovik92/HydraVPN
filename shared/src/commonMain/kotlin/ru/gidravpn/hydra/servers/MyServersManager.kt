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
    AMNEZIAWG, OPENFLUX, PANEL_3XUI, PANEL_SUI, PANEL_XUI, PANEL_PASARGUARD, PANEL_REMNAWAVE;

    /** Вид панели для служб-панелей; null — не панель. */
    val panel: PanelKind? get() = when (this) { PANEL_3XUI -> PanelKind.XUI3; PANEL_SUI -> PanelKind.SUI; PANEL_XUI -> PanelKind.XUI; PANEL_PASARGUARD -> PanelKind.PASARGUARD; PANEL_REMNAWAVE -> PanelKind.REMNAWAVE; else -> null }

    companion object {
        fun ofPanel(k: PanelKind) = when (k) { PanelKind.XUI3 -> PANEL_3XUI; PanelKind.SUI -> PANEL_SUI; PanelKind.XUI -> PANEL_XUI; PanelKind.PASARGUARD -> PANEL_PASARGUARD; PanelKind.REMNAWAVE -> PANEL_REMNAWAVE }
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
    /** HTTPS панели сертификатом Let's Encrypt на IP (3x-ui — 0.8.1-rc1, s-ui и x-ui — 0.8.1-rc2). */
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
    /** Отпечаток, под которым этот адрес был закреплён раньше, если сейчас сервер показал другой (смена ключей или подмена). */
    val previousFingerprint: String = "",
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
    /** Открытые туннели к закрытым извне панелям (0.8.1-rc2): id сервера → адрес панели на этом устройстве. */
    val tunnels: Map<String, String> = emptyMap(),
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
                .onSuccess { fp ->
                    val old = servers().firstOrNull { it.host.equals(f.target.host, true) && it.sshPort == f.target.port }?.fingerprint.orEmpty()
                    _ui.update { it.copy(stage = Stage.CONFIRM_FINGERPRINT, busy = false, fingerprint = fp, progress = "",
                        previousFingerprint = if (old.isNotBlank() && old != fp) old else "") }
                }
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
                    service.planPreviewPanel(spec.kind, panelTag, f.openFirewall, pre, f.sslIp, srv.host)
                }
                ServerKind.PANEL_PASARGUARD -> service.planPreviewPasarGuard()
                ServerKind.PANEL_REMNAWAVE -> service.planPreviewRemnawave(srv.host)
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
                    panelTag.ifBlank { OtherPanels.latestTag(OtherPanels.spec(f.kind.panel!!)!!) }, f.openFirewall, pre, inst, f.sslIp, ::log)
                ServerKind.PANEL_PASARGUARD -> service.deployPasarGuard(ssh, srv, inst, ::log)
                ServerKind.PANEL_REMNAWAVE -> service.deployRemnawave(ssh, srv, inst, ::log)
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
                    res.partial?.let { update(it) }   // панель стоит на сервере — вход не теряем
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
            ServerKind.PANEL_SUI, ServerKind.PANEL_XUI, ServerKind.PANEL_PASARGUARD, ServerKind.PANEL_REMNAWAVE -> service.restartPanel(kind.panel!!, inst)
        }
        if (!ok) throw OpFailed(_ui.value.error ?: "Служба не перезапустилась")
        log("Служба перезапущена")
    }

    /** HTTPS для панели на своём сервере: сертификат на IP (3x-ui — 0.8.1-rc1, s-ui и x-ui — 0.8.1-rc2). */
    fun enablePanelHttps(id: String, openFirewall: Boolean = true) = op(id, "Получаю сертификат для панели…") { srv, ssh, inst ->
        val pre = service.preflight(ssh, 0)
        val upd = service.enablePanelHttps(srv, pre, openFirewall, inst) ?: throw OpFailed(_ui.value.error ?: "Сертификат получить не удалось")
        update(upd)
        log("Готово: панель и подписка работают по HTTPS — ${upd.panel?.baseUrl}")
    }

    /**
     * Закрыть порт панели извне ([local] = true) или открыть снова (0.8.1-rc2). Закрытая панель открывается через SSH-туннель
     * ([openPanelTunnel]); пользователи 3x-ui — по SSH.
     */
    fun setPanelLocal(id: String, local: Boolean) = op(id, if (local) "Закрываю панель извне…" else "Открываю панель для входа извне…") { srv, ssh, inst ->
        closePanelTunnel(id)
        val pre = service.preflight(ssh, 0)
        val upd = service.setPanelLocal(srv, local, pre, inst) ?: throw OpFailed(_ui.value.error ?: "Не получилось изменить доступ к панели")
        update(upd)
        log(if (local) "Готово: панель доступна только с самого сервера. Сайт панели Hydra откроет через защищённый туннель."
            else "Готово: панель снова открывается по адресу ${upd.panel?.baseUrl}")
    }

    // ================================================================================================ Туннель к закрытой панели

    private val tunnels = java.util.concurrent.ConcurrentHashMap<String, SshSession>()

    /**
     * Открыть туннель к панели, закрытой извне (0.8.1-rc2): порт 127.0.0.1 на устройстве ведёт на 127.0.0.1:порт панели на
     * сервере по SSH. Адрес для браузера или WebView — в [MyServersUi.tunnels]. Туннель живёт до [closePanelTunnel].
     */
    fun openPanelTunnel(id: String) {
        val srv = find(id)?.takeIf { it.panel != null && it.fingerprint.isNotBlank() } ?: return
        if (tunnels.containsKey(id) || _ui.value.busy) return
        val t = store.load(id)?.target(srv) ?: memLogins[id]
        if (t == null) { askLogin(id, "Открываю туннель к панели…", null) { openTunnelWith(id, it) }; return }
        openTunnelWith(id, t)
    }

    private fun openTunnelWith(id: String, t: SshTarget) {
        val srv = find(id) ?: return
        val panel = srv.panel ?: return
        _ui.update { it.copy(busy = true, opServer = id, error = null, progress = "Открываю защищённый туннель к панели…") }
        scope.launch(Dispatchers.IO) {
            try {
                val ssh = SshConnector.connect(t, srv.fingerprint)
                val port = try { ssh.forwardLocal(PanelWeb.remotePort(panel.baseUrl)) } catch (e: Exception) { ssh.close(); throw e }
                tunnels.put(id, ssh)?.close()
                _ui.update { it.copy(busy = false, progress = "", tunnels = it.tunnels + (id to PanelWeb.tunnelUrl(panel.baseUrl, port))) }
            } catch (e: Exception) {
                if (isAuthError(e)) {
                    store.remove(id); memLogins.remove(id); refreshSaved()
                    _ui.update { it.copy(busy = false, progress = "") }
                    askLogin(id, "Открываю туннель к панели…", "Сервер не принял сохранённые данные входа — введите новые") { openTunnelWith(id, it) }
                    return@launch
                }
                _ui.update { it.copy(busy = false, progress = "", error = "Туннель к панели не открылся: ${e.message}") }
            }
        }
    }

    /** Закрыть туннель к панели (экран панели закрыт, панель снова открыта извне, сервер забыт). */
    fun closePanelTunnel(id: String) {
        tunnels.remove(id)?.let { runCatching { it.close() } }
        if (id in _ui.value.tunnels) _ui.update { it.copy(tunnels = it.tunnels - id) }
    }

    /** Закрыть все туннели (выход из приложения). */
    fun closeAllTunnels() = tunnels.keys.toList().forEach(::closePanelTunnel)

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
        closePanelTunnel(id)
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
        // порт панели закрыт извне (0.8.1-rc2): API — curl на самом сервере по SSH
        if (panel.local && srv.fingerprint.isNotBlank()) return op(id, "Обращаюсь к панели через сервер…") { s, ssh, inst ->
            val p = s.panel ?: return@op
            inst.addSecret(p.token)
            val u = java.net.URL(p.baseUrl)
            try { work(s, XuiApi(SshXuiTransport(ssh, "${u.protocol}://127.0.0.1:${u.port}${u.path}", p.token, u.host.removePrefix("[").removeSuffix("]")))) }
            catch (e: XuiApiException) { throw OpFailed("Панель: ${e.message}") }
        }
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

    /**
     * Создать подключение любого типа в панели 3x-ui (0.8.2-a2, [XuiConnection]): инбаунд + основной пользователь панели
     * подключается к нему — его подписка в «Серверах» получает подключение сама. Ссылка показывается в окне (её можно добавить и
     * отдельно). Hysteria 2 и TUIC — с сертификатом панели (`webCertFile`; «Включить HTTPS»). Есть вход по SSH — порт открывается
     * в файрволе (UDP для WireGuard, AmneziaWG, Hysteria 2, TUIC) и API идёт по SSH; иначе — напрямую по токену.
     */
    fun createPanelConnection(id: String, type: XuiConnection, port: Int, mask: XuiPanel.RealityMask = XuiPanel.RealityMask.VK) {
        if (find(id)?.panel?.kind == PanelKind.XUI) return createXuiAlirezaConnection(id, type, port, mask)
        if (find(id)?.panel?.kind == PanelKind.SUI) return createSuiConnection(id, type, port, mask)
        if (find(id)?.panel?.kind?.tunnelOnly == true) return createPasarGuardConnection(id, type, port, mask)
        val srv = find(id)?.takeIf { it.panel?.kind == PanelKind.XUI3 && it.panel.token.isNotBlank() } ?: return
        if (port !in 1..65535) return _ui.update { it.copy(error = "Порт — число от 1 до 65535", opServer = id) }
        fun create(s: MyServer, api: XuiApi): Pair<String, List<String>> {
            val host = runCatching { java.net.URL(s.panel!!.baseUrl).host }.getOrNull()?.removePrefix("[")?.removeSuffix("]")?.ifBlank { null } ?: s.host
            val settings = api.settings()
            val cert = if (!type.needsCert) null else {
                val crt = settings.optString("webCertFile"); val key = settings.optString("webKeyFile")
                if (crt.isBlank() || key.isBlank()) throw XuiApiException("Для ${type.title} нужен сертификат сервера: сначала нажмите «Включить HTTPS» у панели")
                XuiConnection.Companion.Cert(crt, key, host)
            }
            val keys = if (type.reality) api.newRealityKeys() else ("" to "")
            if (api.inbounds().let { a -> (0 until a.length()).any { a.getJSONObject(it).optInt("port") == port } })
                throw XuiApiException("Порт $port уже занят другим подключением панели — выберите другой")
            val inboundId = api.addInbound(XuiConnection.inbound(type, port, "Hydra ${type.title}", mask, keys.first, keys.second, cert))
            // основной пользователь: созданный мастером, иначе первый в панели, иначе новый
            val email = s.panel!!.clientEmail.takeIf { e -> e.isNotBlank() && e in api.clientEmails() } ?: api.clientEmails().firstOrNull()
                ?: "hydra-1".also { api.addClient(it, XuiPanel.random(16).lowercase(), listOf(inboundId), flow = "") }
            api.attachClient(email, listOf(inboundId))
            val links = api.links(email).filter { l -> ":$port" in l.substringBefore("#") || linkBodyHasPort(l, port) }
            return email to links
        }
        fun show(email: String, links: List<String>, fw: String) = _ui.update {
            it.copy(shown = "${type.title} · $email" to (links.joinToString("\n").ifBlank { "Подключение создано; ссылку панель выдаст в подписке пользователя $email." }),
                progress = "", panelClients = it.panelClients - id, log = it.log + listOfNotNull("Создано подключение ${type.title} на порту $port — подписка пользователя $email обновится сама", fw.ifBlank { null }))
        }
        if (srv.fingerprint.isNotBlank()) return op(id, "Создаю подключение ${type.title}…") { s, ssh, inst ->
            val p = s.panel!!
            inst.addSecret(p.token)
            val u = java.net.URL(p.baseUrl)
            val api = XuiApi(SshXuiTransport(ssh, "${u.protocol}://127.0.0.1:${u.port}${u.path}", p.token, u.host.removePrefix("[").removeSuffix("]")))
            val (email, links) = try { create(s, api) } catch (e: XuiApiException) { throw OpFailed("Панель: ${e.message}") }
            val pre = service.preflight(ssh, 0)
            val fwStep = ServerOps.openPortStep(pre.firewall, "$port/${if (type.udp) "udp" else "tcp"}")
            if (fwStep != null) inst.run(listOf(fwStep))
            show(email, links, if (fwStep != null) "Порт $port/${if (type.udp) "udp" else "tcp"} открыт в файрволе" else "")
        }
        withPanel(id) { s, api -> val (email, links) = create(s, api); show(email, links, "Откройте порт $port/${if (type.udp) "udp" else "tcp"} в файрволе хостинга, если он закрыт") }
    }

    /**
     * Подключение в x-ui (alireza0, 0.8.2-a2): по SSH (API x-ui — cookie-сессией на самом сервере). Учётные данные придумывает
     * Hydra; все подключения Hydra в этой панели — у одного пользователя-подписки ([OtherPanelConnections.subId]), подписку панели
     * Hydra включает сама (порт 2096). Ссылка — из этой подписки; WireGuard x-ui в подписку не кладёт — конфиг собирает Hydra.
     */
    private fun createXuiAlirezaConnection(id: String, type: XuiConnection, port: Int, mask: XuiPanel.RealityMask) {
        val srv0 = find(id)?.takeIf { it.fingerprint.isNotBlank() } ?: return
        if (type !in OtherPanelConnections.XUI) return _ui.update { it.copy(error = "${type.title} в x-ui нет — выберите другой тип", opServer = id) }
        if (port !in 1..65535) return _ui.update { it.copy(error = "Порт — число от 1 до 65535", opServer = id) }
        op(id, "Создаю подключение ${type.title} в x-ui…") { s, ssh, inst ->
            val p = s.panel ?: throw OpFailed("Панели нет")
            inst.addSecret(p.password)
            val db = OtherPanels.dbPath(PanelKind.XUI)
            fun setting(k: String) = ssh.exec("sqlite3 $db \"select value from settings where key='$k'\" 2>/dev/null").out.trim()
            // sqlite3 нужен и для чтения настроек — ставится тем же шагом, что пишет их
            inst.run(listOf(Step("xui-sqlite", "Проверить доступ к настройкам x-ui", OtherPanels.settingsCommand(PanelKind.XUI, emptyMap()) + " >/dev/null; command -v sqlite3",
                failHint = "Не удалось поставить sqlite3")))
            if (setting("subEnable") != "true") {
                if (!inst.run(listOf(Step("xui-sub", "Включить подписку x-ui (порт 2096): по ней Hydra забирает подключения",
                        OtherPanels.settingsCommand(PanelKind.XUI, mapOf("subEnable" to "true")) + " && systemctl restart x-ui && sleep 3 && systemctl is-active x-ui",
                        accept = { r -> r.out.lineSequence().any { it.trim() == "active" } }, failHint = "x-ui не запустилась")))) throw OpFailed(_ui.value.error ?: "Подписку x-ui включить не удалось")
            }
            val host = runCatching { java.net.URL(p.baseUrl).host }.getOrNull()?.removePrefix("[")?.removeSuffix("]")?.ifBlank { null } ?: s.host
            val cert = if (!type.needsCert) null else {
                val crt = setting("webCertFile"); val key = setting("webKeyFile")
                if (crt.isBlank() || key.isBlank()) throw OpFailed("Для ${type.title} нужен сертификат сервера: сначала нажмите «Включить HTTPS» у панели")
                XuiConnection.Companion.Cert(crt, key, host)
            }
            SessionPanelApi(ssh, p).use { api ->
                try {
                    api.login()
                    if (api.xuiInbounds().let { a -> (0 until a.length()).any { a.getJSONObject(it).optInt("port") == port } })
                        throw XuiApiException("Порт $port уже занят другим подключением панели — выберите другой")
                    val keys = if (type.reality) {
                        val out = ssh.exec("/usr/local/x-ui/bin/xray-linux-* x25519 2>/dev/null").out
                        val priv = Regex("(?i)private\\s*key:\\s*(\\S+)").find(out)?.groupValues?.get(1)
                        val pub = Regex("(?i)(public\\s*key|password)[^:]*:\\s*(\\S+)").find(out)?.groupValues?.get(2)
                        if (priv == null || pub == null) throw XuiApiException("Не удалось получить ключи REALITY от Xray панели")
                        priv to pub
                    } else ("" to "")
                    val creds = OtherPanelConnections.newCreds(type, OtherPanelConnections.subId(p))
                    inst.addSecret(creds.uuid); inst.addSecret(creds.password); inst.addSecret(creds.ssKey); inst.addSecret(creds.wgPriv)
                    val ib = OtherPanelConnections.xuiInbound(type, port, creds, mask, keys.first, keys.second, cert)
                    api.xuiAddInbound(ib)
                    val pre = service.preflight(ssh, 0)
                    val proto = if (type.udp) "udp" else "tcp"
                    ServerOps.openPortStep(pre.firewall, "$port/$proto")?.let { inst.run(listOf(it)) }
                    ServerOps.openPortStep(pre.firewall, "2096/tcp")?.let { inst.run(listOf(it)) }
                    Thread.sleep(3000)   // x-ui применяет инбаунд перезапуском Xray
                    val subHttps = setting("subCertFile").isNotBlank()
                    val subUrl = "${if (subHttps) "https" else "http"}://${if (':' in host) "[$host]" else host}:2096/sub/${creds.subId}"
                    val links = if (type == XuiConnection.WIREGUARD) listOf(OtherPanelConnections.wireguardConf(ib.getJSONObject("settings").getString("secretKey"), host, port, creds))
                        else api.subscription(2096, "/sub/", creds.subId, subHttps).filter { l -> ":$port" in l.substringBefore("#") || linkBodyHasPort(l, port) }
                    _ui.update { it.copy(shown = "${type.title} · x-ui" to links.joinToString("\n").ifBlank { subUrl },
                        log = it.log + "Создано подключение ${type.title} на порту $port/$proto. Подписка всех подключений Hydra в этой панели: $subUrl") }
                } catch (e: XuiApiException) { throw OpFailed("Панель: ${e.message}") }
            }
        }
    }

    /**
     * Подключение в s-ui (0.8.2-a2): по SSH (API s-ui — cookie-сессией на сервере). Объект TLS (REALITY — ключами от самой s-ui,
     * или сертификат панели для Hysteria 2 / TUIC) → инбаунд sing-box → пользователь с учётными данными Hydra; ссылку s-ui
     * считает сама (`links` пользователя).
     */
    private fun createSuiConnection(id: String, type: XuiConnection, port: Int, mask: XuiPanel.RealityMask) {
        if (find(id)?.fingerprint.isNullOrBlank()) return
        if (type !in OtherPanelConnections.SUI) return _ui.update { it.copy(error = "${type.title} в s-ui нет — выберите другой тип", opServer = id) }
        if (port !in 1..65535) return _ui.update { it.copy(error = "Порт — число от 1 до 65535", opServer = id) }
        op(id, "Создаю подключение ${type.title} в s-ui…") { s, ssh, inst ->
            val p = s.panel ?: throw OpFailed("Панели нет")
            inst.addSecret(p.password)
            val host = runCatching { java.net.URL(p.baseUrl).host }.getOrNull()?.removePrefix("[")?.removeSuffix("]")?.ifBlank { null } ?: s.host
            SessionPanelApi(ssh, p).use { api ->
                try {
                    api.login()
                    val state = api.suiLoad()
                    val inbounds = state.optJSONArray("inbounds") ?: org.json.JSONArray()
                    if ((0 until inbounds.length()).any { inbounds.getJSONObject(it).optInt("listen_port") == port })
                        throw XuiApiException("Порт $port уже занят другим подключением панели — выберите другой")
                    val creds = OtherPanelConnections.newCreds(type, OtherPanelConnections.subId(p))
                    inst.addSecret(creds.uuid); inst.addSecret(creds.password); inst.addSecret(creds.ssKey)
                    val tag = creds.email
                    // TLS: REALITY или сертификат панели
                    val tlsId = when {
                        type.reality -> {
                            val kp = org.json.JSONArray(api.suiRaw("api/keypairs?k=reality").let { org.json.JSONObject(it).opt("obj").toString() })
                            val priv = (0 until kp.length()).map { kp.getString(it) }.firstOrNull { it.startsWith("PrivateKey") }?.substringAfter(":")?.trim()
                            val pub = (0 until kp.length()).map { kp.getString(it) }.firstOrNull { it.startsWith("PublicKey") }?.substringAfter(":")?.trim()
                            if (priv == null || pub == null) throw XuiApiException("s-ui не выдала ключи REALITY")
                            val sid = XuiPanel.random(8).lowercase().map { "%02x".format(it.code % 256) }.joinToString("").take(16)
                            api.suiSave("tls", "new", OtherPanelConnections.suiRealityTls(tag, mask.sni, priv, pub, sid))
                            suiId(api, "tls", tag)
                        }
                        type.needsCert -> {
                            val db = OtherPanels.dbPath(PanelKind.SUI)
                            fun setting(k: String) = ssh.exec("sqlite3 $db \"select value from settings where key='$k'\" 2>/dev/null").out.trim()
                            val crt = setting("webCertFile"); val key = setting("webKeyFile")
                            if (crt.isBlank() || key.isBlank()) throw XuiApiException("Для ${type.title} нужен сертификат сервера: сначала нажмите «Включить HTTPS» у панели")
                            api.suiSave("tls", "new", OtherPanelConnections.suiCertTls(tag, XuiConnection.Companion.Cert(crt, key, host), listOf("h3")))
                            suiId(api, "tls", tag)
                        }
                        else -> 0
                    }
                    api.suiSave("inbounds", "new", OtherPanelConnections.suiInbound(type, tag, port, tlsId, creds.ssKey))
                    val inboundId = suiId(api, "inbounds", tag, key = "tag")
                    api.suiSave("clients", "new", OtherPanelConnections.suiClient(creds, inboundId))
                    val pre = service.preflight(ssh, 0)
                    val proto = if (type.udp) "udp" else "tcp"
                    ServerOps.openPortStep(pre.firewall, "$port/$proto")?.let { inst.run(listOf(it)) }
                    Thread.sleep(2000)
                    // список пользователей в api/load — без ссылок; целиком (со ссылками) — api/clients?id=
                    val cid = suiId(api, "clients", creds.email)
                    val client = runCatching { org.json.JSONObject(api.suiRaw("api/clients?id=$cid")).optJSONObject("obj")?.optJSONArray("clients")?.optJSONObject(0) }.getOrNull()
                    val links = client?.let(OtherPanelConnections::suiLinks).orEmpty().filter { l -> ":$port" in l.substringBefore("#") || linkBodyHasPort(l, port) }
                    _ui.update { it.copy(shown = "${type.title} · s-ui" to links.joinToString("\n").ifBlank { "Подключение создано; ссылка — на сайте панели у пользователя ${creds.email}" },
                        log = it.log + "Создано подключение ${type.title} на порту $port/$proto (пользователь ${creds.email})") }
                } catch (e: XuiApiException) { throw OpFailed("Панель: ${e.message}") }
                catch (e: org.json.JSONException) { throw OpFailed("Панель ответила не так, как ожидалось: ${e.message}") }
            }
        }
    }

    /**
     * Подключение в PasarGuard (0.8.2-a3) по SSH: инбаунд в ядро ноды, хост, группа и пользователь «hydra» ([PasarGuardApi.createConnection]).
     * Панель видна только с самого сервера, поэтому ссылки (а не адрес подписки) сразу добавляются в «Серверы».
     */
    private fun createPasarGuardConnection(id: String, type: XuiConnection, port: Int, mask: XuiPanel.RealityMask) {
        if (find(id)?.fingerprint.isNullOrBlank()) return
        val kindTitle = find(id)?.panel?.kind?.title ?: "панели"
        if (type !in OtherPanelConnections.typesFor(find(id)?.panel?.kind ?: PanelKind.PASARGUARD)) return _ui.update { it.copy(error = "${type.title} в $kindTitle из Hydra пока не создаётся — выберите другой тип", opServer = id) }
        if (port !in 1..65535) return _ui.update { it.copy(error = "Порт — число от 1 до 65535", opServer = id) }
        op(id, "Создаю подключение ${type.title} в $kindTitle…") { s, ssh, inst ->
            val p = s.panel ?: throw OpFailed("Панели нет")
            inst.addSecret(p.password)
            val rw = p.kind == PanelKind.REMNAWAVE
            val links = try { if (rw) RemnawaveApi(ssh, p.username, p.password).createConnection(type, port, s.host, mask)
                else PasarGuardApi(ssh, p.username, p.password).createConnection(type, port, s.host, mask) }
                catch (e: XuiApiException) { throw OpFailed("Панель: ${e.message}") }
                catch (e: org.json.JSONException) { throw OpFailed("Панель ответила не так, как ожидалось: ${e.message}") }
            val proto = if (type.udp) "udp" else "tcp"
            val pre = service.preflight(ssh, 0)
            ServerOps.openPortStep(pre.firewall, "$port/$proto")?.let { st ->
                inst.run(listOf(st, Step("pg-port", "Запомнить порт подключения $kindTitle", "echo '$port/$proto' >> ${if (rw) RemnawavePanel.PORTS else PasarGuardPanel.PORTS}")))
            }
            links.forEach { importConf(s.name, it) }
            _ui.update { it.copy(shown = "${type.title} · $kindTitle" to links.joinToString("\n").ifBlank { "Подключение создано; ссылка — на сайте панели у пользователя hydra" },
                log = it.log + "Создано подключение ${type.title} на порту $port/$proto (пользователь hydra)" +
                    (if (links.isNotEmpty()) "; ссылка добавлена в «Серверы»" else "")) }
        }
    }

    // ================================================================================================ Ноды панелей (0.8.2-a4)

    /** Серверы, которые можно сделать нодой панели [mainId]: со входом по SSH, без своих служб и не ноды другой панели. */
    fun nodeCandidates(mainId: String): List<MyServer> = servers().filter { it.id != mainId && it.fingerprint.isNotBlank() && !it.hasServices }

    /**
     * Добавить ноду (0.8.2-a4): сервер [nodeServerId] из «Моих серверов» становится нодой панели сервера [mainId] —
     * 3x-ui (сама панель-нода, токен `node-sync`), PasarGuard (`pg-node`) или Remnawave (`remnanode`). Порты ноды открываются
     * только для главного сервера. Ссылки подключений на ноде сразу попадают в «Серверы».
     */
    fun addPanelNode(mainId: String, nodeServerId: String) {
        val main = find(mainId)?.takeIf { it.panel != null && it.fingerprint.isNotBlank() } ?: return
        val kind = main.panel!!.kind
        if (!PanelNodes.supports(kind)) return _ui.update { it.copy(error = "У панели ${kind.title} нет нод", opServer = mainId) }
        val nodeSrv = find(nodeServerId)?.takeIf { it.id != mainId && it.fingerprint.isNotBlank() } ?: return
        if (nodeSrv.hasServices) return _ui.update { it.copy(error = "На сервере «${nodeSrv.name}» уже есть службы Hydra — для ноды нужен свободный сервер", opServer = mainId) }
        if (_ui.value.busy) return
        val tB = store.load(nodeServerId)?.target(nodeSrv) ?: memLogins[nodeServerId]
        if (tB == null) { askLogin(nodeServerId, "Вход на сервер ноды «${nodeSrv.name}»…", null) { addPanelNode(mainId, nodeServerId) }; return }
        op(mainId, "Добавляю ноду «${nodeSrv.name}» к панели ${kind.title}…") { srv, sshA, instA ->
            val p = srv.panel ?: throw OpFailed("Панели нет")
            instA.addSecret(p.password); instA.addSecret(p.token)
            SshConnector.connect(tB, nodeSrv.fingerprint).use { sshB ->
                val instB = Installer(sshB, listOfNotNull(tB.password, tB.privateKey, tB.keyPassphrase, p.password, p.token), ::onEvent).also { installer = it }
                val preB = service.preflight(sshB, 0)
                // адрес главного сервера, каким его видит нода, — для правила файрвола «только с главного»
                val mainIp = sshB.exec(PanelNodes.resolveCommand(srv.host)).out.trim().lines().firstOrNull().orEmpty()
                fun openFrom(port: String) { if (mainIp.isNotBlank()) ServerOps.openPortFromStep(preB.firewall, port, mainIp)?.let { instB.run(listOf(it)) } }
                fun openAll(port: String) { ServerOps.openPortStep(preB.firewall, port)?.let { instB.run(listOf(it)) } }
                val name = PanelNodes.nodeName(nodeSrv.host)
                val (node, links) = try {
                    when (kind) {
                        PanelKind.XUI3 -> addXuiNode(srv, sshA, nodeSrv, sshB, instB, preB, name, ::openFrom, ::openAll)
                        PanelKind.PASARGUARD -> {
                            val key = UUID.randomUUID().toString(); instB.addSecret(key)
                            if (!instB.run(PanelNodes.pgNodeSteps(key, nodeSrv.host))) throw OpFailed(_ui.value.error ?: "Нода PasarGuard не установилась")
                            PanelNodes.PG_NODE_PORTS.split(',').forEach { openFrom("$it/tcp") }
                            val ca = sshB.exec("cat ${PasarGuardPanel.NODE_CERT}").out.trim()
                            val api = PasarGuardApi(sshA, p.username, p.password)
                            val id = api.addRemoteNode(name, nodeSrv.host, key, ca)
                            api.waitNodeConnected(id)?.let { err ->
                                runCatching { api.removeNode(id, nodeSrv.host) }; instB.run(listOf(Step("pgn-undo", "Убрать неподключившуюся ноду", PanelNodes.pgNodeUninstallCommand())))
                                throw OpFailed("Нода PasarGuard не подключилась к панели ($err). Порты 62050–62051/tcp ноды должны быть доступны с главного сервера")
                            }
                            log("Нода подключена к панели")
                            val ports = api.addNodeHosts(nodeSrv.host, name).map { "$it/tcp" }
                            ports.forEach { openAll(it) }
                            PanelNode(nodeSrv.id, nodeSrv.name, nodeSrv.host, id.toString(), ports) to retryLinks { linksToHost(api.userLinks(), nodeSrv.host) }
                        }
                        PanelKind.REMNAWAVE -> {
                            val api = RemnawaveApi(sshA, p.username, p.password)
                            val key = api.secretKey(); instB.addSecret(key)
                            if (!instB.run(PanelNodes.rwNodeSteps(key))) throw OpFailed(_ui.value.error ?: "Нода Remnawave не запустилась")
                            openFrom("${RemnawavePanel.NODE_PORT}/tcp")
                            val uuid = api.addRemoteNode(name, nodeSrv.host)
                            api.waitNodeConnected(uuid)?.let { err ->
                                runCatching { api.removeNode(uuid, nodeSrv.host) }; instB.run(listOf(Step("rwn-undo", "Убрать неподключившуюся ноду", PanelNodes.rwNodeUninstallCommand())))
                                throw OpFailed("Нода Remnawave не подключилась к панели ($err). Порт ${RemnawavePanel.NODE_PORT}/tcp ноды должен быть доступен с главного сервера")
                            }
                            log("Нода подключена к панели")
                            val ports = api.addNodeHosts(nodeSrv.host, name).map { "$it/tcp" }
                            ports.forEach { openAll(it) }
                            PanelNode(nodeSrv.id, nodeSrv.name, nodeSrv.host, uuid, ports) to retryLinks { linksToHost(api.userLinks(), nodeSrv.host) }
                        }
                        else -> throw OpFailed("У панели ${kind.title} нет нод")
                    }
                } catch (e: XuiApiException) { throw OpFailed("Панель: ${e.message}") }
                catch (e: org.json.JSONException) { throw OpFailed("Панель ответила не так, как ожидалось: ${e.message}") }
                update(srv.copy(panel = p.copy(nodes = p.nodes.filterNot { it.serverId == node.serverId } + node)))
                find(nodeSrv.id)?.let { update(it.copy(nodeOf = NodeRole(kind, srv.id, srv.name))) }
                links.forEach { importConf("${srv.name} · ${nodeSrv.name}", it) }
                _ui.update { it.copy(shown = if (links.isEmpty()) null else "Нода ${nodeSrv.name}" to links.joinToString("\n"),
                    log = it.log + ("Готово: нода «${nodeSrv.name}» подключена к панели ${kind.title}" +
                        if (links.isNotEmpty()) "; подключения на ней добавлены в «Серверы»" else "; создайте подключение на карточке панели")) }
            }
        }
    }

    /** Ссылки появляются в подписке не сразу (панель перезапускает ядро ноды) — несколько попыток. */
    private fun retryLinks(get: () -> List<String>): List<String> {
        var l = emptyList<String>()
        repeat(6) { l = runCatching(get).getOrDefault(emptyList()); if (l.isNotEmpty()) return l; Thread.sleep(3000) }
        return l
    }

    /** 3x-ui: на ноде — своя 3x-ui (её ставит Hydra), главной панели — токен `node-sync`; на ноде — подключение VLESS + gRPC + REALITY. */
    private fun addXuiNode(main: MyServer, sshA: SshSession, nodeSrv: MyServer, sshB: SshSession, instB: Installer, preB: Preflight, name: String,
                           openFrom: (String) -> Unit, openAll: (String) -> Unit): Pair<PanelNode, List<String>> {
        val p = main.panel!!
        if (sshB.exec("test -e /usr/local/x-ui/x-ui && echo yes").out.trim() != "yes") {
            _ui.update { it.copy(progress = "Узнаю последнюю версию 3x-ui…") }
            val params = XuiPanel.InstallParams(xuiTag.ifBlank { XuiPanel.latestTag() })
            instB.addSecret(params.password); instB.addSecret(params.username)
            if (!instB.run(XuiPanel.installSteps(params, false, preB.firewall))) throw OpFailed(_ui.value.error ?: "3x-ui на ноде не установилась")
        } else if (sshB.exec("test -f ${ServerOps.XUI_MARK} && echo yes").out.trim() != "yes")
            throw OpFailed("На сервере ноды уже стоит 3x-ui, поставленная не Hydra, — удалите её или выберите другой сервер")
        val res = XuiPanel.parseResult(sshB.exec("cat ${XuiPanel.RESULT}").out)
        val port = res["XUI_PANEL_PORT"]?.toIntOrNull() ?: throw OpFailed("Нет порта панели ноды (${XuiPanel.RESULT})")
        val path = res["XUI_WEB_BASE_PATH"].orEmpty().trim('/')
        val token = sshB.exec(PanelNodes.xuiNodeTokenCommand()).out.trim().lines().lastOrNull().orEmpty()
        if (token.length < 16) throw OpFailed("Панель ноды не выдала токен синхронизации")
        instB.addSecret(token)
        val scheme = if (sshB.exec("curl -sk -o /dev/null -w '%{http_code}' --max-time 5 https://127.0.0.1:$port/$path/").out.trim().startsWith("2")) "https" else "http"
        openFrom("$port/tcp")
        val u = java.net.URL(p.baseUrl)
        val apiA = XuiApi(SshXuiTransport(sshA, "${u.protocol}://127.0.0.1:${u.port}${u.path}", p.token, u.host.removePrefix("[").removeSuffix("]")))
        val nodes = XuiNodesApi(SshXuiTransport(sshA, "${u.protocol}://127.0.0.1:${u.port}${u.path}", p.token, u.host.removePrefix("[").removeSuffix("]")))
        nodes.list().firstOrNull { it.optString("name") == name }?.let { nodes.delete(it.optInt("id")) }   // осталась от прошлой попытки
        val id = nodes.add(PanelNodes.xuiNodeBody(name, scheme, nodeSrv.host, port, path, token))
        nodes.waitOnline(id)?.let { runCatching { nodes.delete(id) }; throw OpFailed("Главная панель не достучалась до ноды ($it). Порт $port/tcp ноды должен быть доступен с главного сервера") }
        log("Нода подключена к панели")
        // подключение на ноде: порт 443, если свободен у главной панели, иначе следующий свободный
        val used = apiA.inbounds().let { a -> (0 until a.length()).map { a.getJSONObject(it).optInt("port") }.toSet() }
        val access = (listOf(443, 8443) + (20443..20543)).first { it !in used }
        val (priv, pub) = apiA.newRealityKeys()
        val inbound = XuiPanel.defaultInbound(access, priv, pub, XuiPanel.RealityMask.VK, remark = "Hydra · ${nodeSrv.name}").put("nodeId", id)
        val inboundId = apiA.addInbound(inbound)
        openAll("$access/tcp")
        val email = p.clientEmail.takeIf { e -> e.isNotBlank() && e in apiA.clientEmails() } ?: apiA.clientEmails().firstOrNull()
            ?: "hydra-1".also { apiA.addClient(it, XuiPanel.random(16).lowercase(), listOf(inboundId), flow = "") }
        apiA.attachClient(email, listOf(inboundId))
        val links = retryLinks { apiA.links(email).filter { l -> OtherPanelConnections.linkHasPort(l, access) } }
        log("Создано подключение VLESS + gRPC + REALITY на ноде, порт $access")
        return PanelNode(nodeSrv.id, nodeSrv.name, nodeSrv.host, id.toString(), listOf("$port/tcp", "$access/tcp")) to links
    }

    /** Убрать ноду (0.8.2-a4): из главной панели — нода и её подключения/хосты; с сервера ноды — всё, что ставила для неё Hydra. */
    fun removePanelNode(mainId: String, nodeServerId: String) {
        val main = find(mainId)?.takeIf { it.panel != null } ?: return
        val node = main.panel!!.nodes.firstOrNull { it.serverId == nodeServerId } ?: return
        val nodeSrv = find(nodeServerId)
        if (_ui.value.busy) return
        val tB = nodeSrv?.let { store.load(it.id)?.target(it) ?: memLogins[it.id] }
        if (nodeSrv != null && nodeSrv.fingerprint.isNotBlank() && tB == null) {
            askLogin(nodeServerId, "Вход на сервер ноды «${nodeSrv.name}»…", null) { removePanelNode(mainId, nodeServerId) }; return
        }
        op(mainId, "Убираю ноду «${node.name}»…") { srv, sshA, instA ->
            val p = srv.panel ?: throw OpFailed("Панели нет")
            instA.addSecret(p.password); instA.addSecret(p.token)
            try {
                when (p.kind) {
                    PanelKind.XUI3 -> {
                        val u = java.net.URL(p.baseUrl)
                        val tr = SshXuiTransport(sshA, "${u.protocol}://127.0.0.1:${u.port}${u.path}", p.token, u.host.removePrefix("[").removeSuffix("]"))
                        val id = node.remoteId.toIntOrNull() ?: 0
                        val apiA = XuiApi(tr)
                        // подключения на этой ноде — из списка инбаундов (поле nodeId)
                        val full = runCatching { org.json.JSONObject(tr.call("GET", "/panel/api/inbounds/list")).optJSONArray("obj") }.getOrNull() ?: org.json.JSONArray()
                        (0 until full.length()).map { full.getJSONObject(it) }.filter { it.optInt("nodeId", -1) == id }.forEach { runCatching { apiA.delInbound(it.getInt("id")) } }
                        if (id > 0) runCatching { XuiNodesApi(tr).delete(id) }
                    }
                    PanelKind.PASARGUARD -> PasarGuardApi(sshA, p.username, p.password).removeNode(node.remoteId.toIntOrNull() ?: 0, node.host)
                    PanelKind.REMNAWAVE -> RemnawaveApi(sshA, p.username, p.password).removeNode(node.remoteId, node.host)
                    else -> Unit
                }
            } catch (e: XuiApiException) { log("Панель: ${e.message} — нода убрана из Hydra, проверьте её на сайте панели") }
            log("Нода убрана из панели ${p.kind.title}")
            if (nodeSrv != null && tB != null) runCatching {
                SshConnector.connect(tB, nodeSrv.fingerprint).use { sshB ->
                    val instB = Installer(sshB, listOfNotNull(tB.password, tB.privateKey, tB.keyPassphrase), ::onEvent).also { installer = it }
                    val mainIp = sshB.exec(PanelNodes.resolveCommand(srv.host)).out.trim().lines().firstOrNull().orEmpty()
                    val close = node.ports.joinToString("; ") { pt -> ServerOps.closePortCommand(pt) + (if (mainIp.isNotBlank()) "; " + ServerOps.closePortFromCommand(pt, mainIp) else "") } +
                        (if (mainIp.isNotBlank()) "; " + PanelNodes.PG_NODE_PORTS.split(',').joinToString("; ") { ServerOps.closePortFromCommand("$it/tcp", mainIp) } + "; " +
                            ServerOps.closePortFromCommand("${RemnawavePanel.NODE_PORT}/tcp", mainIp) else "")
                    instB.run(listOf(when (p.kind) {
                        PanelKind.XUI3 -> Step("node-rm", "Удалить 3x-ui ноды с сервера", "if [ -f ${ServerOps.XUI_MARK} ]; then printf 'y\\n' | /usr/bin/x-ui uninstall >/dev/null 2>&1; " +
                            // скрипт x-ui в контейнере (есть /.dockerenv) считает себя «не установленным» и ничего не удаляет — снимаем сами
                            "if [ -e /usr/local/x-ui ]; then systemctl disable --now x-ui >/dev/null 2>&1; rm -rf /usr/local/x-ui /etc/x-ui /usr/bin/x-ui /etc/systemd/system/x-ui.service; systemctl daemon-reload; fi; " +
                            "rm -f ${ServerOps.XUI_MARK}; fi; test ! -e /usr/local/x-ui", failHint = "3x-ui ноды удалить не удалось")
                        PanelKind.PASARGUARD -> Step("node-rm", "Удалить ноду PasarGuard с сервера", PanelNodes.pgNodeUninstallCommand())
                        else -> Step("node-rm", "Удалить ноду Remnawave с сервера", PanelNodes.rwNodeUninstallCommand())
                    }, Step("node-fw-close", "Закрыть порты ноды, если их открывала Hydra", "$close; true")))
                }
            }.onFailure { log("Сервер ноды недоступен (${it.message}) — служба ноды на нём осталась, её снимет «Очистить сервер»") }
            update(srv.copy(panel = p.copy(nodes = p.nodes.filterNot { it.serverId == nodeServerId })))
            nodeSrv?.let { s -> find(s.id)?.let { update(it.copy(nodeOf = null)) } }
            log("Готово: нода «${node.name}» убрана. Её подключения в «Серверах» больше не работают — их можно удалить.")
        }
    }

    /** id объекта s-ui по имени ([key] = name) или тегу. */
    private fun suiId(api: SessionPanelApi, obj: String, value: String, key: String = "name"): Int {
        val a = api.suiLoad().optJSONArray(obj) ?: throw XuiApiException("s-ui не вернула список $obj")
        return (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString(key) == value }?.optInt("id")
            ?: throw XuiApiException("s-ui не сохранила $obj «$value»")
    }

    /** У ссылок vmess:// и vpn:// порт внутри base64. */
    private fun linkBodyHasPort(l: String, port: Int): Boolean {
        if (!l.startsWith("vmess://") && !l.startsWith("vpn://")) return false
        val b = l.substringAfter("://").substringBefore("#").replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
        val t = runCatching { String(java.util.Base64.getDecoder().decode(b)) }.getOrDefault("")
        return ":$port" in t || Regex("\"port\"\\s*:\\s*\"?$port\"?").containsMatchIn(t)
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
