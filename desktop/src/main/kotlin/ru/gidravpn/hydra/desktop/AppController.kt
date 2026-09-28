package ru.gidravpn.hydra.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.Subscription
import ru.gidravpn.hydra.data.subscription.LinkParser
import ru.gidravpn.hydra.desktop.core.ClashApi
import ru.gidravpn.hydra.desktop.core.CoreRunner
import ru.gidravpn.hydra.desktop.core.DesktopConfig
import ru.gidravpn.hydra.desktop.core.NeedsElevation
import ru.gidravpn.hydra.desktop.core.Ping
import ru.gidravpn.hydra.desktop.core.Subscriptions
import ru.gidravpn.hydra.desktop.core.SystemProxy
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom

enum class Status { DISCONNECTED, CONNECTING, CONNECTED, STOPPING, ERROR }

data class UiState(
    val data: HydraState = HydraState(),
    val status: Status = Status.DISCONNECTED,
    val statusText: String = "Не подключено",
    val connectedId: Long? = null,
    val upSpeed: Long = 0,
    val downSpeed: Long = 0,
    val upTotal: Long = 0,
    val downTotal: Long = 0,
    val delayMs: Int? = null,
    val log: List<String> = emptyList(),
    val updatingSubs: Set<Long> = emptySet(),
    val pinging: Boolean = false,
    val message: String? = null,
    val needsElevation: Boolean = false,
) {
    val selected: ServerProfile? get() = data.servers.firstOrNull { it.id == data.settings.selectedServerId }
    val active get() = status == Status.CONNECTING || status == Status.CONNECTED
}

class AppController(private val scope: CoroutineScope, private val store: Store = Store()) {

    private val _ui = MutableStateFlow(UiState(data = store.load()))
    val ui: StateFlow<UiState> = _ui

    @Volatile private var session = 0   // номер сеанса ядра: колбэки старого сеанса игнорируются
    @Volatile private var stopping = false

    private val runner = CoreRunner(
        onLog = { line -> appendLog(line) },
        onExit = { code -> onCoreExit(code) },
    )

    init {
        // Прошлый запуск мог упасть с включённым системным прокси или оставить ядро.
        SystemProxy.restore()
        CoreRunner.killStale()
        scope.launch(Dispatchers.IO) { autoUpdateSubscriptions() }
    }

    // ------------------------------------------------------------------ данные
    private fun mutate(block: (HydraState) -> HydraState) {
        _ui.update { it.copy(data = block(it.data)) }
        runCatching { store.save(_ui.value.data) }.onFailure { toast("Не удалось сохранить настройки: ${it.message}") }
    }

    fun toast(msg: String?) = _ui.update { it.copy(message = msg) }

    fun updateSettings(block: (DesktopSettings) -> DesktopSettings) =
        mutate { it.copy(settings = block(it.settings)) }

    fun select(id: Long) = updateSettings { it.copy(selectedServerId = id) }

    private fun nextId(ids: List<Long>) = (ids.maxOrNull() ?: 0L) + 1

