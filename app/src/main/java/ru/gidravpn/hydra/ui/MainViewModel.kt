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
import kotlinx.coroutines.sync.withPermit

class MainViewModel(app: Application) : AndroidViewModel(app) {
    /**
     * ÐÑÐµ ÐºÐ¾ÑÑÑÐ¸Ð½Ñ Ð²ÑÑÐ¼Ð¾Ð´ÐµÐ»Ð¸ Ð¸Ð´ÑÑ ÑÐµÑÐµÐ· safeLaunch: Ñ viewModelScope Ð½ÐµÑ ÑÐ²Ð¾ÐµÐ³Ð¾
     * Ð¾Ð±ÑÐ°Ð±Ð¾ÑÑÐ¸ÐºÐ°, Ð¸ Ð»ÑÐ±Ð¾Ðµ Ð¸ÑÐºÐ»ÑÑÐµÐ½Ð¸Ðµ Ð¸Ð· Room/DataStore (Ð¿ÐµÑÐµÐ¿Ð¾Ð»Ð½ÐµÐ½Ð½ÑÐ¹ Ð´Ð¸ÑÐº,
     * Ð¿Ð¾Ð²ÑÐµÐ¶Ð´ÑÐ½Ð½Ð°Ñ Ð±Ð°Ð·Ð°) ÑÐ¾Ð½ÑÐ»Ð¾ Ð¿ÑÐ¾ÑÐµÑÑ Ð¿ÑÑÐ¼Ð¾ Ð¸Ð· ÑÐ¾Ð½Ð¾Ð²Ð¾Ð³Ð¾ ÑÐ¾ÑÑÐ°Ð½ÐµÐ½Ð¸Ñ Ð½Ð°ÑÑÑÐ¾Ð¹ÐºÐ¸.
     * Ð¢ÐµÐ¿ÐµÑÑ Ð¾Ð½Ð¾ Ð¿Ð¾Ð¿Ð°Ð´Ð°ÐµÑ Ð² Ð¶ÑÑÐ½Ð°Ð», Ð° Ð¿ÑÐ¸Ð»Ð¾Ð¶ÐµÐ½Ð¸Ðµ Ð¿ÑÐ¾Ð´Ð¾Ð»Ð¶Ð°ÐµÑ ÑÐ°Ð±Ð¾ÑÐ°ÑÑ.
     */
    private fun safeLaunch(
        context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
        block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit,
    ) = viewModelScope.launch(context) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            VpnState.log("ÐÑÐ¸Ð±ÐºÐ°: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private val repo = ServerRepository(app)
    private val splitRepo = SplitTunnelRepository(app)
    private val themeRepo = ThemeRepository(app)
    private val engineRepo = EngineRepository(app)
    private val vpnSettingsRepo = VpnSettingsRepository(app)
    private val routingRepo = RoutingRepository(app)

    val themeMode: StateFlow<ThemeMode> = themeRepo.mode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.AMBIENT)

    /** ÐÑÐ±ÑÐ°Ð½Ð½ÑÐ¹ ÑÑÐ»ÑÐº ÑÐ°Ð±Ð¾ÑÐµÐ³Ð¾ ÑÑÐ¾Ð»Ð°: Ð¿Ð¾ ÑÐ¼Ð¾Ð»ÑÐ°Ð½Ð¸Ñ Ð±Ð°Ð·Ð¾Ð²ÑÐ¹, Ð»Ð¸Ð±Ð¾ Â«ÑÐ»ÐµÐ´Ð¾Ð²Ð°ÑÑ Ð·Ð° ÑÐµÐ¼Ð¾Ð¹Â», Ð»Ð¸Ð±Ð¾ Ð¾Ð´Ð¸Ð½ Ð¸Ð· Ð²Ð°ÑÐ¸Ð°Ð½ÑÐ¾Ð². */
    val launcherIcon: StateFlow<LauncherIconChoice> = themeRepo.launcherIcon
        .stateIn(viewModelScope, SharingStarted.Eagerly, LauncherIconChoice.AMBIENT)

    fun setThemeMode(mode: ThemeMode) = safeLaunch {
        themeRepo.setMode(mode)
        // No-op, ÐµÑÐ»Ð¸ ÑÑÐ»ÑÐº Ð½Ðµ ÑÐ»ÐµÐ´ÑÐµÑ Ð·Ð° ÑÐµÐ¼Ð¾Ð¹ Ð¸ alias ÑÐ¶Ðµ Ð½ÑÐ¶Ð½ÑÐ¹.
        LauncherIcon.apply(getApplication(), launcherIcon.value, mode)
    }

    fun setLauncherIcon(choice: LauncherIconChoice) = safeLaunch {
        themeRepo.setLauncherIcon(choice)
        LauncherIcon.apply(getApplication(), choice, themeMode.value)
    }

