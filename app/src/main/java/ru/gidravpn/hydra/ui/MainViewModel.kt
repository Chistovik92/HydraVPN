package ru.gidravpn.hydra.ui

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ru.gidravpn.hydra.data.model.DnsProvider
import ru.gidravpn.hydra.data.model.Engine
import ru.gidravpn.hydra.data.model.GeoRoutingMode
import ru.gidravpn.hydra.data.model.NetworkRule
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.SplitTunnel
import ru.gidravpn.hydra.data.model.SplitTunnelMode
import ru.gidravpn.hydra.data.net.PingMeasurer
import ru.gidravpn.hydra.data.model.Subscription
import ru.gidravpn.hydra.data.repository.EngineRepository
import ru.gidravpn.hydra.data.repository.RoutingRepository
import ru.gidravpn.hydra.data.repository.ServerRepository
import ru.gidravpn.hydra.data.repository.SplitTunnelRepository
import ru.gidravpn.hydra.data.repository.ThemeRepository
import ru.gidravpn.hydra.data.repository.VpnSettingsRepository
import ru.gidravpn.hydra.ui.theme.ThemeMode
import ru.gidravpn.hydra.vpn.HydraVpnService
import ru.gidravpn.hydra.vpn.VpnState
import ru.gidravpn.hydra.vpn.core.ConnectionState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ServerRepository(app)
    private val splitRepo = SplitTunnelRepository(app)
    private val themeRepo = ThemeRepository(app)
    private val engineRepo = EngineRepository(app)
    private val vpnSettingsRepo = VpnSettingsRepository(app)
    private val routingRepo = RoutingRepository(app)

    val themeMode: StateFlow<ThemeMode> = themeRepo.mode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.AMBIENT)

    /** Менять ли ярлык на рабочем столе вместе с темой (по умолчанию — нет). */
    val dynamicLauncherIcon: StateFlow<Boolean> = themeRepo.dynamicLauncherIcon
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch {
        themeRepo.setMode(mode)
        if (dynamicLauncherIcon.value) LauncherIcon.apply(getApplication(), mode)
    }

    fun setDynamicLauncherIcon(enabled: Boolean) = viewModelScope.launch {
        themeRepo.setDynamicLauncherIcon(enabled)
        // Включили — сразу подгоняем ярлык под текущую тему; выключили — возвращаем базовый.
        if (enabled) LauncherIcon.apply(getApplication(), themeMode.value)
        else LauncherIcon.reset(getApplication())
    }

    /** Xray Core вместо sing-box для VLESS/VMess/Trojan/SS (см. NativeCoreFactory). */
    val preferXray: StateFlow<Boolean> = engineRepo.preferXray
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setPreferXray(enabled: Boolean) = viewModelScope.launch { engineRepo.setPreferXray(enabled) }

    // ----- Фаза 6b: безопасность соединения -----

    val killSwitch: StateFlow<Boolean> = vpnSettingsRepo.killSwitch
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setKillSwitch(enabled: Boolean) = viewModelScope.launch { vpnSettingsRepo.setKillSwitch(enabled) }

    val autoConnectOnAppStart: StateFlow<Boolean> = vpnSettingsRepo.autoConnectOnAppStart
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setAutoConnectOnAppStart(enabled: Boolean) = viewModelScope.launch { vpnSettingsRepo.setAutoConnectOnAppStart(enabled) }

    val autoConnectOnBoot: StateFlow<Boolean> = vpnSettingsRepo.autoConnectOnBoot
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setAutoConnectOnBoot(enabled: Boolean) = viewModelScope.launch { vpnSettingsRepo.setAutoConnectOnBoot(enabled) }

    // ----- Фаза 6c: DNS и geoip-маршрутизация -----

    val dnsProvider: StateFlow<DnsProvider> = routingRepo.dnsProvider
        .stateIn(viewModelScope, SharingStarted.Eagerly, DnsProvider.CLOUDFLARE)
    fun setDnsProvider(provider: DnsProvider) = viewModelScope.launch { routingRepo.setDnsProvider(provider) }

    val dnsCustomAddress: StateFlow<String> = routingRepo.dnsCustomAddress
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    fun setDnsCustomAddress(address: String) = viewModelScope.launch { routingRepo.setDnsCustomAddress(address) }

    val geoRoutingMode: StateFlow<GeoRoutingMode> = routingRepo.geoRoutingMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, GeoRoutingMode.OFF)
    fun setGeoRoutingMode(mode: GeoRoutingMode) = viewModelScope.launch { routingRepo.setGeoRoutingMode(mode) }

    val geoCountries: StateFlow<Set<String>> = routingRepo.geoCountries
        .stateIn(viewModelScope, SharingStarted.Eagerly, setOf("ru"))
    fun toggleGeoCountry(code: String) = viewModelScope.launch {
        val current = geoCountries.value
        routingRepo.setGeoCountries(if (code in current) current - code else current + code)
    }

    /** Страны с IP-базой и (подмножество) с доменной — для экрана выбора. */
    val geoAvailable: List<String> by lazy { ru.gidravpn.hydra.data.subscription.GeoAssets.availableCountries(getApplication()) }
    val geoWithDomains: Set<String> by lazy { ru.gidravpn.hydra.data.subscription.GeoAssets.countriesWithDomains(getApplication()) }

    val mtu: StateFlow<ru.gidravpn.hydra.data.model.MtuPreset> = routingRepo.mtu
        .stateIn(viewModelScope, SharingStarted.Eagerly, ru.gidravpn.hydra.data.model.MtuPreset.AUTO)
    fun setMtu(preset: ru.gidravpn.hydra.data.model.MtuPreset) = viewModelScope.launch { routingRepo.setMtu(preset) }

    val tlsFragment: StateFlow<ru.gidravpn.hydra.data.model.TlsFragmentMode> = routingRepo.tlsFragment
        .stateIn(viewModelScope, SharingStarted.Eagerly, ru.gidravpn.hydra.data.model.TlsFragmentMode.OFF)
    fun setTlsFragment(mode: ru.gidravpn.hydra.data.model.TlsFragmentMode) =
        viewModelScope.launch { routingRepo.setTlsFragment(mode) }

    val servers: StateFlow<List<ServerProfile>> = repo.allServers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val subscriptions: StateFlow<List<Subscription>> = repo.allSubscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val splitTunnel: StateFlow<SplitTunnel> = splitRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SplitTunnel())

    fun setSplitMode(mode: SplitTunnelMode) = viewModelScope.launch {
        splitRepo.setMode(mode)
        VpnState.log("Split tunneling: режим ${mode.name}")
    }

    fun toggleSplitApp(pkg: String) = viewModelScope.launch {
        splitRepo.toggleApp(pkg)
    }

    fun setNetMode(mode: SplitTunnelMode) = viewModelScope.launch { splitRepo.setNetMode(mode) }
    fun addNetRule(rule: NetworkRule) = viewModelScope.launch { splitRepo.addNetRule(rule) }
    fun removeNetRule(rule: NetworkRule) = viewModelScope.launch { splitRepo.removeNetRule(rule) }

    val state = VpnState.state
    val logs = VpnState.logs
    val stats = VpnState.stats
    val activeServer = VpnState.activeServer
    val connectedSince = VpnState.connectedSince

    private val _selectedId = MutableStateFlow<Long?>(null)
    val selectedId = _selectedId.asStateFlow()

    init {
        // Восстанавливаем выбор сервера между запусками — нужно и UI (чтобы
        // "Подключить" на первом экране сразу знал сервер), и autoConnectIfEnabled()
        // ниже; BootReceiver/HydraQsTileService читают то же значение напрямую
        // из VpnSettingsRepository, минуя эту viewmodel.
        viewModelScope.launch {
            // Не перетираем выбор, если пользователь успел ткнуть сервер раньше.
            vpnSettingsRepo.lastServerId.firstOrNull()?.let { _selectedId.compareAndSet(null, it) }
        }
    }

    val selectedServer: StateFlow<ServerProfile?> =
        combine(servers, _selectedId) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Событие: нужно системное согласие на VPN. Активити ловит и запускает consent.
    private val _requestPermission = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestPermission = _requestPermission.asSharedFlow()

    /**
     * Выбор сервера в списке. Если соединение уже активно (или устанавливается),
     * сразу переключает туннель на новый сервер — VPN-consent уже выдан, повторный
     * запрос не нужен, поэтому идём напрямую через startTunnelWith(), минуя toggle().
     */
    fun select(id: Long) {
        _selectedId.value = id
        viewModelScope.launch { vpnSettingsRepo.setLastServerId(id) }
        if (state.value == ConnectionState.CONNECTED || state.value == ConnectionState.CONNECTING) {
            servers.value.firstOrNull { it.id == id }?.let { startTunnelWith(it) }
        }
    }

    /**
     * Автоподключение при холодном старте приложения — вызывается из
     * MainActivity.onCreate() ровно один раз (savedInstanceState == null).
     * Работает только если VPN-согласие уже выдавалось раньше (VpnService.prepare
     * вернёт null): без этого пришлось бы дёргать системный consent-диалог сразу
     * при открытии приложения, что не отличить от обычного запуска пользователем.
     */
    fun autoConnectIfEnabled() = viewModelScope.launch {
        if (vpnSettingsRepo.autoConnectOnAppStart.firstOrNull() != true) return@launch
        if (state.value != ConnectionState.DISCONNECTED) return@launch
        val id = vpnSettingsRepo.lastServerId.firstOrNull() ?: return@launch
        val server = repo.byId(id) ?: return@launch
        _selectedId.value = id
        val ctx = getApplication<Application>()
        if (VpnService.prepare(ctx) == null) startTunnelWith(server)
    }

    fun toggle() = viewModelScope.launch {
        if (state.value == ConnectionState.CONNECTED || state.value == ConnectionState.CONNECTING) {
            disconnect()
        } else {
            _requestPermission.tryEmit(Unit)   // активити проверит prepare() и вызовет startTunnel()
        }
    }

    fun startTunnel() {
        val server = selectedServer.value ?: run {
            VpnState.log("Ошибка: сервер не выбран"); return
        }
        startTunnelWith(server)
    }

    private fun startTunnelWith(server: ServerProfile) {
        val ctx = getApplication<Application>()

        // PPTP честно недоступен: GRE требует root, стек удалён из Android 12/13.
        if (server.protocol?.engine == Engine.UNAVAILABLE) {
            VpnState.log("${server.protocol?.displayName}: протокол недоступен на Android — используйте SSTP/L2TP/WireGuard")
            return
        }

        val intent = Intent(ctx, HydraVpnService::class.java).apply {
            action = HydraVpnService.ACTION_CONNECT
            putExtra(HydraVpnService.EXTRA_SERVER_ID, server.id)
        }
        ctx.startForegroundService(intent)
    }

    fun disconnect() {
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, HydraVpnService::class.java)
            .apply { action = HydraVpnService.ACTION_DISCONNECT })
    }

    fun addServer(name: String, address: String, port: Int, protocol: Protocol) =
        viewModelScope.launch {
            repo.save(ServerProfile(
                name = name, address = address, port = port, protocolId = protocol.id
            ))
            VpnState.log("Сервер \"$name\" добавлен")
        }

    fun importLink(link: String) = viewModelScope.launch {
        val p = runCatching { repo.importLink(link) }.getOrNull()
        VpnState.log(if (p != null) "Импортирован: ${p.name}" else "Не удалось разобрать ссылку")
    }

    fun addSubscription(name: String, url: String) = viewModelScope.launch {
        val n = runCatching { repo.addSubscription(name, url) }.getOrDefault(0)
        VpnState.log("Подписка \"$name\": импортировано $n серверов")
    }

    fun delete(server: ServerProfile) = viewModelScope.launch { repo.delete(server) }
    fun clearLogs() = VpnState.clearLogs()

    // ----- Фаза 6d: резервная копия и сброс -----

    private val _backupMessage = MutableStateFlow<String?>(null)
    /** Результат последней операции с копией — показывается на экране, пока не сброшен. */
    val backupMessage = _backupMessage.asStateFlow()
    fun dismissBackupMessage() { _backupMessage.value = null }

    fun exportBackup(uri: android.net.Uri) = viewModelScope.launch {
        val app = getApplication<Application>()
        _backupMessage.value = runCatching {
            val text = ru.gidravpn.hydra.data.backup.BackupManager.export(app)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
            }
            "Копия сохранена: ${servers.value.size} серв., все настройки"
        }.getOrElse { "Не удалось сохранить: ${it.message ?: it.javaClass.simpleName}" }
    }

    fun importBackup(uri: android.net.Uri) = viewModelScope.launch {
        if (state.value != ConnectionState.DISCONNECTED) {
            _backupMessage.value = "Отключите VPN перед восстановлением — иначе туннель останется со старыми настройками"
            return@launch
        }
        val app = getApplication<Application>()
        _backupMessage.value = runCatching {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            }
            val s = ru.gidravpn.hydra.data.backup.BackupManager.import(app, text)
            _selectedId.value = vpnSettingsRepo.lastServerId.firstOrNull()
            VpnState.log("Восстановлено из копии: ${s.servers} серв., ${s.subscriptions} подп., ${s.settings} настроек")
            "Восстановлено: ${s.servers} серверов, ${s.subscriptions} подписок, ${s.settings} настроек"
        }.getOrElse { "Не удалось восстановить: ${it.message ?: it.javaClass.simpleName}" }
    }

    fun resetSettings() = viewModelScope.launch {
        ru.gidravpn.hydra.data.backup.BackupManager.resetSettings(getApplication())
        LauncherIcon.reset(getApplication())
        VpnState.log("Настройки сброшены к значениям по умолчанию")
        _backupMessage.value = "Настройки сброшены. Серверы и подписки не тронуты."
    }

    private val _measuringIds = MutableStateFlow<Set<Long>>(emptySet())
    val measuringIds: StateFlow<Set<Long>> = _measuringIds.asStateFlow()

    fun measurePing(server: ServerProfile) = viewModelScope.launch {
        _measuringIds.update { it + server.id }
        val ms = PingMeasurer.measure(server.address, server.port)
        repo.save(server.copy(pingMs = ms))
        _measuringIds.update { it - server.id }
    }

    fun measureAllPings() {
        servers.value.forEach { measurePing(it) }
    }
}
