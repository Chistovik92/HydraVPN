package ru.gidravpn.hydra.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ru.gidravpn.hydra.data.repository.RouterRepository
import ru.gidravpn.hydra.router.RouterLink
import ru.gidravpn.hydra.router.RouterManager

/**
 * Управление роутерами HydraVPN for Router (вкладка «Роутеры»). Вся логика опроса и команд —
 * в общем [RouterManager] (:shared), тот же, что у ПК-версии; здесь только хранилище и сообщения.
 */
class RoutersViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = RouterRepository(app)

    private val _links = MutableStateFlow(runBlocking(Dispatchers.IO) { repo.load() })
    val links: StateFlow<List<RouterLink>> = _links

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun messageShown() { _message.value = null }

    val manager = RouterManager(
        viewModelScope,
        routers = { _links.value },
        // Список обновляется сразу (менеджер читает его следом), на диск — в фоне.
        saveRouters = { list ->
            _links.value = list
            viewModelScope.launch(Dispatchers.IO) { runCatching { repo.save(list) } }
        },
        toast = { _message.value = it },
    )
}
