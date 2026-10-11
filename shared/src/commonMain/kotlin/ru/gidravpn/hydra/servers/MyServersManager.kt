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
)

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

    private var form: ServerForm? = null
    private var session: SshSession? = null
    private var installer: Installer? = null

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
                val pre = service.preflight(s, f.awgPort)
                val preview = service.planPreview(AwgServerSpec.create(f.target.host, f.awgPort, f.generation), pre, f.openFirewall)
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
            val res = service.deployAwg(s, f.target, fp, f.name, f.generation, f.awgPort, f.openFirewall, pre) { ssh, secrets ->
                Installer(ssh, secrets, ::onEvent).also { installer = it }
            }
            when (res) {
                is DeployResult.Ok -> {
                    save(servers() + res.server)
                    res.server.awg?.let { spec -> importConf(res.server.name, spec.clientConf(res.server.clients.first())) }
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

    /** Удалить сервер: со своей машины снимаем службу и файлы, из приложения — запись. */
    fun uninstall(id: String, login: SshTarget) {
        val srv = servers().firstOrNull { it.id == id } ?: return
        _ui.update { it.copy(busy = true, error = null, log = emptyList()) }
        scope.launch(Dispatchers.IO) {
            try {
                SshConnector.connect(login, srv.fingerprint).use { ssh ->
                    if (service.uninstall(srv, Installer(ssh, listOf(login.password.orEmpty()), ::onEvent))) save(servers().filterNot { it.id == id })
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
