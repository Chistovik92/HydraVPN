package com.shadowlink.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.os.Build
import com.shadowlink.data.model.Engine
import com.shadowlink.data.model.Protocol
import com.shadowlink.data.model.ServerProfile
import com.shadowlink.data.model.Subscription
import com.shadowlink.data.repository.ServerRepository
import com.shadowlink.vpn.Ikev2Connector
import com.shadowlink.vpn.ShadowLinkVpnService
import com.shadowlink.vpn.VpnState
import com.shadowlink.vpn.core.ConnectionState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ServerRepository(app)

    val servers: StateFlow<List<ServerProfile>> = repo.allServers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val subscriptions: StateFlow<List<Subscription>> = repo.allSubscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    fun select(id: Long) { _selectedId.value = id }

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
        val ctx = getApplication<Application>()

        // Семейство L2TP/IPsec обслуживается системным IKEv2 (VpnManager), а не proxy-туннелем.
        if (server.protocol?.engine == Engine.IKEV2) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                VpnState.log("IKEv2 требует Android 13+ (API 33)"); return
            }
            runCatching { Ikev2Connector(ctx).provisionAndStart(server) }
                .onFailure { VpnState.log("IKEv2: ${it.message}") }
            return
        }

        val intent = Intent(ctx, ShadowLinkVpnService::class.java).apply {
            action = ShadowLinkVpnService.ACTION_CONNECT
            putExtra(ShadowLinkVpnService.EXTRA_SERVER_ID, server.id)
        }
        ctx.startForegroundService(intent)
    }

    fun disconnect() {
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, ShadowLinkVpnService::class.java)
            .apply { action = ShadowLinkVpnService.ACTION_DISCONNECT })
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