    /**
     * Импорт текста из буфера/поля: ссылка(и) на серверы, base64-список, .conf
     * WireGuard целиком, или http(s)-адрес подписки.
     */
    fun import(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return toast("Буфер обмена пуст")
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) {
            return addSubscription(t, "")
        }
        val parsed = if ("[Interface]" in t) listOfNotNull(runCatching { LinkParser.parseLine(t) }.getOrNull())
        else LinkParser.parseSubscription(t)
        if (parsed.isEmpty()) return toast("Не найдено ни одной поддерживаемой ссылки")
        var firstId = 0L
        mutate { s ->
            var id = nextId(s.servers.map { it.id })
            firstId = id
            val added = parsed.map { it.copy(id = id++, subscriptionId = null) }
            s.copy(servers = s.servers + added,
                settings = if (s.settings.selectedServerId == null) s.settings.copy(selectedServerId = firstId) else s.settings)
        }
        val unsupported = parsed.count { !DesktopConfig.isSupported(it) }
        toast("Добавлено серверов: ${parsed.size}" + if (unsupported > 0) " (на ПК недоступно: $unsupported)" else "")
    }

    fun deleteServer(id: Long) = mutate { s ->
        s.copy(servers = s.servers.filterNot { it.id == id },
            settings = if (s.settings.selectedServerId == id) s.settings.copy(selectedServerId = null) else s.settings)
    }

    fun addSubscription(url: String, name: String) {
        val u = url.trim()
        if (!(u.startsWith("http://", true) || u.startsWith("https://", true))) return toast("Адрес подписки должен начинаться с https://")
        if (_ui.value.data.subscriptions.any { it.url == u }) return toast("Эта подписка уже добавлена")
        var id = 0L
        mutate { s ->
            id = nextId(s.subscriptions.map { it.id })
            s.copy(subscriptions = s.subscriptions + Subscription(id = id, name = name.ifBlank { hostOf(u) }, url = u))
        }
        refreshSubscription(id)
    }

    fun deleteSubscription(id: Long) = mutate { s ->
        val removed = s.servers.filter { it.subscriptionId == id }.map { it.id }.toSet()
        s.copy(subscriptions = s.subscriptions.filterNot { it.id == id },
            servers = s.servers.filterNot { it.id in removed },
            settings = if (s.settings.selectedServerId in removed) s.settings.copy(selectedServerId = null) else s.settings)
    }

    fun refreshSubscription(id: Long) {
        val sub = _ui.value.data.subscriptions.firstOrNull { it.id == id } ?: return
        if (id in _ui.value.updatingSubs) return
        _ui.update { it.copy(updatingSubs = it.updatingSubs + id) }
        scope.launch(Dispatchers.IO) {
            val result = runCatching { Subscriptions.fetch(sub.url, id) }
            result.onSuccess { f -> mutate { s -> applySubscription(s, id, f) } }
            result.onFailure { e ->
                mutate { s -> s.copy(subscriptions = s.subscriptions.map { if (it.id == id) it.copy(lastError = e.message ?: e.toString()) else it }) }
                toast("Подписка «${sub.displayName}»: ${e.message}")
            }
            _ui.update { it.copy(updatingSubs = it.updatingSubs - id) }
        }
    }

    fun refreshAll() = _ui.value.data.subscriptions.forEach { refreshSubscription(it.id) }

    private fun applySubscription(s: HydraState, id: Long, f: Subscriptions.Fetched): HydraState {
        val old = s.servers.filter { it.subscriptionId == id }
        val selectedOld = old.firstOrNull { it.id == s.settings.selectedServerId }
        var next = nextId(s.servers.map { it.id })
        // Сохраняем id совпадающих серверов — выбор и пинг переживают обновление.
        val fresh = f.servers.map { n ->
            val same = old.firstOrNull { it.address == n.address && it.port == n.port && it.name == n.name }
            n.copy(id = same?.id ?: next++, subscriptionId = id, pingMs = same?.pingMs ?: -1)
        }
        val keepSelection = selectedOld == null || fresh.any { it.id == selectedOld.id }
        return s.copy(
            servers = s.servers.filterNot { it.subscriptionId == id } + fresh,
            subscriptions = s.subscriptions.map {
                if (it.id != id) it else it.copy(
                    lastUpdated = System.currentTimeMillis(), lastError = "",
                    serverTitle = f.info.title, uploadBytes = f.info.upload, downloadBytes = f.info.download,
                    totalBytes = f.info.total, expireAt = f.info.expire, supportUrl = f.info.supportUrl,
                    autoUpdateHours = f.info.updateHours ?: it.autoUpdateHours,
                )
            },
            settings = if (keepSelection) s.settings else s.settings.copy(selectedServerId = fresh.firstOrNull()?.id),
        )
    }

    private fun autoUpdateSubscriptions() {
        val now = System.currentTimeMillis()
        _ui.value.data.subscriptions
            .filter { now - it.lastUpdated > it.autoUpdateHours * 3_600_000L }
            .forEach { refreshSubscription(it.id) }
    }

    fun pingAll() {
        if (_ui.value.pinging) return
        _ui.update { it.copy(pinging = true) }
        scope.launch(Dispatchers.IO) {
            val servers = _ui.value.data.servers
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            val pool = Dispatchers.IO.limitedParallelism(16)
            val results: Map<Long, Int> = coroutineScope {
                servers.map { p -> async(pool) { p.id to Ping.tcp(p.address, p.port) } }.awaitAll().toMap()
            }
            mutate { s -> s.copy(servers = s.servers.map { p -> results[p.id]?.let { p.copy(pingMs = it) } ?: p }) }
            _ui.update { it.copy(pinging = false) }
        }
    }

    // ------------------------------------------------------------------ соединение
    fun toggle() = if (_ui.value.active) disconnect() else connect()

    fun connect() {
        val st = _ui.value
        if (st.active || st.status == Status.STOPPING) return
        val profile = st.selected ?: return toast("Выберите сервер")
        if (!DesktopConfig.isSupported(profile)) {
            return toast("«${profile.protocol?.displayName ?: profile.protocolId}» на ПК пока недоступен — выберите другой сервер")
        }
        val settings = st.data.settings
        val sessionId = ++session
        stopping = false
        _ui.update {
            it.copy(status = Status.CONNECTING, statusText = "Подключение…", connectedId = profile.id,
                upSpeed = 0, downSpeed = 0, upTotal = 0, downTotal = 0, delayMs = null, needsElevation = false, log = emptyList())
        }
        scope.launch(Dispatchers.IO) {
            try {
                if (settings.mode == ConnectionMode.PROXY) ensurePortFree(settings.proxyPort)
                val api = DesktopConfig.Api(freePort(), randomSecret())
                val config = DesktopConfig.build(profile, settings, Platform.os, api, Platform.geoDir())
                runner.start(config.toString(2), settings.mode)
                val clash = ClashApi(api)
                clash.streamTraffic({ session == sessionId && runner.isAlive }) { up, down ->
                    _ui.update { it.copy(upSpeed = up, downSpeed = down, upTotal = it.upTotal + up, downTotal = it.downTotal + down) }
                }
                if (settings.mode == ConnectionMode.PROXY && settings.setSystemProxy) {
                    SystemProxy.enable(settings.proxyPort).onFailure { toast("Системный прокси не установлен: ${it.message}") }
                }
                verify(clash, sessionId, settings)
            } catch (e: NeedsElevation) {
                _ui.update { it.copy(status = Status.DISCONNECTED, statusText = "Нужны права администратора", needsElevation = true, connectedId = null) }
            } catch (e: Exception) {
                runner.stop()
                SystemProxy.restore()
                _ui.update { it.copy(status = Status.ERROR, statusText = e.message ?: e.toString(), connectedId = null) }
            }
        }
    }

    /** Реальная проверка: HTTP-запрос через outbound proxy. Без неё «Подключено» = только «процесс жив». */
    private suspend fun verify(clash: ClashApi, sessionId: Int, settings: DesktopSettings) {
        var lastError = ""
        repeat(4) { attempt ->
            delay(if (attempt == 0) 1200 else 2000)
            if (session != sessionId || !runner.isAlive) return
            clash.delay().onSuccess { ms ->
                _ui.update {
                    it.copy(status = Status.CONNECTED, delayMs = ms,
                        statusText = "Подключено · ${if (settings.mode == ConnectionMode.TUN) "TUN, весь трафик" else "прокси 127.0.0.1:${settings.proxyPort}"}")
                }
                return
            }.onFailure { lastError = it.message.orEmpty() }
        }
        if (session == sessionId && runner.isAlive) {
            // Ядро живо, но сервер не отвечает: держим соединение, но честно показываем проблему.
            _ui.update { it.copy(status = Status.CONNECTED, delayMs = null, statusText = "Ядро запущено, но сервер не отвечает: $lastError") }
        }
    }

    fun disconnect() {
        if (!_ui.value.active) return
        stopping = true
        session++
        _ui.update { it.copy(status = Status.STOPPING, statusText = "Отключение…") }
        scope.launch(Dispatchers.IO) {
            SystemProxy.restore()
            runner.stop()
            _ui.update { it.copy(status = Status.DISCONNECTED, statusText = "Не подключено", connectedId = null, upSpeed = 0, downSpeed = 0, delayMs = null) }
        }
    }

    private fun onCoreExit(code: Int) {
        if (stopping) return
        SystemProxy.restore()
        val reason = _ui.value.log.lastOrNull { "FATAL" in it || "ERROR" in it }?.substringAfter("] ")
        _ui.update {
            if (!it.active) it else it.copy(status = Status.ERROR, connectedId = null, upSpeed = 0, downSpeed = 0,
                statusText = "Ядро остановилось (код $code)" + (reason?.let { r -> ": $r" } ?: ""))
        }
    }

    fun relaunchAsAdmin(): Boolean = ru.gidravpn.hydra.desktop.core.Elevation.relaunchAsAdmin()

    /** Синхронно: вызывается при выходе из приложения. */
    fun shutdown() {
        stopping = true
        session++
        SystemProxy.restore()
        runner.stop()
    }

    // sing-box красит лог ANSI-кодами даже в пайп (опции отключения в 1.12 нет).
    private val ansi = Regex("\u001B\\[[0-9;]*m")

    private fun appendLog(line: String) {
        val clean = line.replace(ansi, "")
        _ui.update { it.copy(log = (it.log + clean).takeLast(400)) }
    }

    private fun ensurePortFree(port: Int) {
        runCatching { ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close() }
            .onFailure { error("Порт $port уже занят другой программой — смените порт прокси в настройках") }
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun randomSecret(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: "Подписка"
}