    // Ð¥Ð¾ÑÑÐ¿Ð¾Ñ-Ð¿ÑÐ¾ÐºÑÐ¸ (Ð¤Ð°Ð·Ð° 6f)
    private val hotspotRepo = ru.gidravpn.hydra.data.repository.HotspotRepository(app)
    val hotspot: StateFlow<ru.gidravpn.hydra.data.model.HotspotSettings> = hotspotRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, ru.gidravpn.hydra.data.model.HotspotSettings())
    fun setHotspotEnabled(enabled: Boolean) = safeLaunch { hotspotRepo.setEnabled(enabled) }
    fun setHotspotPort(port: Int) = safeLaunch { hotspotRepo.setPort(port) }
    fun setHotspotCredentials(user: String, password: String) =
        safeLaunch { hotspotRepo.setCredentials(user, password) }
    fun regenerateHotspotPassword() = safeLaunch { hotspotRepo.regeneratePassword() }

    /** Xray Core Ð²Ð¼ÐµÑÑÐ¾ sing-box Ð´Ð»Ñ VLESS/VMess/Trojan/SS (ÑÐ¼. NativeCoreFactory). */
    val preferXray: StateFlow<Boolean> = engineRepo.preferXray
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setPreferXray(enabled: Boolean) = safeLaunch { engineRepo.setPreferXray(enabled) }

    /** Ð¢ÑÐ¼Ð±Ð»ÐµÑÑ ÑÐ´ÐµÑ (0.6.22), Ð¿Ð¾ ÑÐ¼Ð¾Ð»ÑÐ°Ð½Ð¸Ñ Ð²ÑÐµ Ð²ÐºÐ»ÑÑÐµÐ½Ñ. */
    val engineToggles: StateFlow<ru.gidravpn.hydra.data.model.EngineToggles> = engineRepo.toggles
        .stateIn(viewModelScope, SharingStarted.Eagerly, ru.gidravpn.hydra.data.model.EngineToggles())

    fun setEngineEnabled(kind: ru.gidravpn.hydra.data.model.EngineToggles.Kind, enabled: Boolean) =
        safeLaunch { engineRepo.setEnabled(kind, enabled) }

    // ----- Ð¤Ð°Ð·Ð° 6b: Ð±ÐµÐ·Ð¾Ð¿Ð°ÑÐ½Ð¾ÑÑÑ ÑÐ¾ÐµÐ´Ð¸Ð½ÐµÐ½Ð¸Ñ -----

    val killSwitch: StateFlow<Boolean> = vpnSettingsRepo.killSwitch
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setKillSwitch(enabled: Boolean) = safeLaunch { vpnSettingsRepo.setKillSwitch(enabled) }

    val autoReconnect: StateFlow<Boolean> = vpnSettingsRepo.autoReconnect
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    fun setAutoReconnect(enabled: Boolean) = safeLaunch { vpnSettingsRepo.setAutoReconnect(enabled) }

    val autoConnectOnAppStart: StateFlow<Boolean> = vpnSettingsRepo.autoConnectOnAppStart
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setAutoConnectOnAppStart(enabled: Boolean) = safeLaunch { vpnSettingsRepo.setAutoConnectOnAppStart(enabled) }

    val autoConnectOnBoot: StateFlow<Boolean> = vpnSettingsRepo.autoConnectOnBoot
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setAutoConnectOnBoot(enabled: Boolean) = safeLaunch { vpnSettingsRepo.setAutoConnectOnBoot(enabled) }

    // ----- Ð¤Ð°Ð·Ð° 6c: DNS Ð¸ geoip-Ð¼Ð°ÑÑÑÑÑÐ¸Ð·Ð°ÑÐ¸Ñ -----

    val dnsProvider: StateFlow<DnsProvider> = routingRepo.dnsProvider
        .stateIn(viewModelScope, SharingStarted.Eagerly, DnsProvider.CLOUDFLARE)
    fun setDnsProvider(provider: DnsProvider) = safeLaunch { routingRepo.setDnsProvider(provider) }

    val dnsCustomAddress: StateFlow<String> = routingRepo.dnsCustomAddress
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    fun setDnsCustomAddress(address: String) = safeLaunch { routingRepo.setDnsCustomAddress(address) }

    val geoRoutingMode: StateFlow<GeoRoutingMode> = routingRepo.geoRoutingMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, GeoRoutingMode.OFF)
    fun setGeoRoutingMode(mode: GeoRoutingMode) = safeLaunch { routingRepo.setGeoRoutingMode(mode) }

    val geoCountries: StateFlow<Set<String>> = routingRepo.geoCountries
        .stateIn(viewModelScope, SharingStarted.Eagerly, setOf("ru"))
    fun toggleGeoCountry(code: String) = safeLaunch {
        val current = geoCountries.value
        routingRepo.setGeoCountries(if (code in current) current - code else current + code)
    }

    /** Ð¡ÑÑÐ°Ð½Ñ Ñ IP-Ð±Ð°Ð·Ð¾Ð¹ Ð¸ (Ð¿Ð¾Ð´Ð¼Ð½Ð¾Ð¶ÐµÑÑÐ²Ð¾) Ñ Ð´Ð¾Ð¼ÐµÐ½Ð½Ð¾Ð¹ â Ð´Ð»Ñ ÑÐºÑÐ°Ð½Ð° Ð²ÑÐ±Ð¾ÑÐ°. */
    val geoAvailable: List<String> by lazy { ru.gidravpn.hydra.data.subscription.GeoAssets.availableCountries(getApplication()) }
    val geoWithDomains: Set<String> by lazy { ru.gidravpn.hydra.data.subscription.GeoAssets.countriesWithDomains(getApplication()) }

    val mtu: StateFlow<ru.gidravpn.hydra.data.model.MtuPreset> = routingRepo.mtu
        .stateIn(viewModelScope, SharingStarted.Eagerly, ru.gidravpn.hydra.data.model.MtuPreset.AUTO)
    fun setMtu(preset: ru.gidravpn.hydra.data.model.MtuPreset) = safeLaunch { routingRepo.setMtu(preset) }

    val tlsFragment: StateFlow<ru.gidravpn.hydra.data.model.TlsFragmentMode> = routingRepo.tlsFragment
        .stateIn(viewModelScope, SharingStarted.Eagerly, ru.gidravpn.hydra.data.model.TlsFragmentMode.OFF)
    fun setTlsFragment(mode: ru.gidravpn.hydra.data.model.TlsFragmentMode) =
        safeLaunch { routingRepo.setTlsFragment(mode) }

    val servers: StateFlow<List<ServerProfile>> = repo.allServers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val subscriptions: StateFlow<List<Subscription>> = repo.allSubscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val splitTunnel: StateFlow<SplitTunnel> = splitRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SplitTunnel())

    private var splitReapplyJob: kotlinx.coroutines.Job? = null

    /**
     * ÐÐ°ÑÑÑÐ¾Ð¹ÐºÐ¸ split tunneling ÑÐ¸ÑÐ°ÑÑÑÑ Ð¿ÑÐ¸ Ð¿Ð¾Ð´ÑÑÐ¼Ðµ ÑÑÐ½Ð½ÐµÐ»Ñ. ÐÑÐ»Ð¸ VPN ÑÐ¶Ðµ Ð²ÐºÐ»ÑÑÑÐ½,
     * Ð±ÐµÐ· Ð¿ÐµÑÐµÐ·Ð°Ð¿ÑÑÐºÐ° Ð¸Ð·Ð¼ÐµÐ½ÐµÐ½Ð¸Ðµ Ð²ÑÐ³Ð»ÑÐ´ÐµÐ»Ð¾ ÐºÐ°Ðº Â«Ð½Ðµ ÑÐ°Ð±Ð¾ÑÐ°ÐµÑÂ» â Ð¿Ð¾ÑÑÐ¾Ð¼Ñ Ð¿Ð¾ÑÐ»Ðµ ÑÐµÑÐ¸Ð¸
     * Ð¿ÑÐ°Ð²Ð¾Ðº (Ð´ÐµÐ±Ð°ÑÐ½Ñ 1,2 Ñ) ÑÑÐ½Ð½ÐµÐ»Ñ Ð¿ÐµÑÐµÐ·Ð°Ð¿ÑÑÐºÐ°ÐµÑÑÑ Ð½Ð° ÑÐµÐºÑÑÐµÐ¼ ÑÐµÑÐ²ÐµÑÐµ.
     */
    private fun reapplySplitIfConnected() {
        splitReapplyJob?.cancel()
        splitReapplyJob = safeLaunch {
            kotlinx.coroutines.delay(1200)
            if (state.value != ConnectionState.CONNECTED) return@safeLaunch
            val server = selectedServer.value ?: return@safeLaunch
            VpnState.log("Split tunneling: Ð½Ð°ÑÑÑÐ¾Ð¹ÐºÐ¸ Ð¸Ð·Ð¼ÐµÐ½ÐµÐ½Ñ â Ð¿ÐµÑÐµÐ¿Ð¾Ð´ÐºÐ»ÑÑÐµÐ½Ð¸Ðµ")
            startTunnelWith(server)
        }
    }

    fun setSplitMode(mode: SplitTunnelMode) = safeLaunch {
        splitRepo.setMode(mode)
        VpnState.log("Split tunneling: ÑÐµÐ¶Ð¸Ð¼ ${mode.name}")
        reapplySplitIfConnected()
    }

    fun toggleSplitApp(pkg: String) = safeLaunch {
        splitRepo.toggleApp(pkg)
        reapplySplitIfConnected()
    }

    fun setNetMode(mode: SplitTunnelMode) = safeLaunch { splitRepo.setNetMode(mode); reapplySplitIfConnected() }
    fun addNetRule(rule: NetworkRule) = safeLaunch { splitRepo.addNetRule(rule); reapplySplitIfConnected() }
    fun removeNetRule(rule: NetworkRule) = safeLaunch { splitRepo.removeNetRule(rule); reapplySplitIfConnected() }

    val state = VpnState.state
    val logs = VpnState.logs
    val stats = VpnState.stats
    val activeServer = VpnState.activeServer
    val connectedSince = VpnState.connectedSince

    private val _selectedId = MutableStateFlow<Long?>(null)
    val selectedId = _selectedId.asStateFlow()

    init {
        // ÐÐ¾ÑÑÑÐ°Ð½Ð°Ð²Ð»Ð¸Ð²Ð°ÐµÐ¼ Ð²ÑÐ±Ð¾Ñ ÑÐµÑÐ²ÐµÑÐ° Ð¼ÐµÐ¶Ð´Ñ Ð·Ð°Ð¿ÑÑÐºÐ°Ð¼Ð¸ â Ð½ÑÐ¶Ð½Ð¾ Ð¸ UI (ÑÑÐ¾Ð±Ñ
        // "ÐÐ¾Ð´ÐºÐ»ÑÑÐ¸ÑÑ" Ð½Ð° Ð¿ÐµÑÐ²Ð¾Ð¼ ÑÐºÑÐ°Ð½Ðµ ÑÑÐ°Ð·Ñ Ð·Ð½Ð°Ð» ÑÐµÑÐ²ÐµÑ), Ð¸ autoConnectIfEnabled()
        // Ð½Ð¸Ð¶Ðµ; BootReceiver/HydraQsTileService ÑÐ¸ÑÐ°ÑÑ ÑÐ¾ Ð¶Ðµ Ð·Ð½Ð°ÑÐµÐ½Ð¸Ðµ Ð½Ð°Ð¿ÑÑÐ¼ÑÑ
        // Ð¸Ð· VpnSettingsRepository, Ð¼Ð¸Ð½ÑÑ ÑÑÑ viewmodel.
        safeLaunch {
            // ÐÐµ Ð¿ÐµÑÐµÑÐ¸ÑÐ°ÐµÐ¼ Ð²ÑÐ±Ð¾Ñ, ÐµÑÐ»Ð¸ Ð¿Ð¾Ð»ÑÐ·Ð¾Ð²Ð°ÑÐµÐ»Ñ ÑÑÐ¿ÐµÐ» ÑÐºÐ½ÑÑÑ ÑÐµÑÐ²ÐµÑ ÑÐ°Ð½ÑÑÐµ.
            vpnSettingsRepo.lastServerId.firstOrNull()?.let { _selectedId.compareAndSet(null, it) }
        }
    }

    val selectedServer: StateFlow<ServerProfile?> =
        combine(servers, _selectedId) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Ð¡Ð¾Ð±ÑÑÐ¸Ðµ: Ð½ÑÐ¶Ð½Ð¾ ÑÐ¸ÑÑÐµÐ¼Ð½Ð¾Ðµ ÑÐ¾Ð³Ð»Ð°ÑÐ¸Ðµ Ð½Ð° VPN. ÐÐºÑÐ¸Ð²Ð¸ÑÐ¸ Ð»Ð¾Ð²Ð¸Ñ Ð¸ Ð·Ð°Ð¿ÑÑÐºÐ°ÐµÑ consent.
    private val _requestPermission = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestPermission = _requestPermission.asSharedFlow()

    /**
     * ÐÑÐ±Ð¾Ñ ÑÐµÑÐ²ÐµÑÐ° Ð² ÑÐ¿Ð¸ÑÐºÐµ. ÐÑÐ»Ð¸ ÑÐ¾ÐµÐ´Ð¸Ð½ÐµÐ½Ð¸Ðµ ÑÐ¶Ðµ Ð°ÐºÑÐ¸Ð²Ð½Ð¾ (Ð¸Ð»Ð¸ ÑÑÑÐ°Ð½Ð°Ð²Ð»Ð¸Ð²Ð°ÐµÑÑÑ),
     * ÑÑÐ°Ð·Ñ Ð¿ÐµÑÐµÐºÐ»ÑÑÐ°ÐµÑ ÑÑÐ½Ð½ÐµÐ»Ñ Ð½Ð° Ð½Ð¾Ð²ÑÐ¹ ÑÐµÑÐ²ÐµÑ â VPN-consent ÑÐ¶Ðµ Ð²ÑÐ´Ð°Ð½, Ð¿Ð¾Ð²ÑÐ¾ÑÐ½ÑÐ¹
     * Ð·Ð°Ð¿ÑÐ¾Ñ Ð½Ðµ Ð½ÑÐ¶ÐµÐ½, Ð¿Ð¾ÑÑÐ¾Ð¼Ñ Ð¸Ð´ÑÐ¼ Ð½Ð°Ð¿ÑÑÐ¼ÑÑ ÑÐµÑÐµÐ· startTunnelWith(), Ð¼Ð¸Ð½ÑÑ toggle().
     */
    fun select(id: Long) {
        _selectedId.value = id
        safeLaunch { vpnSettingsRepo.setLastServerId(id) }
        if (state.value == ConnectionState.CONNECTED || state.value == ConnectionState.CONNECTING || state.value == ConnectionState.RECONNECTING) {
            servers.value.firstOrNull { it.id == id }?.let { startTunnelWith(it) }
        }
    }

    /**
     * ÐÐ²ÑÐ¾Ð¿Ð¾Ð´ÐºÐ»ÑÑÐµÐ½Ð¸Ðµ Ð¿ÑÐ¸ ÑÐ¾Ð»Ð¾Ð´Ð½Ð¾Ð¼ ÑÑÐ°ÑÑÐµ Ð¿ÑÐ¸Ð»Ð¾Ð¶ÐµÐ½Ð¸Ñ â Ð²ÑÐ·ÑÐ²Ð°ÐµÑÑÑ Ð¸Ð·
     * MainActivity.onCreate() ÑÐ¾Ð²Ð½Ð¾ Ð¾Ð´Ð¸Ð½ ÑÐ°Ð· (savedInstanceState == null).
     * Ð Ð°Ð±Ð¾ÑÐ°ÐµÑ ÑÐ¾Ð»ÑÐºÐ¾ ÐµÑÐ»Ð¸ VPN-ÑÐ¾Ð³Ð»Ð°ÑÐ¸Ðµ ÑÐ¶Ðµ Ð²ÑÐ´Ð°Ð²Ð°Ð»Ð¾ÑÑ ÑÐ°Ð½ÑÑÐµ (VpnService.prepare
     * Ð²ÐµÑÐ½ÑÑ null): Ð±ÐµÐ· ÑÑÐ¾Ð³Ð¾ Ð¿ÑÐ¸ÑÐ»Ð¾ÑÑ Ð±Ñ Ð´ÑÑÐ³Ð°ÑÑ ÑÐ¸ÑÑÐµÐ¼Ð½ÑÐ¹ consent-Ð´Ð¸Ð°Ð»Ð¾Ð³ ÑÑÐ°Ð·Ñ
     * Ð¿ÑÐ¸ Ð¾ÑÐºÑÑÑÐ¸Ð¸ Ð¿ÑÐ¸Ð»Ð¾Ð¶ÐµÐ½Ð¸Ñ, ÑÑÐ¾ Ð½Ðµ Ð¾ÑÐ»Ð¸ÑÐ¸ÑÑ Ð¾Ñ Ð¾Ð±ÑÑÐ½Ð¾Ð³Ð¾ Ð·Ð°Ð¿ÑÑÐºÐ° Ð¿Ð¾Ð»ÑÐ·Ð¾Ð²Ð°ÑÐµÐ»ÐµÐ¼.
     */
    fun autoConnectIfEnabled() = safeLaunch {
        if (vpnSettingsRepo.autoConnectOnAppStart.firstOrNull() != true) return@safeLaunch
        if (state.value != ConnectionState.DISCONNECTED) return@safeLaunch
        val id = vpnSettingsRepo.lastServerId.firstOrNull() ?: return@safeLaunch
        val server = repo.byId(id) ?: return@safeLaunch
        _selectedId.value = id
        val ctx = getApplication<Application>()
        if (VpnService.prepare(ctx) == null) startTunnelWith(server)
    }

    fun toggle() = safeLaunch {
        if (state.value == ConnectionState.CONNECTED || state.value == ConnectionState.CONNECTING || state.value == ConnectionState.RECONNECTING) {
            disconnect()
        } else {
            _requestPermission.tryEmit(Unit)   // Ð°ÐºÑÐ¸Ð²Ð¸ÑÐ¸ Ð¿ÑÐ¾Ð²ÐµÑÐ¸Ñ prepare() Ð¸ Ð²ÑÐ·Ð¾Ð²ÐµÑ startTunnel()
        }
    }

    fun startTunnel() {
        val server = selectedServer.value ?: run {
            VpnState.log("ÐÑÐ¸Ð±ÐºÐ°: ÑÐµÑÐ²ÐµÑ Ð½Ðµ Ð²ÑÐ±ÑÐ°Ð½"); return
        }
        startTunnelWith(server)
    }

    private fun startTunnelWith(server: ServerProfile) {
        val ctx = getApplication<Application>()

        // PPTP ÑÐµÑÑÐ½Ð¾ Ð½ÐµÐ´Ð¾ÑÑÑÐ¿ÐµÐ½: GRE ÑÑÐµÐ±ÑÐµÑ root, ÑÑÐµÐº ÑÐ´Ð°Ð»ÑÐ½ Ð¸Ð· Android 12/13.
        if (server.protocol?.engine == Engine.UNAVAILABLE) {
            VpnState.log("${server.protocol?.displayName}: Ð¿ÑÐ¾ÑÐ¾ÐºÐ¾Ð» Ð½ÐµÐ´Ð¾ÑÑÑÐ¿ÐµÐ½ Ð½Ð° Android â Ð¸ÑÐ¿Ð¾Ð»ÑÐ·ÑÐ¹ÑÐµ SSTP/L2TP/WireGuard")
            return
        }

        val intent = Intent(ctx, HydraVpnService::class.java).apply {
            action = HydraVpnService.ACTION_CONNECT
            putExtra(HydraVpnService.EXTRA_SERVER_ID, server.id)
        }
        // Как в BootReceiver и в плитке: если активити уже уходит в фон
        // (переключение сервера, автоподключение), Android 12+ отвечает
        // ForegroundServiceStartNotAllowedException. Голый вызов ронял UI.
        runCatching { ctx.startForegroundService(intent) }
            .onFailure {
                VpnState.log("Ошибка: система не дала стартовать подключение — ${it.javaClass.simpleName}")
            }
    }

    fun disconnect() {
        val ctx = getApplication<Application>()
        runCatching {
            ctx.startService(Intent(ctx, HydraVpnService::class.java)
                .apply { action = HydraVpnService.ACTION_DISCONNECT })
        }.onFailure {
            VpnState.log("Ошибка: отключение не отправлено сервису — ${it.javaClass.simpleName}")
        }
    }

    fun addServer(name: String, address: String, port: Int, protocol: Protocol) =
        safeLaunch {
            repo.save(ServerProfile(
                name = name, address = address, port = port, protocolId = protocol.id
            ))
            VpnState.log("Ð¡ÐµÑÐ²ÐµÑ \"$name\" Ð´Ð¾Ð±Ð°Ð²Ð»ÐµÐ½")
        }

    // ----- 0.6.18: ÑÐ¼Ð½ÑÐ¹ Ð¸Ð¼Ð¿Ð¾ÑÑ (ÑÑÑÐ»ÐºÐ° / Ð¿Ð¾Ð´Ð¿Ð¸ÑÐºÐ° / .conf / QR / ÑÐ¾ÑÐ¾) -----

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage = _importMessage.asStateFlow()
    fun dismissImportMessage() { _importMessage.value = null }

    /**
     * ÐÑÐ¸Ð½Ð¸Ð¼Ð°ÐµÑ Ð»ÑÐ±Ð¾Ð¹ ÑÐµÐºÑÑ â ÐºÐ»Ð¸ÐµÐ½Ñ ÑÐ°Ð¼ ÑÐµÑÐ°ÐµÑ, ÑÐµÑÐ²ÐµÑÑ ÑÑÐ¾ Ð¸Ð»Ð¸ Ð¿Ð¾Ð´Ð¿Ð¸ÑÐºÐ°. ÐÐµÑÐºÐ¾Ð»ÑÐºÐ¾
     * ÑÑÐ°Ð³Ð¼ÐµÐ½ÑÐ¾Ð² (Ð½ÐµÑÐºÐ¾Ð»ÑÐºÐ¾ QR Ð½Ð° ÑÐ½Ð¸Ð¼ÐºÐµ) Ð¾Ð±ÑÐ°Ð±Ð°ÑÑÐ²Ð°ÑÑÑÑ Ð¿Ð¾ Ð¾ÑÐµÑÐµÐ´Ð¸, Ð¸ÑÐ¾Ð³ â Ð¾Ð´Ð½Ð¸Ð¼ ÑÐ¾Ð¾Ð±ÑÐµÐ½Ð¸ÐµÐ¼.
     * [subName] â Ð½ÐµÐ¾Ð±ÑÐ·Ð°ÑÐµÐ»ÑÐ½Ð¾Ðµ Ð¸Ð¼Ñ Ð¿Ð¾Ð´Ð¿Ð¸ÑÐºÐ¸ Ð¾Ñ Ð¿Ð¾Ð»ÑÐ·Ð¾Ð²Ð°ÑÐµÐ»Ñ (Ð¸Ð½Ð°ÑÐµ Ð±ÐµÑÑÑÑÑ Ð¸Ð· ÑÑÑÐ»ÐºÐ¸).
     */
    fun importAuto(texts: List<String>, subName: String? = null) = safeLaunch {
        val app = getApplication<Application>()
        fun s(id: Int, vararg a: Any) = app.getString(id, *a)
        if (texts.isEmpty()) { _importMessage.value = s(ru.gidravpn.hydra.R.string.import_no_qr); return@safeLaunch }
        val lines = mutableListOf<String>()
        for (text in texts) {
            when (val r = ru.gidravpn.hydra.data.subscription.ImportDetector.classify(text)) {
                is ru.gidravpn.hydra.data.subscription.ImportDetector.Result.SubscriptionUrl -> {
                    val name = subName?.takeIf { it.isNotBlank() } ?: r.nameHint
                    runCatching { repo.addSubscription(name, r.url) }.fold(
                        onSuccess = {
                            VpnState.log("ÐÐ¾Ð´Ð¿Ð¸ÑÐºÐ° \"$name\": Ð¸Ð¼Ð¿Ð¾ÑÑÐ¸ÑÐ¾Ð²Ð°Ð½Ð¾ $it ÑÐµÑÐ²ÐµÑÐ¾Ð²")
                            lines += s(ru.gidravpn.hydra.R.string.import_done_sub, name, it)
                        },
                        onFailure = {
                            val why = it.message ?: it.javaClass.simpleName
                            VpnState.log("ÐÑÐ¸Ð±ÐºÐ°: Ð¿Ð¾Ð´Ð¿Ð¸ÑÐºÐ° \"$name\" Ð½Ðµ Ð·Ð°Ð³ÑÑÐ¶ÐµÐ½Ð° â $why")
                            lines += s(ru.gidravpn.hydra.R.string.import_fail_sub, name, why)
                        },
                    )
                }
                is ru.gidravpn.hydra.data.subscription.ImportDetector.Result.Servers -> {
                    val n = runCatching { repo.importProfiles(r.profiles) }.getOrDefault(0)
                    VpnState.log("ÐÐ¼Ð¿Ð¾ÑÑÐ¸ÑÐ¾Ð²Ð°Ð½Ð¾ ÑÐµÑÐ²ÐµÑÐ¾Ð²: $n")
                    lines += if (n == 1) s(ru.gidravpn.hydra.R.string.import_done_one, r.profiles.first().name)
                    else s(ru.gidravpn.hydra.R.string.import_done_servers, n)
                }
                is ru.gidravpn.hydra.data.subscription.ImportDetector.Result.Unsupported ->
                    lines += s(if (r.reason == ru.gidravpn.hydra.data.subscription.ImportDetector.Reason.JSON_CONFIG)
                        ru.gidravpn.hydra.R.string.import_json_unsupported else ru.gidravpn.hydra.R.string.import_unknown)
                ru.gidravpn.hydra.data.subscription.ImportDetector.Result.Empty -> Unit
            }
        }
        _importMessage.value = lines.distinct().joinToString("\n").ifBlank { s(ru.gidravpn.hydra.R.string.import_unknown) }
    }

    /** Ð¤Ð¾ÑÐ¾/ÑÐºÑÐ¸Ð½ÑÐ¾Ñ Ð¸Ð· Ð³Ð°Ð»ÐµÑÐµÐ¸: Ð´Ð¾ÑÑÐ°ÑÐ¼ Ð²ÑÐµ QR Ð¸ Ð¾ÑÐ´Ð°ÑÐ¼ ÑÐ¼Ð½Ð¾Ð¼Ñ Ð¸Ð¼Ð¿Ð¾ÑÑÑ. */
    fun importFromImage(uri: android.net.Uri) = safeLaunch {
        val codes = ru.gidravpn.hydra.data.subscription.QrImageDecoder.decode(getApplication(), uri)
        importAuto(codes).join()
    }

    fun importLink(link: String) = safeLaunch {
        val p = runCatching { repo.importLink(link) }.getOrNull()
        VpnState.log(if (p != null) "ÐÐ¼Ð¿Ð¾ÑÑÐ¸ÑÐ¾Ð²Ð°Ð½: ${p.name}" else "ÐÐµ ÑÐ´Ð°Ð»Ð¾ÑÑ ÑÐ°Ð·Ð¾Ð±ÑÐ°ÑÑ ÑÑÑÐ»ÐºÑ")
    }

    fun addSubscription(name: String, url: String) = safeLaunch {
        // ÐÑÐ¸ÑÐ¸Ð½Ð° Ð¾ÑÐºÐ°Ð·Ð° ÑÐµÐ¿ÐµÑÑ Ð²Ð¸Ð´Ð½Ð°: Ð´Ð¾ 7e Ð»ÑÐ±Ð°Ñ Ð¾ÑÐ¸Ð±ÐºÐ° (Ð½ÐµÑ ÑÐµÑÐ¸, 404,
        // Ð¿ÑÑÑÐ¾Ð¹ Ð¾ÑÐ²ÐµÑ) Ð¿ÑÐµÐ²ÑÐ°ÑÐ°Ð»Ð°ÑÑ Ð² Â«Ð¸Ð¼Ð¿Ð¾ÑÑÐ¸ÑÐ¾Ð²Ð°Ð½Ð¾ 0 ÑÐµÑÐ²ÐµÑÐ¾Ð²Â».
        runCatching { repo.addSubscription(name, url) }.fold(
            onSuccess = { VpnState.log("ÐÐ¾Ð´Ð¿Ð¸ÑÐºÐ° \"$name\": Ð¸Ð¼Ð¿Ð¾ÑÑÐ¸ÑÐ¾Ð²Ð°Ð½Ð¾ $it ÑÐµÑÐ²ÐµÑÐ¾Ð²") },
            onFailure = { VpnState.log("ÐÑÐ¸Ð±ÐºÐ°: Ð¿Ð¾Ð´Ð¿Ð¸ÑÐºÐ° \"$name\" Ð½Ðµ Ð·Ð°Ð³ÑÑÐ¶ÐµÐ½Ð° â ${it.message ?: it.javaClass.simpleName}") },
        )
    }

    fun delete(server: ServerProfile) = safeLaunch { repo.delete(server) }

    // ----- 0.6.21: Ð¿Ð¾Ð´Ð¿Ð¸ÑÐºÐ¸ â Ð¾Ð±Ð½Ð¾Ð²Ð»ÐµÐ½Ð¸Ðµ, Ð¿Ð¸Ð½Ð³, Ð°Ð²ÑÐ¾Ð¾Ð±Ð½Ð¾Ð²Ð»ÐµÐ½Ð¸Ðµ, ÑÐ²ÑÑÑÐºÐ°, ÑÐ´Ð°Ð»ÐµÐ½Ð¸Ðµ -----

    private val _refreshingSubs = MutableStateFlow<Set<Long>>(emptySet())
    val refreshingSubs: StateFlow<Set<Long>> = _refreshingSubs.asStateFlow()

    /** ÐÐ¿Ð¿Ð°ÑÐ°ÑÐ½ÑÐ¹ ID, ÐºÐ¾ÑÐ¾ÑÑÐ¹ ÐºÐ»Ð¸ÐµÐ½Ñ Ð¾ÑÐ´Ð°ÑÑ Ð¿Ð°Ð½ÐµÐ»ÑÐ¼ (`x-hwid`) â Ð¿Ð¾ÐºÐ°Ð·ÑÐ²Ð°ÐµÑÑÑ Ð² ÐÑÐ¾ÑÐ¸Ð»Ðµ. */
    val hwid: String by lazy { ru.gidravpn.hydra.data.repository.HydraDevice.hwid(getApplication()) }

    fun refreshSubscription(sub: Subscription) = safeLaunch {
        if (sub.id in _refreshingSubs.value) return@safeLaunch
        _refreshingSubs.update { it + sub.id }
        val app = getApplication<Application>()
        try {
            runCatching { repo.refreshDetailed(sub.id) }.fold(
                onSuccess = {
                    VpnState.log("ÐÐ¾Ð´Ð¿Ð¸ÑÐºÐ° Â«${sub.displayName}Â» Ð¾Ð±Ð½Ð¾Ð²Ð»ÐµÐ½Ð°: ${it.total} ÑÐµÑÐ². (+${it.added} / â${it.removed})")
                    _importMessage.value = app.getString(ru.gidravpn.hydra.R.string.sub_refreshed, sub.displayName, it.total, it.added, it.removed)
                },
                onFailure = {
                    val why = it.message ?: it.javaClass.simpleName
                    VpnState.log("ÐÑÐ¸Ð±ÐºÐ°: Ð¿Ð¾Ð´Ð¿Ð¸ÑÐºÐ° Â«${sub.displayName}Â» Ð½Ðµ Ð¾Ð±Ð½Ð¾Ð²Ð»ÐµÐ½Ð° â $why")
                    _importMessage.value = app.getString(ru.gidravpn.hydra.R.string.import_fail_sub, sub.displayName, why)
                },
            )
        } finally {
            _refreshingSubs.update { it - sub.id }
        }
    }

    fun refreshAllSubscriptions() = subscriptions.value.forEach { refreshSubscription(it) }

    fun pingSubscription(sub: Subscription) = servers.value.filter { it.subscriptionId == sub.id }.forEach { measurePing(it) }

    fun setSubscriptionAutoUpdate(sub: Subscription, enabled: Boolean) =
        safeLaunch { repo.updateSubscription(sub.copy(autoUpdate = enabled)) }

    fun toggleSubscriptionCollapsed(sub: Subscription) =
        safeLaunch { repo.updateSubscription(sub.copy(collapsed = !sub.collapsed)) }

    fun deleteSubscription(sub: Subscription) = safeLaunch {
        repo.deleteSubscription(sub)
        VpnState.log("ÐÐ¾Ð´Ð¿Ð¸ÑÐºÐ° Â«${sub.displayName}Â» ÑÐ´Ð°Ð»ÐµÐ½Ð° Ð²Ð¼ÐµÑÑÐµ Ñ ÑÐµÑÐ²ÐµÑÐ°Ð¼Ð¸")
    }
    fun clearLogs() = VpnState.clearLogs()

    // ----- Ð¤Ð°Ð·Ð° 6d: Ð»Ð¾Ð³Ð¸ Ð½Ð° ÑÑÑÑÐ¾Ð¹ÑÑÐ²Ðµ -----

    private val logSettingsRepo = ru.gidravpn.hydra.data.repository.LogSettingsRepository(app)
    val logPersistMode: StateFlow<ru.gidravpn.hydra.data.log.LogPersistMode> = logSettingsRepo.mode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ru.gidravpn.hydra.data.log.LogPersistMode.ERRORS)
    fun setLogPersistMode(m: ru.gidravpn.hydra.data.log.LogPersistMode) = safeLaunch { logSettingsRepo.setMode(m) }

    val logRetention: StateFlow<ru.gidravpn.hydra.data.log.LogRetention> = logSettingsRepo.retention
        .stateIn(viewModelScope, SharingStarted.Eagerly, ru.gidravpn.hydra.data.log.LogRetention.D3)
    fun setLogRetention(r: ru.gidravpn.hydra.data.log.LogRetention) = safeLaunch { logSettingsRepo.setRetention(r) }

    private val _storedLogBytes = MutableStateFlow(0L)
    val storedLogBytes = _storedLogBytes.asStateFlow()
    fun refreshStoredLogSize() = safeLaunch(kotlinx.coroutines.Dispatchers.IO) {
        _storedLogBytes.value = ru.gidravpn.hydra.data.log.LogStore.sizeBytes()
    }

    fun clearStoredLogs() {
        ru.gidravpn.hydra.data.log.LogStore.clear()
        _storedLogBytes.value = 0
    }

    /** Ð¡Ð¾ÑÑÐ°Ð½ÑÐ½Ð½ÑÐµ Ð´Ð½Ð¸ + ÑÐµÐºÑÑÐ°Ñ ÑÐµÑÑÐ¸Ñ Ð¸Ð· Ð¿Ð°Ð¼ÑÑÐ¸ (Ð² ÑÐµÐ¶Ð¸Ð¼Ðµ Â«ÑÐ¾Ð»ÑÐºÐ¾ Ð¾ÑÐ¸Ð±ÐºÐ¸Â» Ð½Ð° Ð´Ð¸ÑÐºÐµ ÐµÑ Ð½ÐµÑ). */
    fun exportLogs(uri: android.net.Uri) = safeLaunch(kotlinx.coroutines.Dispatchers.IO) {
        val app = getApplication<Application>()
        val text = ru.gidravpn.hydra.data.log.LogStore.readAll() +
            "===== ÑÐµÐºÑÑÐ°Ñ ÑÐµÑÑÐ¸Ñ (Hydra ${ru.gidravpn.hydra.BuildConfig.VERSION_NAME}) =====\n" +
            logs.value.joinToString("\n") + "\n"
        val result = runCatching {
            app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
        }
        VpnState.log(result.fold({ "ÐÐ¾Ð³Ð¸ ÑÐ¾ÑÑÐ°Ð½ÐµÐ½Ñ Ð² ÑÐ°Ð¹Ð»" }, { "ÐÑÐ¸Ð±ÐºÐ°: Ð»Ð¾Ð³Ð¸ Ð½Ðµ ÑÐ¾ÑÑÐ°Ð½ÐµÐ½Ñ â ${it.message}" }))
    }

    // ----- Ð¤Ð°Ð·Ð° 6d: ÑÐµÐ·ÐµÑÐ²Ð½Ð°Ñ ÐºÐ¾Ð¿Ð¸Ñ Ð¸ ÑÐ±ÑÐ¾Ñ -----

    private val _backupMessage = MutableStateFlow<String?>(null)
    /** Ð ÐµÐ·ÑÐ»ÑÑÐ°Ñ Ð¿Ð¾ÑÐ»ÐµÐ´Ð½ÐµÐ¹ Ð¾Ð¿ÐµÑÐ°ÑÐ¸Ð¸ Ñ ÐºÐ¾Ð¿Ð¸ÐµÐ¹ â Ð¿Ð¾ÐºÐ°Ð·ÑÐ²Ð°ÐµÑÑÑ Ð½Ð° ÑÐºÑÐ°Ð½Ðµ, Ð¿Ð¾ÐºÐ° Ð½Ðµ ÑÐ±ÑÐ¾ÑÐµÐ½. */
    val backupMessage = _backupMessage.asStateFlow()
    fun dismissBackupMessage() { _backupMessage.value = null }

    fun exportBackup(uri: android.net.Uri) = safeLaunch {
        val app = getApplication<Application>()
        _backupMessage.value = runCatching {
            val text = ru.gidravpn.hydra.data.backup.BackupManager.export(app)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
            }
            app.getString(ru.gidravpn.hydra.R.string.msg_backup_saved, servers.value.size)
        }.getOrElse { app.getString(ru.gidravpn.hydra.R.string.msg_backup_save_failed, it.message ?: it.javaClass.simpleName) }
    }

    fun importBackup(uri: android.net.Uri) = safeLaunch {
        if (state.value != ConnectionState.DISCONNECTED) {
            _backupMessage.value = getApplication<Application>().getString(ru.gidravpn.hydra.R.string.msg_backup_disconnect_first)
            return@safeLaunch
        }
        val app = getApplication<Application>()
        _backupMessage.value = runCatching {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            }
            val s = ru.gidravpn.hydra.data.backup.BackupManager.import(app, text)
            _selectedId.value = vpnSettingsRepo.lastServerId.firstOrNull()
            LauncherIcon.apply(getApplication(), themeRepo.launcherIcon.first(), themeRepo.mode.first())
            VpnState.log("ÐÐ¾ÑÑÑÐ°Ð½Ð¾Ð²Ð»ÐµÐ½Ð¾ Ð¸Ð· ÐºÐ¾Ð¿Ð¸Ð¸: ${s.servers} ÑÐµÑÐ²., ${s.subscriptions} Ð¿Ð¾Ð´Ð¿., ${s.settings} Ð½Ð°ÑÑÑÐ¾ÐµÐº")
            app.getString(ru.gidravpn.hydra.R.string.msg_backup_restored, s.servers, s.subscriptions, s.settings)
        }.getOrElse { app.getString(ru.gidravpn.hydra.R.string.msg_backup_restore_failed, it.message ?: it.javaClass.simpleName) }
    }

    fun resetSettings() = safeLaunch {
        ru.gidravpn.hydra.data.backup.BackupManager.resetSettings(getApplication())
        LauncherIcon.apply(getApplication(), LauncherIconChoice.AMBIENT, ThemeMode.AMBIENT)
        VpnState.log("ÐÐ°ÑÑÑÐ¾Ð¹ÐºÐ¸ ÑÐ±ÑÐ¾ÑÐµÐ½Ñ Ðº Ð·Ð½Ð°ÑÐµÐ½Ð¸ÑÐ¼ Ð¿Ð¾ ÑÐ¼Ð¾Ð»ÑÐ°Ð½Ð¸Ñ")
        _backupMessage.value = getApplication<Application>().getString(ru.gidravpn.hydra.R.string.msg_reset_done)
    }

    private val _measuringIds = MutableStateFlow<Set<Long>>(emptySet())
    val measuringIds: StateFlow<Set<Long>> = _measuringIds.asStateFlow()

    /**
     * ÐÐµ Ð±Ð¾Ð»ÑÑÐµ Ð²Ð¾ÑÑÐ¼Ð¸ Ð¾Ð´Ð½Ð¾Ð²ÑÐµÐ¼ÐµÐ½Ð½ÑÑ Ð·Ð°Ð¼ÐµÑÐ¾Ð² (Ð¤Ð°Ð·Ð° 7e). Â«ÐÐ±Ð½Ð¾Ð²Ð¸ÑÑ Ð¿Ð¸Ð½Ð³Â» Ð½Ð°
     * Ð¿Ð¾Ð´Ð¿Ð¸ÑÐºÐµ Ð² ÑÐ¾ÑÐ½Ð¸ ÑÐµÑÐ²ÐµÑÐ¾Ð² ÑÐ°Ð½ÑÑÐµ Ð·Ð°Ð¿ÑÑÐºÐ°Ð» Ð¿Ð¾ ÐºÐ¾ÑÑÑÐ¸Ð½Ðµ Ð½Ð° ÐºÐ°Ð¶Ð´ÑÐ¹ ÑÐµÑÐ²ÐµÑ:
     * ÑÐ¾ÑÐ½Ð¸ Ð¾Ð´Ð½Ð¾Ð²ÑÐµÐ¼ÐµÐ½Ð½ÑÑ TCP-connect, ÑÑÐ¾Ð»ÑÐºÐ¾ Ð¶Ðµ Ð·Ð°Ð¿Ð¸ÑÐµÐ¹ Ð² Room Ð¸ Ð·Ð°Ð±Ð¸ÑÑÐ¹
     * Dispatchers.IO â Ð½Ð° ÑÐ»Ð°Ð±Ð¾Ð¼ ÑÑÑÑÐ¾Ð¹ÑÑÐ²Ðµ ÑÑÐ¾ Ð·Ð°Ð¼ÐµÑÐ½Ð¾Ðµ Ð¿Ð¾Ð´Ð²Ð¸ÑÐ°Ð½Ð¸Ðµ.
     */
    private val pingLimit = kotlinx.coroutines.sync.Semaphore(8)

    fun measurePing(server: ServerProfile) = safeLaunch {
        _measuringIds.update { it + server.id }
        try {
            val ms = pingLimit.withPermit { PingMeasurer.measure(server.address, server.port) }
            repo.save(server.copy(pingMs = ms))
        } finally {
            _measuringIds.update { it - server.id }
        }
    }

    fun measureAllPings() {
        servers.value.forEach { measurePing(it) }
    }
}
