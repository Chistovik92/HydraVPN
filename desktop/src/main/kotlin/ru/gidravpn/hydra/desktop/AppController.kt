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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import ru.gidravpn.hydra.bot.BotClient
import ru.gidravpn.hydra.bot.BotException
import ru.gidravpn.hydra.bot.BotJson
import ru.gidravpn.hydra.bot.BotSyncPlanner
import ru.gidravpn.hydra.data.dpi.DpiArgs
import ru.gidravpn.hydra.data.dpi.DpiProbe
import ru.gidravpn.hydra.data.dpi.DpiSettings
import ru.gidravpn.hydra.data.dpi.DpiStrategies
import ru.gidravpn.hydra.data.geo.CustomGeoSource
import ru.gidravpn.hydra.data.geo.GeoSettings
import ru.gidravpn.hydra.data.geo.GeoSources
import ru.gidravpn.hydra.data.routing.RouteConfig
import ru.gidravpn.hydra.data.routing.RouteGroup
import ru.gidravpn.hydra.data.routing.RouteRule
import ru.gidravpn.hydra.data.routing.RouteTarget
import ru.gidravpn.hydra.data.routing.withVia
import ru.gidravpn.hydra.data.geo.GeoKind
import ru.gidravpn.hydra.data.geo.GeoUpdater
import ru.gidravpn.hydra.data.geo.JvmGeoHttp
import ru.gidravpn.hydra.data.model.Engine
import ru.gidravpn.hydra.data.model.EngineToggles
import ru.gidravpn.hydra.data.model.GeoRoutingMode
import ru.gidravpn.hydra.data.routing.RouteKind
import ru.gidravpn.hydra.data.routing.RoutePlan
import ru.gidravpn.hydra.data.routing.RoutePlanFactory
import ru.gidravpn.hydra.data.routing.viaOf
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder
import ru.gidravpn.hydra.data.model.HotspotSettings
import ru.gidravpn.hydra.data.model.NetRuleType
import ru.gidravpn.hydra.data.model.NetworkRule
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.SplitTunnelMode
import ru.gidravpn.hydra.data.model.Subscription
import ru.gidravpn.hydra.data.subscription.LinkBuilder
import ru.gidravpn.hydra.data.subscription.LinkParser
import ru.gidravpn.hydra.data.subscription.OlcRtcConfigBuilder
import ru.gidravpn.hydra.data.subscription.OpenFluxArgs
import ru.gidravpn.hydra.data.subscription.OpenFluxLink
import ru.gidravpn.hydra.data.subscription.XrayConfigBuilder
import ru.gidravpn.hydra.desktop.core.Autostart
import ru.gidravpn.hydra.desktop.core.ClashApi
import ru.gidravpn.hydra.desktop.core.CoreRunner
import ru.gidravpn.hydra.desktop.core.Sidecar
import ru.gidravpn.hydra.desktop.core.hasValidEndpoint
import ru.gidravpn.hydra.desktop.core.DesktopConfig
import ru.gidravpn.hydra.desktop.core.NeedsElevation
import ru.gidravpn.hydra.desktop.core.NetWatch
import ru.gidravpn.hydra.desktop.core.Ping
import ru.gidravpn.hydra.desktop.core.Rules
import ru.gidravpn.hydra.desktop.core.Subscriptions
import ru.gidravpn.hydra.desktop.core.SystemProxy
import ru.gidravpn.hydra.desktop.core.Updates
import ru.gidravpn.hydra.router.RouterManager
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom

/** Значение routing.dns для приватного DNS Hydra VPN (адрес с токеном в файл настроек не пишется). */
const val HYDRA_DNS = "hydra"

enum class Status { DISCONNECTED, CONNECTING, CONNECTED, STOPPING, ERROR }

/** Состояние мастера подбора обхода DPI: [baseline] — как открывается всё без обхода, [results] — лучшие на данный момент. */
data class DpiProbeUi(
    val running: Boolean = false, val done: Int = 0, val total: Int = 0,
    val baseline: DpiProbe.Result? = null, val results: List<DpiProbe.Result> = emptyList(),
    val finished: Boolean = false, val error: String? = null,
)

data class UiState(
    val data: HydraState = HydraState(),
    val status: Status = Status.DISCONNECTED,
    val statusText: String = "Не подключено",
    val connectedId: Long? = null,
    /** Движок текущего/последнего подключения. */
    val engine: EngineToggles.Kind? = null,
    val upSpeed: Long = 0,
    val downSpeed: Long = 0,
    val upTotal: Long = 0,
    val downTotal: Long = 0,
    val delayMs: Int? = null,
    /** Растёт после обновления geo-баз: экран «Geo-базы» перечитывает хранилище. */
    val geoTick: Int = 0,
    val log: List<String> = emptyList(),
    val updatingSubs: Set<Long> = emptySet(),
    val pinging: Boolean = false,
    val message: String? = null,
    val needsElevation: Boolean = false,
    /** Kill switch сработал: системный прокси оставлен на мёртвый порт, трафик не утекает. */
    val blocked: Boolean = false,
    val update: Updates.Release? = null,
    /** Идёт загрузка обновления: 0..1; null — не идёт. */
    val updateProgress: Float? = null,
    /** Идёт вход или синхронизация с ботом «Радар». */
    val botBusy: Boolean = false,
) {
    val selected: ServerProfile? get() = data.servers.firstOrNull { it.id == data.settings.selectedServerId }
    val active get() = status == Status.CONNECTING || status == Status.CONNECTED
}

/** Расписание переподключения — то же, что ReconnectPolicy на Android: 2, 4, 8, 16, 30… с. */
object ReconnectPolicy {
    const val MAX_ATTEMPTS = 10
    fun backoffMs(n: Int): Long = minOf(2_000L shl minOf(n.coerceAtLeast(0), 10), 30_000L)
}

