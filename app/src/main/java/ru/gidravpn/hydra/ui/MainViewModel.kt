package ru.gidravpn.hydra.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ru.gidravpn.hydra.data.model.Engine
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.SplitTunnel
import ru.gidravpn.hydra.data.model.SplitTunnelMode
import ru.gidravpn.hydra.data.model.Subscription
import ru.gidravpn.hydra.data.repository.ServerRepository
import ru.gidravpn.hydra.data.repository.SplitTunnelRepository
import ru.gidravpn.hydra.vpn.HydraVpnService
import ru.gidravpn.hydra.vpn.VpnState
import ru.gidravpn.hydra.vpn.core.ConnectionState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ServerRepository(app)
    private val splitRepo = SplitTunnelRepository(app)

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

    val state = VpnState.state
    val logs = VpnState.logs
    val stats = VpnState.stats
    val activeServer = VpnState.activeServer
    val connectedSince = VpnState.connectedSince

    private val _selectedId = MutableStateFlow<Long?>(null)
    val selectedId = _selectedId.asStateFlow()

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
        if (state.value == ConnectionState.CONNECTED || state.value == ConnectionState.CONNECTING) {
            servers.value.firstOrNull { it.id == id }?.let { startTunnelWith(it) }
        }
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
}