class AppController(
    private val scope: CoroutineScope,
    private val store: Store = Store(),
    /** false — без фоновых задач (сети) при создании: для тестов рендера. */
    private val background: Boolean = true,
) {

    private val _ui = MutableStateFlow(UiState(data = store.load()))
    val ui: StateFlow<UiState> = _ui

    /** Роутеры HydraVPN for Router, которыми управляет приложение (экран «Роутеры»). */
    val routers = RouterManager(
        scope,
        routers = { _ui.value.data.routers },
        saveRouters = { list -> mutate { it.copy(routers = list) } },
        toast = ::toast,
    )

    /** Номер сеанса ядра: колбэки и корутины старого сеанса ничего не меняют. */
    @Volatile private var session = 0
    /** Пользователь сам отключился — выход ядра не ошибка и не повод переподключаться. */
    @Volatile private var userStopped = true
    /** Сеанс, чей выход ядра уже обработан (ядер два — выход второго не считаем заново). */
    @Volatile private var exitHandled = -1
    @Volatile private var reconnectAttempt = 0

    /** Подключение и отключение строго по очереди — без осиротевших ядер и «чужих» статусов. */
    private val connLock = Mutex()
    private val saveLock = Any()

    /** Ядро Xray есть в пакете (проверяется один раз: UI спрашивает на каждой перерисовке). */
    val xrayAvailable: Boolean by lazy { Platform.bundledXray() != null }
    /** Клиенты olcRTC и OpenFlux есть в пакете (0.7.4). */
    val olcRtcAvailable: Boolean by lazy { Platform.bundledOlcRtc() != null }
    val openFluxAvailable: Boolean by lazy { Platform.bundledOpenFlux() != null }

    private val runner = CoreRunner(
        onLog = { line -> appendLog(line) },
        onExit = { sid, code, core -> onCoreExit(sid, code, core) },
    )

    init {
        if (background) {
            // Прошлый запуск мог упасть с включённым системным прокси или оставить ядра.
            SystemProxy.restore()
            CoreRunner.killStale()
            scope.launch(Dispatchers.IO) {
                // Подписки — сейчас и затем раз в полчаса проверяем, не пора ли.
                while (isActive) {
                    autoUpdateSubscriptions()
                    autoSyncBot()
                    updateGeo(manual = false)
                    delay(30 * 60_000L)
                }
            }
            if (_ui.value.data.settings.checkUpdates) checkForUpdates(manual = false)
        }
    }

    /** Вызывается из main после построения окна: автоподключение при запуске (как на Android). */
    fun onStartup(reconnect: Boolean = false) {
        // reconnect — запуск после «Перезапустить от администратора» (0.7.16): соединение, которое было, поднимается снова.
        if ((reconnect || _ui.value.data.settings.autoConnect) && _ui.value.selected != null) connect()
    }

    // ------------------------------------------------------------------ данные
    private fun mutate(block: (HydraState) -> HydraState) {
        _ui.update { it.copy(data = block(it.data)) }
        persist()
    }

    /** Сохраняет последнее состояние: чтение под той же блокировкой, что и запись. */
    private fun persist() {
        runCatching { synchronized(saveLock) { store.save(_ui.value.data) } }
            .onFailure { toast("Не удалось сохранить настройки: ${it.message}") }
    }

    fun toast(msg: String?) = _ui.update { it.copy(message = msg) }

    /** Для скриншотов и тестов отрисовки: подменяет состояние интерфейса (в самом приложении не используется). */
    internal fun previewState(f: (UiState) -> UiState) { _ui.update(f) }

    fun updateSettings(block: (DesktopSettings) -> DesktopSettings) =
        mutate { it.copy(settings = block(it.settings)) }

    fun updateRouting(block: (RoutingSettings) -> RoutingSettings) =
        updateSettings { it.copy(routing = block(it.routing)) }

    fun select(id: Long) = updateSettings { it.copy(selectedServerId = id) }

    private fun nextId(ids: List<Long>) = (ids.maxOrNull() ?: 0L) + 1

    /**
     * Импорт текста из буфера/поля: ссылка(и) на серверы, base64-список, .conf
     * WireGuard целиком, или http(s)-адрес подписки.
     */
    fun import(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return toast("Буфер обмена пуст")
        if (t.length > Subscriptions.MAX_BYTES) return toast("Слишком большой текст для импорта")
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) {
            return addSubscription(t.lineSequence().first().trim(), "")
        }
        // olcbox://add?url=<ссылка подписки> — «поделиться» из клиента olcBox: это обычная подписка.
        if (t.startsWith("olcbox://", true)) {
            val u = Regex("[?&]url=([^&#]+)").find(t)?.groupValues?.get(1)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            if (u != null && (u.startsWith("https://", true) || u.startsWith("http://", true))) return addSubscription(u, "olcBox")
        }
        val parsed = if ("[Interface]" in t) listOfNotNull(runCatching { LinkParser.parseLine(t) }.getOrNull())
        else LinkParser.parseSubscription(t)
        val valid = parsed.filter { it.hasValidEndpoint }
        if (valid.isEmpty()) {
            t.lineSequence().map { it.trim() }.firstNotNullOfOrNull { l ->
                if (l.startsWith("openflux://", true)) OpenFluxLink.errorCode(l) else null
            }?.let { return toast(openFluxErrorText(it)) }
            return toast("Не найдено ни одной поддерживаемой ссылки")
        }
        mutate { s ->
            var id = nextId(s.servers.map { it.id })
            val first = id
            val added = valid.map { it.copy(id = id++, subscriptionId = null) }
            s.copy(servers = s.servers + added,
                settings = if (s.settings.selectedServerId == null) s.settings.copy(selectedServerId = first) else s.settings)
        }
        val unsupported = valid.count { !DesktopConfig.isSupported(it) }
        toast("Добавлено серверов: ${valid.size}" + if (unsupported > 0) " (на ПК недоступно: $unsupported)" else "")
    }

    private fun openFluxErrorText(code: String) = when (code) {
        "damaged", "case_changed", "bad_payload", "too_large", "not_link" ->
            "Ссылка OpenFlux повреждена или обрезана (возможно, изменился регистр букв) — скопируйте её заново целиком."
        "unsupported_version" -> "Эта ссылка OpenFlux создана более новой версией — обновите Hydra."
        "short_secret", "session_secret" -> "Ключ в ссылке OpenFlux короче 16 символов."
        "stream_unsupported" -> "Режим OpenFlux «без сервера» (узел на PHP-хостинге) пока не поддерживается — он запланирован на 0.8.0."
        else -> "Эту ссылку OpenFlux нельзя использовать ($code)."
    }

    fun deleteServer(id: Long) {
        if (_ui.value.active && _ui.value.connectedId == id) return toast("Сначала отключитесь от этого сервера")
        mutate { s ->
            s.copy(servers = s.servers.filterNot { it.id == id },
                settings = if (s.settings.selectedServerId == id) s.settings.copy(selectedServerId = null) else s.settings)
        }
    }

    /** Ссылка на сервер (vless://… и т.п.) для «Поделиться»; null — у протокола нет формата ссылки. */
    fun shareLink(id: Long): String? =
        _ui.value.data.servers.firstOrNull { it.id == id }?.let { runCatching { LinkBuilder.toLink(it) }.getOrNull() }

    fun addSubscription(url: String, name: String) {
        val u = url.trim()
        if (!(u.startsWith("http://", true) || u.startsWith("https://", true))) return toast("Адрес подписки должен начинаться с https://")
        if (runCatching { java.net.URI(u).host }.getOrNull().isNullOrBlank()) return toast("Некорректный адрес подписки")
        if (_ui.value.data.subscriptions.any { it.url == u }) return toast("Эта подписка уже добавлена")
        var id = 0L
        mutate { s ->
            id = nextId(s.subscriptions.map { it.id })
            s.copy(subscriptions = s.subscriptions + Subscription(id = id, name = name.trim().ifBlank { hostOf(u) }.take(80), url = u))
        }
        refreshSubscription(id)
    }

    fun deleteSubscription(id: Long) {
        val st = _ui.value
        if (st.active && st.data.servers.any { it.id == st.connectedId && it.subscriptionId == id }) {
            return toast("Сначала отключитесь — текущий сервер из этой подписки")
        }
        mutate { s ->
            val removed = s.servers.filter { it.subscriptionId == id }.map { it.id }.toSet()
            s.copy(subscriptions = s.subscriptions.filterNot { it.id == id },
                servers = s.servers.filterNot { it.id in removed },
                settings = if (s.settings.selectedServerId in removed) s.settings.copy(selectedServerId = null) else s.settings)
        }
    }

    fun setSubscriptionAutoUpdate(id: Long, hours: Int) = mutate { s ->
        s.copy(subscriptions = s.subscriptions.map { if (it.id == id) it.copy(autoUpdateHours = hours.coerceIn(0, 168)) else it })
    }

    fun refreshSubscription(id: Long) {
        scope.launch(Dispatchers.IO) { refreshNow(id) }
    }

    /** Блокирующее обновление одной подписки (вызывать из IO); true — серверы получены. */
    private fun refreshNow(id: Long, quiet: Boolean = false): Boolean {
        val sub = _ui.value.data.subscriptions.firstOrNull { it.id == id } ?: return false
        var started = false
        _ui.update { if (id in it.updatingSubs) it else { started = true; it.copy(updatingSubs = it.updatingSubs + id) } }
        if (!started) return false
        try {
            val result = runCatching { Subscriptions.fetch(sub.url, id) }
            result.onSuccess { f -> mutate { s -> if (s.subscriptions.none { it.id == id }) s else applySubscription(s, id, f) } }
            result.onFailure { e ->
                mutate { s -> s.copy(subscriptions = s.subscriptions.map { if (it.id == id) it.copy(lastError = e.message ?: e.toString()) else it }) }
                if (!quiet) toast("Подписка «${sub.displayName}»: ${e.message}")
            }
            return result.isSuccess
        } finally {
            _ui.update { it.copy(updatingSubs = it.updatingSubs - id) }
        }
    }

    // ------------------------------------------------------------------ аккаунт бота «Радар» (0.7.0)

    private val bot = BotClient()
    @Volatile private var lastBotSync = 0L

    private fun botError(e: BotException): String = when (e.code) {
        0 -> "Нет связи с сервером бота."
        401 -> "Код неверен или устарел, либо это устройство отключили в боте. Возьмите новый код."
        -1 -> e.message ?: "Это не сервер бота «Радар»."
        404 -> "На этом сервере API для приложений выключен."
        429 -> "Слишком много попыток. Подождите несколько минут."
        else -> e.message ?: "HTTP ${e.code}"
    }

    /** Код из бота («VPN» → «Подключить приложение») → токен устройства → первая синхронизация. */
    fun linkBot(serverInput: String, code: String) {
        if (_ui.value.botBusy) return
        val server = BotJson.normalizeServer(serverInput)
            ?: return toast("Укажите адрес сервера бота, например radar.example.org. Обычный http — только в своей сети.")
        _ui.update { it.copy(botBusy = true) }
        scope.launch(Dispatchers.IO) {
            try {
                val token = bot.link(server, code, Platform.deviceName())
                val profile = runCatching { bot.profile(server, token) }.getOrNull()
                mutate { it.copy(bot = BotLink(server, token, profile?.username.orEmpty(), profile?.panels)) }
                toast(syncBotNow())
            } catch (e: BotException) {
                toast(botError(e))
            } finally {
                _ui.update { it.copy(botBusy = false) }
            }
        }
    }

    fun syncBot() {
        if (_ui.value.botBusy || _ui.value.data.bot == null) return
        _ui.update { it.copy(botBusy = true) }
        scope.launch(Dispatchers.IO) {
            try { toast(syncBotNow()) } finally { _ui.update { it.copy(botBusy = false) } }
        }
    }

    /** Отключить устройство в боте и забыть токен. Уже заведённые подписки остаются. */
    fun unlinkBot() {
        val link = _ui.value.data.bot ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { bot.logout(link.server, link.token) }
            mutate { it.copy(bot = null, settings = it.settings.copy(routing = it.settings.routing.let { r ->
                if (r.dns == HYDRA_DNS) r.copy(dns = "1.1.1.1") else r })) }
            toast("Отключено. Уже добавленные подписки остались в списке.")
        }
    }

    /** Срок и трафик из ответа бота — запасной источник: заголовки панели при обновлении подписки главнее. */
    private fun applyBotQuota(subId: Long, item: ru.gidravpn.hydra.bot.BotSubscription) = mutate { s ->
        s.copy(subscriptions = s.subscriptions.map {
            if (it.id != subId || it.expireAt != 0L || it.totalBytes != 0L) it
            else it.copy(expireAt = item.expire, totalBytes = item.trafficLimit, downloadBytes = item.trafficUsed, uploadBytes = 0)
        })
    }

    /** Синхронизация: заводит/обновляет подписки бота. Возвращает текст для пользователя. */
    private fun syncBotNow(): String {
        val link = _ui.value.data.bot ?: return ""
        val items = try {
            bot.subscriptions(link.server, link.token)
        } catch (e: BotException) {
            // Токен отозван (устройство отключили в боте) — держать его дальше незачем.
            if (e.code == 401) mutate { it.copy(bot = null) }
            return botError(e)
        }
        runCatching { bot.profile(link.server, link.token) }.getOrNull()?.let { p ->
            mutate { s -> s.copy(bot = s.bot?.copy(username = p.username.ifBlank { s.bot.username }, panels = p.panels)) }
        }
        var subs = 0; var failed = 0
        for (step in BotSyncPlanner.plan(_ui.value.data.subscriptions, items)) {
            when (step) {
                is BotSyncPlanner.Step.Disable -> mutate { s ->
                    s.copy(subscriptions = s.subscriptions.map {
                        if (it.id == step.sub.id) it.copy(autoUpdateHours = 0, lastError = BotSyncPlanner.DISABLED_MARK) else it
                    })
                }
                is BotSyncPlanner.Step.Add -> {
                    var id = 0L
                    mutate { s ->
                        id = nextId(s.subscriptions.map { it.id })
                        s.copy(subscriptions = s.subscriptions + Subscription(id = id, name = step.item.title.take(80), url = step.item.url, botPanel = step.item.panel))
                    }
                    if (refreshNow(id, quiet = true)) { subs++; applyBotQuota(id, step.item) } else failed++
                }
                is BotSyncPlanner.Step.Update -> {
                    mutate { s ->
                        s.copy(subscriptions = s.subscriptions.map {
                            if (it.id != step.sub.id) it else it.copy(
                                url = step.item.url, botPanel = step.item.panel,
                                autoUpdateHours = if (it.autoUpdateHours == 0 && it.lastError == BotSyncPlanner.DISABLED_MARK) 12 else it.autoUpdateHours,
                            )
                        })
                    }
                    if (refreshNow(step.sub.id, quiet = true)) { subs++; applyBotQuota(step.sub.id, step.item) } else failed++
                }
            }
        }
        lastBotSync = System.currentTimeMillis()
        return when {
            items.none { it.importable } -> "В боте пока нет рабочих подписок для вас. Доступ выдаёт администратор."
            failed > 0 -> "Подписок: $subs. Не загрузилось: $failed."
            else -> "Подписок добавлено или обновлено: $subs."
        }
    }

    /** Раз в 6 часов, как BotSyncWorker на Android. */
    private fun autoSyncBot() {
        if (_ui.value.data.bot == null || _ui.value.botBusy) return
        if (System.currentTimeMillis() - lastBotSync < 6 * 3_600_000L) return
        syncBotNow()
    }

    /** Приватный DNS Hydra VPN доступен только вошедшему через бота и только если сборка знает адрес. */
    val hydraDnsAvailable: Boolean get() = _ui.value.data.bot != null && HydraSecrets.PRIVATE_DNS.isNotBlank()

    fun refreshAll() = _ui.value.data.subscriptions.forEach { refreshSubscription(it.id) }

    private fun applySubscription(s: HydraState, id: Long, f: Subscriptions.Fetched): HydraState {
        val old = s.servers.filter { it.subscriptionId == id }
        val connected = _ui.value.connectedId
        var next = nextId(s.servers.map { it.id })
        // Сохраняем id совпадающих серверов — выбор, пинг и текущее подключение переживают обновление.
        val used = mutableSetOf<Long>()
        val fresh = f.servers.filter { it.hasValidEndpoint }.map { n ->
            val same = old.firstOrNull { it.id !in used && it.address == n.address && it.port == n.port && it.name == n.name }
            same?.let { used += it.id }
            n.copy(id = same?.id ?: next++, subscriptionId = id, pingMs = same?.pingMs ?: -1).let { fresh ->
                // «Пустить через …» (0.7.13) хранится в профиле — обновление подписки его не стирает.
                same?.let { viaOf(it) }?.let { v -> withVia(fresh, v) } ?: fresh
            }
        }
        // Сервер, к которому сейчас подключены, не удаляем из-под туннеля.
        val keepConnected = old.filter { it.id == connected && it.id !in used }
        val selected = s.settings.selectedServerId
        val selectionAlive = selected == null || old.none { it.id == selected } || fresh.any { it.id == selected } || keepConnected.any { it.id == selected }
        return s.copy(
            servers = s.servers.filterNot { it.subscriptionId == id } + fresh + keepConnected,
            subscriptions = s.subscriptions.map {
                if (it.id != id) it else it.copy(
                    lastUpdated = System.currentTimeMillis(), lastError = "",
                    serverTitle = f.info.title, uploadBytes = f.info.upload, downloadBytes = f.info.download,
                    totalBytes = f.info.total, expireAt = f.info.expire, supportUrl = f.info.supportUrl,
                    autoUpdateHours = if (it.autoUpdateHours == 0) 0 else f.info.updateHours?.coerceIn(1, 168) ?: it.autoUpdateHours,
                )
            },
            settings = if (selectionAlive) s.settings else s.settings.copy(selectedServerId = fresh.firstOrNull()?.id),
        )
    }

    private fun autoUpdateSubscriptions() {
        val now = System.currentTimeMillis()
        _ui.value.data.subscriptions
            .filter { it.autoUpdateHours > 0 && now - it.lastUpdated > it.autoUpdateHours * 3_600_000L }
            .forEach { refreshSubscription(it.id) }
    }

    fun pingAll() {
        var started = false
        _ui.update { if (it.pinging) it else { started = true; it.copy(pinging = true) } }
        if (!started) return
        scope.launch(Dispatchers.IO) {
            try {
                val servers = _ui.value.data.servers
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                val pool = Dispatchers.IO.limitedParallelism(16)
                val results: Map<Long, Int> = coroutineScope {
                    servers.map { p -> async(pool) { p.id to Ping.tcp(p.address, p.port) } }.awaitAll().toMap()
                }
                mutate { s -> s.copy(servers = s.servers.map { p -> results[p.id]?.let { p.copy(pingMs = it) } ?: p }) }
            } finally {
                _ui.update { it.copy(pinging = false) }
            }
        }
    }

    // ------------------------------------------------------------------ маршрутизация
    fun setAppMode(mode: SplitTunnelMode) = updateRouting { it.copy(appMode = mode) }

    fun addApps(entries: List<String>) {
        val cur = _ui.value.data.settings.routing.apps
        val merged = Rules.apps(cur + entries)
        if (merged.size == cur.size) return toast("Уже в списке или некорректное имя")
        updateRouting { it.copy(apps = merged) }
    }

    fun removeApp(app: String) = updateRouting { it.copy(apps = it.apps - app) }

    fun setNetMode(mode: SplitTunnelMode) = updateRouting { it.copy(netMode = mode) }

    /** [type] null — определить по виду строки (адрес/подсеть или домен). */
    fun addNetRule(type: NetRuleType?, value: String): Boolean {
        val rule = (if (type == null) Rules.guess(value) else Rules.netRule(type, value))
            ?: return false.also { toast("«${value.trim()}» не подходит для правила этого типа") }
        val cur = _ui.value.data.settings.routing.netRules
        if (rule in cur) return false.also { toast("Такое правило уже есть") }
        if (cur.size >= Rules.MAX_RULES) return false.also { toast("Не больше ${Rules.MAX_RULES} правил") }
        updateRouting { it.copy(netRules = it.netRules + rule) }
        return true
    }

    fun removeNetRule(rule: NetworkRule) = updateRouting { it.copy(netRules = it.netRules - rule) }

    fun setDns(value: String): Boolean {
        val v = value.trim()
        val ok = v.equals("system", true) || (v == HYDRA_DNS && hydraDnsAvailable) || ru.gidravpn.hydra.data.model.DnsEndpoint.parse(v) != null
        if (ok) updateRouting { it.copy(dns = v) }
        return ok
    }

    fun toggleGeoCountry(code: String) = updateRouting { r ->
        val c = code.lowercase()
        r.copy(geoCountries = if (c in r.geoCountries) r.geoCountries - c else Rules.countries(r.geoCountries + c))
    }

    fun saveProfile(name: String) {
        val n = name.trim().take(40)
        if (n.isEmpty()) return
        mutate { s -> s.copy(profiles = s.profiles.filterNot { it.name.equals(n, true) } + RoutingProfile(n, s.settings.routing)) }
        toast("Профиль «$n» сохранён")
    }

    fun applyProfile(name: String) {
        val p = _ui.value.data.profiles.firstOrNull { it.name == name } ?: return
        updateRouting { p.routing }
        toast("Профиль «${p.name}» применён" + if (_ui.value.active) " — переподключитесь, чтобы он заработал" else "")
    }

    fun deleteProfile(name: String) = mutate { s -> s.copy(profiles = s.profiles.filterNot { it.name == name }) }

    // ------------------------------------------------------------------ движки и безопасность
    fun setEngine(kind: EngineToggles.Kind, enabled: Boolean) = updateSettings {
        when (kind) {
            EngineToggles.Kind.SINGBOX -> it.copy(singBoxEnabled = enabled)
            EngineToggles.Kind.XRAY -> it.copy(xrayEnabled = enabled)
            EngineToggles.Kind.OLCRTC -> it.copy(olcRtcEnabled = enabled)
            EngineToggles.Kind.OPENFLUX -> it.copy(openFluxEnabled = enabled)
            EngineToggles.Kind.BYEDPI -> it   // тумблера нет
            else -> it
        }
    }

    fun setLaunchAtLogin(on: Boolean) {
        Autostart.set(on)
            .onSuccess { updateSettings { it.copy(launchAtLogin = on) } }
            .onFailure { toast("Автозапуск: ${it.message}") }
    }

    fun setLanShare(enabled: Boolean) = updateSettings { s ->
        val pass = s.lanShare.password.takeIf { it.length >= HotspotSettings.MIN_PASSWORD } ?: randomPassword()
        s.copy(lanShare = s.lanShare.copy(enabled = enabled, password = pass))
    }

    fun updateLanShare(block: (HotspotSettings) -> HotspotSettings) = updateSettings { it.copy(lanShare = block(it.lanShare)) }

    fun regenerateLanPassword() = updateLanShare { it.copy(password = randomPassword()) }

    // ------------------------------------------------------------------ резервная копия
    fun exportBackup(file: File) = scope.launch(Dispatchers.IO) {
        runCatching { Store.writePrivateAtomic(file, Store.toJson(_ui.value.data.copy(bot = null)).toString(2)) }
            .onSuccess { toast("Резервная копия сохранена: ${file.name}\nВ ней пароли серверов — храните файл в надёжном месте.") }
            .onFailure { toast("Не удалось сохранить: ${it.message}") }
    }

    fun importBackup(file: File) {
        if (_ui.value.active) return toast("Сначала отключитесь")
        scope.launch(Dispatchers.IO) {
            runCatching {
                check(file.length() in 1..Subscriptions.MAX_BYTES) { "файл пуст или слишком большой" }
                val state = Store.fromJson(JSONObject(file.readText()))
                check(state.servers.isNotEmpty() || state.subscriptions.isNotEmpty()) { "в файле нет серверов и подписок Hydra" }
                state
            }.onSuccess { state ->
                // Автозапуск — состояние ОС, а не файла: его копия не включает.
                mutate { cur -> state.copy(settings = state.settings.copy(launchAtLogin = cur.settings.launchAtLogin), bot = cur.bot) }
                toast("Восстановлено: серверов ${state.servers.size}, подписок ${state.subscriptions.size}")
            }.onFailure { toast("Не удалось восстановить: ${it.message}") }
        }
    }

    /** Выставляется из Main: корректный выход (отключить VPN, снять блокировку одного экземпляра, закрыть окно). */
    @Volatile var quitHandler: (() -> Unit)? = null

    /** Скачать обновление напрямую (SHA-256 сверяется) и запустить установщик ОС. */
    fun installUpdate() {
        val r = _ui.value.update ?: return
        if (_ui.value.updateProgress != null) return
        _ui.update { it.copy(updateProgress = 0f) }
        scope.launch(Dispatchers.IO) {
            try {
                val file = Updates.download(r) { done, total ->
                    if (total > 0) _ui.update { it.copy(updateProgress = (done.toFloat() / total).coerceIn(0f, 1f)) }
                    true
                }
                val (quit, text) = Updates.install(file)
                toast(text)
                if (quit) { shutdown(); quitHandler?.invoke() }
            } catch (e: Exception) {
                toast("Не удалось обновить: ${e.message}")
            } finally {
                _ui.update { it.copy(updateProgress = null) }
            }
        }
    }

    fun checkForUpdates(manual: Boolean) = scope.launch(Dispatchers.IO) {
        runCatching { Updates.latest() }
            .onSuccess { r ->
                if (Updates.isNewer(r.version, Platform.version)) _ui.update { it.copy(update = r) }
                else if (manual) toast("Установлена последняя версия (${Platform.version})")
            }
            .onFailure { if (manual) toast("Не удалось проверить обновления: ${it.message}") }
    }

    // ------------------------------------------------------------------ соединение
    fun toggle() = if (_ui.value.active) disconnect() else connect()

    fun connect() {
        val st = _ui.value
        if (st.active || st.status == Status.STOPPING) return
        val profile = st.selected ?: return toast("Выберите сервер")
        val settings = st.data.settings
        if (!DesktopConfig.isSupported(profile)) {
            return toast("«${profile.protocol?.displayName ?: profile.protocolId}» на ПК пока недоступен — выберите другой сервер")
        }
        val engine = DesktopConfig.engineFor(profile, settings, xrayAvailable)
            ?: return toast("Для «${profile.protocol?.displayName}» не включено ни одно ядро — Настройки → Движки")
        userStopped = false
        reconnectAttempt = 0
        _ui.update { it.copy(log = emptyList(), upTotal = 0, downTotal = 0) }
        launchSession(profile, settings, engine, "Подключение…")
    }

    private fun launchSession(profile: ServerProfile, settings: DesktopSettings, engine: EngineToggles.Kind, text: String) {
        val sessionId = ++session
        _ui.update {
            it.copy(status = Status.CONNECTING, statusText = text, connectedId = profile.id, engine = engine,
                upSpeed = 0, downSpeed = 0, delayMs = null, needsElevation = false)
        }
        scope.launch(Dispatchers.IO) { connLock.withLock { startSession(sessionId, profile, effectiveSettings(settings), engine) } }
    }

    /** Подставляет настоящий адрес вместо метки приватного DNS (адрес с токеном нигде не сохраняется). */
    private fun effectiveSettings(s: DesktopSettings): DesktopSettings =
        if (s.routing.dns != HYDRA_DNS) s
        else s.copy(routing = s.routing.copy(dns = if (hydraDnsAvailable) HydraSecrets.PRIVATE_DNS else "1.1.1.1"))

    private suspend fun startSession(sessionId: Int, profile: ServerProfile, settings: DesktopSettings, engine: EngineToggles.Kind) {
        if (session != sessionId) return
        // Клиенты узлов пишут ключи в файлы 0600 ещё до запуска; если запуск сорвался раньше runner.start, чистим их сами.
        var extras: MutableList<Sidecar> = mutableListOf()
        try {
            if (settings.mode == ConnectionMode.PROXY) ensurePortFree(settings.proxyPort, "прокси")
            if (settings.lanShare.isUsable) ensurePortFree(settings.lanShare.port, "раздачи в сеть", any = true)
            val api = DesktopConfig.Api(freePort(), randomSecret())
            val sidecar: Sidecar? = when (engine) {
                EngineToggles.Kind.OLCRTC -> olcRtcSidecar(profile)
                EngineToggles.Kind.OPENFLUX -> openFluxSidecar(profile)
                EngineToggles.Kind.BYEDPI -> byeDpiSidecar(profile)
                else -> null
            }
            val bridge = when {
                engine == EngineToggles.Kind.XRAY -> DesktopConfig.XrayBridge(freePort(), XrayConfigBuilder.SocksAuth("hydra", randomSecret()))
                sidecar != null -> DesktopConfig.XrayBridge(sidecar.socksPort, null)   // клиент olcRTC/OpenFlux — SOCKS5 без пароля на 127.0.0.1
                else -> null
            }
            val xrayConfig = bridge?.takeIf { engine == EngineToggles.Kind.XRAY }?.let {
                // В TUN адрес сервера — заранее, пока туннеля нет (см. DesktopConfig.xray).
                val ip = if (settings.mode == ConnectionMode.TUN) resolve(profile.address) else null
                DesktopConfig.xray(profile, settings, it, ip).toString(2)
            }
            // 0.7.13: правила, группы, цепочки, обход DPI — дополнительные клиенты (ByeDPI, узлы на OpenFlux/olcRTC) поднимаются до sing-box.
            extras = mutableListOf()
            val plan = buildPlan(profile, settings, extras)
            val bypass = listOfNotNull(Platform.processExecutable, bridge?.takeIf { engine == EngineToggles.Kind.XRAY }?.let { Platform.bundledXray()?.absolutePath }, sidecar?.exe?.absolutePath) +
                extras.map { it.exe.absolutePath }
            val config = DesktopConfig.build(profile, settings, Platform.os, api, Platform.geoDir(), bridge, bypass, plan, Platform.geoStore) { appendLog("Маршрутизация: $it") }
            runner.start(sessionId, config.toString(2), settings.mode, xrayConfig, bridge?.port ?: 0, sidecar, extras)
            if (session != sessionId) {   // пока ядро поднималось, нажали «Отключить»
                runner.stop()
                return
            }
            val clash = ClashApi(api)
            clash.streamTraffic({ session == sessionId && runner.isAlive }) { up, down ->
                if (session == sessionId) {
                    _ui.update { it.copy(upSpeed = up, downSpeed = down, upTotal = it.upTotal + up, downTotal = it.downTotal + down) }
                }
            }
            if (session == sessionId && settings.mode == ConnectionMode.PROXY && settings.setSystemProxy) {
                SystemProxy.enable(settings.proxyPort).onFailure { toast("Системный прокси не установлен: ${it.message}") }
            }
            _ui.update { it.copy(blocked = false) }
            verify(clash, sessionId, settings, engine)
            if (session == sessionId && runner.isAlive) watchNetwork(sessionId, profile, engine)
        } catch (e: NeedsElevation) {
            runner.stop()
            extras.forEach { sc -> sc.secrets.forEach { runCatching { it.delete() } } }
            if (session == sessionId) {
                SystemProxy.restore()
                _ui.update { it.copy(status = Status.DISCONNECTED, statusText = "Нужны права администратора", needsElevation = true, connectedId = null, blocked = false) }
            }
        } catch (e: Exception) {
            runner.stop()
            extras.forEach { sc -> sc.secrets.forEach { runCatching { it.delete() } } }
            if (session == sessionId) {
                if (!keepBlocked(settings)) SystemProxy.restore()
                _ui.update { it.copy(status = Status.ERROR, statusText = e.message ?: e.toString(), connectedId = null, blocked = keepBlocked(settings)) }
            }
        }
    }

    /** Реальная проверка: HTTP-запрос через outbound proxy. Без неё «Подключено» = только «процесс жив». */
    private suspend fun verify(clash: ClashApi, sessionId: Int, settings: DesktopSettings, engine: EngineToggles.Kind) {
        var lastError = ""
        val how = (if (settings.mode == ConnectionMode.TUN) "TUN, весь трафик" else "прокси 127.0.0.1:${settings.proxyPort}") +
            when (engine) { EngineToggles.Kind.XRAY -> " · Xray"; EngineToggles.Kind.OLCRTC -> " · olcRTC"; EngineToggles.Kind.OPENFLUX -> " · OpenFlux"; EngineToggles.Kind.BYEDPI -> " · ByeDPI"; else -> " · sing-box" }
        repeat(4) { attempt ->
            delay(if (attempt == 0) 1200 else 2000)
            if (session != sessionId || !runner.isAlive) return
            clash.delay().onSuccess { ms ->
                reconnectAttempt = 0
                _ui.update { if (session != sessionId) it else it.copy(status = Status.CONNECTED, delayMs = ms, statusText = "Подключено · $how") }
                return
            }.onFailure { lastError = it.message.orEmpty() }
        }
        if (session == sessionId && runner.isAlive) {
            // Ядро живо, но сервер не отвечает: держим соединение, но честно показываем проблему.
            _ui.update { it.copy(status = Status.CONNECTED, delayMs = null, statusText = "Ядро запущено ($how), но сервер не отвечает: $lastError") }
        }
    }

    /**
     * Смена физической сети (0.7.9, см. [NetWatch]): соединения ядер к серверу остались на прежнем
     * интерфейсе и умирают молча — переподключаемся сами, как раньше приходилось пользователю.
     * Только при включённом «Переподключаться автоматически».
     */
    private fun watchNetwork(sessionId: Int, profile: ServerProfile, engine: EngineToggles.Kind) = scope.launch(Dispatchers.IO) {
        var base = NetWatch.fingerprint()
        while (isActive && session == sessionId && !userStopped && runner.isAlive) {
            delay(3_000)
            val now = NetWatch.fingerprint()
            if (now == base) continue
            delay(3_000)   // переход Wi-Fi ↔ кабель идёт в несколько шагов — ждём, пока сеть устоится
            val settled = NetWatch.fingerprint()
            if (settled == base) continue
            base = settled
            if (settled.isEmpty()) continue   // сети нет совсем — переподключимся, когда появится
            if (session != sessionId || userStopped || !_ui.value.data.settings.autoReconnect) continue
            appendLog("Hydra: сеть сменилась — переподключение")
            connLock.withLock {
                if (session != sessionId || userStopped) return@launch
                session++   // выход ядер прежнего сеанса — не авария и не повод для второго переподключения
                runner.stop()
            }
            val cur = _ui.value
            launchSession(profile, cur.data.settings, engine, "Сеть сменилась — переподключение…")
            return@launch
        }
    }

    /** Переподключиться тем же профилем (новые geo-базы, правила): ядро читает их только при запуске. */
    private fun reconnectSoon(text: String) {
        val st = _ui.value
        val profile = st.data.servers.firstOrNull { it.id == st.connectedId } ?: return
        val engine = DesktopConfig.engineFor(profile, st.data.settings, xrayAvailable) ?: return
        scope.launch(Dispatchers.IO) {
            connLock.withLock {
                if (userStopped) return@launch
                session++
                runner.stop()
            }
            launchSession(profile, _ui.value.data.settings, engine, text)
        }
    }

    fun disconnect() {
        val st = _ui.value
        // 0.7.16: и с «зависшим» прокси (kill switch после сбоя запуска) — иначе его нельзя было снять, пока не выйдешь из Hydra.
        if (!st.active && !st.blocked && !SystemProxy.engaged) return
        userStopped = true
        session++
        _ui.update { it.copy(status = Status.STOPPING, statusText = "Отключение…") }
        scope.launch(Dispatchers.IO) {
            connLock.withLock {
                SystemProxy.restore()
                runner.stop()
            }
            _ui.update { it.copy(status = Status.DISCONNECTED, statusText = "Не подключено", connectedId = null,
                upSpeed = 0, downSpeed = 0, delayMs = null, blocked = false) }
        }
    }

    /** Kill switch в режиме прокси: системный прокси остаётся на мёртвый порт — программы не ходят мимо VPN. */
    private fun keepBlocked(s: DesktopSettings) = s.killSwitch && s.mode == ConnectionMode.PROXY && s.setSystemProxy

    private fun onCoreExit(sessionId: Int, code: Int, core: String) {
        if (userStopped || sessionId != session || exitHandled == sessionId) return
        exitHandled = sessionId
        val st = _ui.value
        val settings = st.data.settings
        val reason = st.log.lastOrNull { "FATAL" in it || "ERROR" in it || "Failed" in it }?.substringAfter("] ")
        val blocked = keepBlocked(settings)
        scope.launch(Dispatchers.IO) {
            connLock.withLock {
                runner.stop()   // второе ядро без первого бесполезно
                if (!blocked) SystemProxy.restore()
            }
        }
        val died = "Ядро $core остановилось (код $code)" + (reason?.let { ": $it" } ?: "")
        val profile = st.data.servers.firstOrNull { it.id == st.connectedId }
        val engine = profile?.let { DesktopConfig.engineFor(it, settings, xrayAvailable) }
        if (settings.autoReconnect && profile != null && engine != null && reconnectAttempt < ReconnectPolicy.MAX_ATTEMPTS) {
            val n = reconnectAttempt++
            val wait = ReconnectPolicy.backoffMs(n)
            appendLog("Hydra: $died — переподключение через ${wait / 1000} с (попытка ${n + 1}/${ReconnectPolicy.MAX_ATTEMPTS})")
            _ui.update { it.copy(status = Status.CONNECTING, blocked = blocked, upSpeed = 0, downSpeed = 0, delayMs = null,
                statusText = "Соединение потеряно — переподключение (${n + 1}/${ReconnectPolicy.MAX_ATTEMPTS})…") }
            scope.launch(Dispatchers.IO) {
                delay(wait)
                // За время паузы пользователь мог отключиться или начать новое подключение.
                if (!userStopped && session == sessionId) {
                    val cur = _ui.value
                    launchSession(profile, cur.data.settings, engine, cur.statusText)
                }
            }
            return
        }
        _ui.update {
            it.copy(status = Status.ERROR, connectedId = null, upSpeed = 0, downSpeed = 0, blocked = blocked,
                statusText = if (blocked) "$died. Kill switch: интернет заблокирован до отключения" else died)
        }
    }

    /** Windows: можно перезапустить Hydra от имени администратора (для TUN обязательно, для прокси — по желанию). */
    val elevationAvailable: Boolean get() = ru.gidravpn.hydra.desktop.core.Elevation.canRelaunch
    val isElevated: Boolean get() = ru.gidravpn.hydra.desktop.core.Elevation.admin

    /**
     * Перезапуск от администратора (0.7.16). Сначала запрос UAC — и только после согласия останавливаем ядра и выходим:
     * отказ (или ошибка запуска) ничего не ломает. Раньше ядра гасились до запроса, а при отказе окно оставалось
     * «Подключено» с мёртвым ядром; к тому же запрос шёл в потоке интерфейса и вешал окно.
     */
    fun relaunchElevated() {
        if (!elevationAvailable) return toast("Перезапуск от администратора есть только в Windows")
        if (isElevated) return toast("Hydra уже запущена от администратора")
        toast("Подтвердите запрос Windows (UAC)…")
        val wasActive = _ui.value.active
        scope.launch(Dispatchers.IO) {
            if (ru.gidravpn.hydra.desktop.core.Elevation.relaunchAsAdmin(wasActive)) {
                shutdown()
                quitHandler?.invoke()
            } else toast("Запрос прав администратора отклонён")
        }
    }

    /** Синхронно: вызывается при выходе из приложения. */
    fun shutdown() {
        userStopped = true
        session++
        SystemProxy.restore()
        runner.stop()
    }

    // sing-box красит лог ANSI-кодами даже в пайп (опции отключения в 1.12 нет).
    private val ansi = Regex("\u001B\\[[0-9;]*m")

    private fun appendLog(line: String) {
        val clean = line.replace(ansi, "").take(2000)
        _ui.update { it.copy(log = (it.log + clean).takeLast(400)) }
    }

    fun clearLog() = _ui.update { it.copy(log = emptyList()) }

    private fun ensurePortFree(port: Int, what: String, any: Boolean = false) {
        runCatching { ServerSocket(port, 1, InetAddress.getByName(if (any) "0.0.0.0" else "127.0.0.1")).close() }
            .onFailure { error("Порт $port ($what) уже занят другой программой — смените его в настройках") }
    }

    private fun resolve(host: String): String? = runCatching {
        val all = InetAddress.getAllByName(host)
        (all.firstOrNull { it is Inet4Address } ?: all.first()).hostAddress
    }.getOrNull()

    /** Клиент olcRTC: YAML с ключом комнаты лежит только на время работы (0600) и удаляется при остановке. */
    private fun olcRtcSidecar(profile: ServerProfile): Sidecar {
        val exe = Platform.bundledOlcRtc() ?: error("olcRTC не входит в эту сборку Hydra (нужен Go при сборке) — выберите другой сервер")
        val port = freePort()
        val cfg = File(Platform.runDir, "olcrtc-client-$port.yaml")
        Store.writePrivateAtomic(cfg, OlcRtcConfigBuilder.build(profile, port))
        return Sidecar("olcRTC", exe, listOf(cfg.absolutePath), port, secrets = listOf(cfg))
    }

    /** Клиент OpenFlux: ключ шифрования — файл 0600 на время работы. */
    private fun openFluxSidecar(profile: ServerProfile): Sidecar {
        val exe = Platform.bundledOpenFlux() ?: error("OpenFlux не входит в эту сборку Hydra — выберите другой сервер")
        val port = freePort()
        val key = if (profile.uuidOrPassword.isNotEmpty()) File(Platform.runDir, "openflux-$port.key").also { Store.writePrivateAtomic(it, profile.uuidOrPassword) } else null
        return Sidecar("OpenFlux", exe, OpenFluxArgs.build(profile, port, key?.absolutePath), port, secrets = listOfNotNull(key))
    }

    /** Профиль «Обход DPI»: ciadpi как основной клиент, sing-box — мост от tun/прокси к его SOCKS5. */
    private fun byeDpiSidecar(profile: ServerProfile): Sidecar {
        val exe = Platform.bundledByeDpi() ?: error("ByeDPI не входит в эту сборку Hydra — выберите другой сервер")
        val port = freePort()
        val strategy = runCatching { JSONObject(profile.extra).optString("strategy") }.getOrDefault("")
        return Sidecar("ByeDPI", exe, DpiArgs.build(strategy, port), port, readyTimeoutMs = 8_000)
    }

    /** Узел маршрутизации на движке-подпроцессе (OpenFlux, olcRTC): свой клиент на свободном порту, выход — socks на него. */
    private fun nodeSocks(p: ServerProfile, tag: String, extras: MutableList<Sidecar>): JSONObject? = runCatching {
        val sc = when (p.protocol?.engine) {
            Engine.OPENFLUX -> openFluxSidecar(p)
            Engine.OLCRTC -> olcRtcSidecar(p)
            else -> return null
        }
        extras += sc
        JSONObject().put("type", "socks").put("tag", tag).put("server", "127.0.0.1").put("server_port", sc.socksPort).put("version", "5")
    }.getOrElse { appendLog("Маршрутизация: узел $tag не подготовлен — ${it.message}"); null }

    /** План маршрутизации для подключения: правила по программам, группы, цепочки, ByeDPI. null — настроек нет. */
    private fun buildPlan(profile: ServerProfile, settings: DesktopSettings, extras: MutableList<Sidecar>): RoutePlan? {
        val cfg = settings.routing.routes
        if (cfg.rules.isEmpty() && cfg.groups.isEmpty() && !cfg.dpi.enabled && viaOf(profile) == null) return null
        val servers = _ui.value.data.servers
        val nodeOutbound = { p: ServerProfile, tag: String ->
            if (p.protocol?.engine == Engine.SINGBOX) SingBoxConfigBuilder.nodeOutbound(p, tag) else nodeSocks(p, tag, extras)
        }
        var plan = RoutePlanFactory.build(cfg, profile, setOf(RouteKind.PROCESS), { id -> servers.firstOrNull { it.id == id } }, nodeOutbound)
        if (plan.needsDpi) {
            val exe = Platform.bundledByeDpi()
            if (exe == null) {
                appendLog("ByeDPI: в этой сборке нет ciadpi — продолжаем без обхода DPI")
                plan = plan.withoutDpi()
            } else if (runCatching { ServerSocket(plan.dpi.port, 1, InetAddress.getByName("127.0.0.1")).close() }.isFailure) {
                // Порт занят (вторая копия Hydra, другая программа): подключение лучше, чем ошибка из-за необязательного обхода.
                appendLog("ByeDPI: порт ${plan.dpi.port} занят — продолжаем без обхода DPI")
                plan = plan.withoutDpi()
            } else {
                extras += Sidecar("ByeDPI", exe, DpiArgs.build(plan.dpi.strategy, plan.dpi.port, sni = plan.dpi.fakeSni), plan.dpi.port, readyTimeoutMs = 8_000)
            }
        }
        if (plan.needsTgWs) {
            // 0.7.14: Telegram через WebSocket — внутри процесса Hydra (он исключён из tun наравне с ядрами-подпроцессами).
            val tg = ru.gidravpn.hydra.data.tgws.TgWsProxy(log = { appendLog(it) })
            try {
                tg.start(RouteTarget.TGWS_PORT)
                runner.attachInProcess(AutoCloseable { tg.stop() })
            } catch (e: Exception) {
                appendLog("TG WS: не запущен (${e.message}) — продолжаем без него")
                tg.stop()
                plan = plan.withoutTgWs()
            }
        }
        return plan
    }

    // ------------------------------------------------------------------ обход DPI и маршрутизация (0.7.13)
    fun updateRoutes(block: (RouteConfig) -> RouteConfig) = updateRouting { it.copy(routes = block(it.routes)) }
    fun setDpi(block: (DpiSettings) -> DpiSettings) = updateRoutes { it.copy(dpi = block(it.dpi)) }
    /** Настройки подбора стратегии (0.7.15). */
    fun setProbe(block: (ru.gidravpn.hydra.data.dpi.DpiProbeSettings) -> ru.gidravpn.hydra.data.dpi.DpiProbeSettings) = setDpi { it.copy(probe = block(it.probe)) }

    /** Передать Telegram локальный прокси Hydra: `tg://socks?…` (Telegram спросит, включить ли). Порт — TG WS, если он включён, иначе ByeDPI. */
    fun telegramProxyPort(): Int {
        val routes = _ui.value.data.settings.routing.routes
        return if (ru.gidravpn.hydra.data.tgws.TgWsPreset.isApplied(routes.rules)) RouteTarget.TGWS_PORT else routes.dpi.port
    }
    fun openTelegramProxy(port: Int = telegramProxyPort()) {
        val ok = runCatching {
            java.awt.Desktop.getDesktop().browse(java.net.URI("tg://socks?server=127.0.0.1&port=$port"))
        }.isSuccess
        if (!ok) toast("Не удалось открыть Telegram: он не установлен или не принимает ссылки tg://")
    }
    fun addRouteRule(r: RouteRule) = updateRoutes { it.copy(rules = (it.rules + r).distinct().take(Rules.MAX_RULES)) }
    fun removeRouteRule(r: RouteRule) = updateRoutes { it.copy(rules = it.rules - r) }
    fun addRouteGroup(g: RouteGroup) = updateRoutes { it.copy(groups = it.groups.filterNot { x -> x.tag == g.tag } + g) }
    fun removeRouteGroup(g: RouteGroup) = updateRoutes { it.copy(groups = it.groups - g) }
    fun setGeo(block: (GeoSettings) -> GeoSettings) = updateRouting { it.copy(geo = block(it.geo)) }

    /** «Пустить этот сервер через …»: `dpi`, `node-<id>` или null. */
    fun setServerVia(server: ServerProfile, via: String?) = mutate { s ->
        s.copy(servers = s.servers.map { if (it.id == server.id) withVia(it, via) else it })
    }

    /** Профиль «Обход DPI» (без VPN): трафик выходит напрямую, но «порезанным» ByeDPI. */
    fun addByeDpiProfile(strategy: String) {
        mutate { s ->
            val p = ServerProfile(id = nextId(s.servers.map { it.id }), name = "Обход DPI", protocolId = ru.gidravpn.hydra.data.model.Protocol.BYEDPI.id,
                address = "127.0.0.1", port = DpiSettings.DEFAULT_PORT, extra = JSONObject().put("strategy", strategy).toString(), flag = "🛡️")
            s.copy(servers = s.servers + p)
        }
        toast("Добавлен профиль «Обход DPI»")
    }

    /** Применить найденную стратегию: она станет стратегией обхода «напрямую», сам обход включится. */
    fun useDpiStrategy(strategy: String) = setDpi { it.copy(enabled = true, strategy = strategy) }

    /**
     * Пресет «Заблокированное в РФ → обход DPI»: правила geosite/geoip набора ru-blocked на выход «dpi» и включённый обход.
     * Наборы есть только у runetfreedom — добавляются как свои источники geo-баз и скачиваются сразу.
     */
    fun applyBlockedRuPreset() {
        val src = GeoSources.RUNETFREEDOM
        updateRouting { r ->
            r.copy(
                routes = r.routes.copy(
                    dpi = r.routes.dpi.copy(enabled = true),
                    rules = (r.routes.rules + listOf(
                        RouteRule(RouteKind.GEOSITE, "ru-blocked", RouteTarget.DPI),
                        RouteRule(RouteKind.GEOIP, "ru-blocked", RouteTarget.DPI),
                    )).distinct(),
                ),
                geo = r.geo.copy(custom = r.geo.custom.filterNot { it.name == "ru-blocked" } + listOf(
                    CustomGeoSource(GeoKind.SITE, "ru-blocked", src.site[0].replace("{name}", "ru-blocked")),
                    CustomGeoSource(GeoKind.IP, "ru-blocked", src.ip[0].replace("{name}", "ru-blocked")),
                )),
            )
        }
        updateGeo(manual = true)
    }

    /** Пресет «Telegram через WebSocket» (0.7.14): подсети Telegram → выход «tgws»; повторное нажатие снимает. */
    fun toggleTgWsPreset() {
        val rules = ru.gidravpn.hydra.data.tgws.TgWsPreset.rules()
        updateRoutes { c ->
            if (ru.gidravpn.hydra.data.tgws.TgWsPreset.isApplied(c.rules)) c.copy(rules = c.rules - rules.toSet())
            else c.copy(rules = (c.rules + rules).distinct().take(Rules.MAX_RULES))
        }
    }

    fun rollbackGeo(kind: GeoKind, name: String) {
        Platform.geoStore.rollback(kind, name)
        _ui.update { it.copy(geoTick = it.geoTick + 1) }
        if (_ui.value.active) reconnectSoon("Geo-базы изменены — переподключение")
    }

    fun removeGeo(kind: GeoKind, name: String) {
        Platform.geoStore.remove(kind, name)
        _ui.update { it.copy(geoTick = it.geoTick + 1) }
        if (_ui.value.active) reconnectSoon("Geo-базы изменены — переподключение")
    }

    val dpiProbe = MutableStateFlow(DpiProbeUi())
    private var probeJob: kotlinx.coroutines.Job? = null

    /** Запуск ciadpi с точными аргументами для проверки; бросает, если не поднялся. */
    private fun startCiadpi(exe: File, args: List<String>, port: Int): AutoCloseable {
        val p = ProcessBuilder(listOf(exe.absolutePath) + args).redirectErrorStream(true).start()
        Thread { runCatching { p.inputStream.readAllBytes() } }.apply { isDaemon = true }.start()   // не даём заполниться буферу вывода
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            check(p.isAlive) { "ciadpi завершился при запуске" }
            if (runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess) {
                return AutoCloseable {
                    p.destroy()
                    if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
                }
            }
            Thread.sleep(100)
        }
        p.destroyForcibly()
        error("ciadpi: порт $port не открылся")
    }

    /**
     * Мастер подбора обхода: [groups] — что разблокировать (ключи [DpiStrategies.SITES]), [extraSite] — свой сайт,
     * [full] — все стратегии (иначе первые [DpiProbe.QUICK]).
     */
    fun probeDpi(extraSite: String, full: Boolean, groupsOverride: Set<String>? = null) {
        if (probeJob?.isActive == true) return
        val exe = Platform.bundledByeDpi() ?: return toast("ByeDPI не входит в эту сборку Hydra")
        probeJob = scope.launch(Dispatchers.IO) {
            // Списки и стратегии — из настроек подбора (0.7.15): встроенные и свои списки доменов, свой список стратегий.
            val dpi = _ui.value.data.settings.routing.routes.dpi
            val ps = if (groupsOverride != null) dpi.probe.copy(groups = groupsOverride) else dpi.probe
            val sites = LinkedHashMap(ps.selectedSites())
            extraSite.trim().takeIf { it.isNotEmpty() }?.let { sites["custom"] = listOf(it) }
            if (sites.isEmpty()) sites["general"] = DpiStrategies.SITES.getValue("general")
            val own = ps.strategyLines().takeIf { ps.customStrategiesOn && it.isNotEmpty() }
            val list = own ?: if (full) DpiStrategies.PRESETS else DpiStrategies.PRESETS.take(DpiProbe.QUICK)
            dpiProbe.value = DpiProbeUi(running = true, total = list.size)
            val port = freePort()
            try {
                val res = DpiProbe.run(list, sites, port,
                    timeoutMs = ps.timeoutSec * 1000, concurrency = ps.parallel, requests = ps.requests, delayMs = ps.delaySec * 1000L, sni = dpi.fakeSni,
                    start = { args -> startCiadpi(exe, args, port) },
                    onBaseline = { b -> dpiProbe.update { it.copy(baseline = b) } },
                    // Копим все результаты, а не только тройку лучших: в окне их можно просмотреть целиком.
                    onResult = { i, n, r -> dpiProbe.update { it.copy(done = i, total = n, results = (it.results + r).sortedByDescending { x -> x.ratio }) } })
                dpiProbe.update { it.copy(running = false, finished = true, results = res) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                dpiProbe.value = DpiProbeUi(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelProbe() { probeJob?.cancel(); dpiProbe.value = DpiProbeUi() }

    // ------------------------------------------------------------------ geo-базы (0.7.13)
    @Volatile private var geoBusy = false

    /** Какие базы нужны сейчас: страны режима geo, правила «страна → выход», дополнительные наборы и свои источники. */
    fun geoWanted(r: RoutingSettings): List<Pair<GeoKind, String>> {
        val out = linkedSetOf<Pair<GeoKind, String>>()
        if (r.geoMode != GeoRoutingMode.OFF) r.geoCountries.forEach { cc ->
            out += GeoKind.IP to cc
            val bundledSite = Platform.geoDir()?.let { File(it, "geosite/$cc.srs").isFile } == true
            if (bundledSite || Platform.geoStore.resolve(GeoKind.SITE, cc) != null) out += GeoKind.SITE to cc
        }
        r.routes.rules.forEach {
            when (it.kind) {
                RouteKind.GEOIP -> out += GeoKind.IP to it.value.trim().lowercase()
                RouteKind.GEOSITE -> out += GeoKind.SITE to it.value.trim().lowercase()
                else -> Unit
            }
        }
        r.geo.extraIp.forEach { out += GeoKind.IP to it }
        r.geo.extraSite.forEach { out += GeoKind.SITE to it }
        r.geo.custom.forEach { out += it.kind to it.name }
        return out.filter { it.second.isNotBlank() }
    }

    /**
     * Обновить geo-базы. [manual] — по кнопке (всегда, с сообщением); иначе по расписанию (только если пора и включено).
     * При включённом режиме «прокси» качает через локальный HTTP-вход — так работает и там, где GitHub закрыт, а сервер доступен.
     */
    fun updateGeo(manual: Boolean) {
        if (geoBusy) return
        val st = _ui.value.data.settings
        val g = st.routing.geo
        val store = Platform.geoStore
        if (!manual && (!g.autoUpdate || System.currentTimeMillis() - store.lastChecked() < g.intervalHours * 3_600_000L)) return
        val wanted = geoWanted(st.routing)
        if (wanted.isEmpty()) { if (manual) toast("Нечего обновлять: включите geo-маршрутизацию или добавьте правила/наборы"); return }
        geoBusy = true
        scope.launch(Dispatchers.IO) {
            try {
                val viaProxy = if (_ui.value.status == Status.CONNECTED && st.mode == ConnectionMode.PROXY) st.proxyPort else 0
                val rep = GeoUpdater(store, JvmGeoHttp(viaProxy)).update(g, wanted) { appendLog(it) }
                appendLog("Geo-базы: обновлено ${rep.installed}, без изменений ${rep.unchanged}, ошибок ${rep.failed}")
                if (manual) toast("Geo-базы: +${rep.installed} / =${rep.unchanged} / ✕${rep.failed}")
                if (rep.installed > 0 && _ui.value.active) reconnectSoon("Geo-базы обновлены — переподключение")
                _ui.update { it.copy(geoTick = it.geoTick + 1) }
            } catch (e: Exception) {
                if (manual) toast("Geo-базы: ${e.message}")
            } finally { geoBusy = false }
        }
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private val random = SecureRandom()

    private fun randomSecret(): String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun randomPassword(): String {
        val abc = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..12).map { abc[random.nextInt(abc.length)] }.joinToString("")
    }

    private fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: "Подписка"
}
