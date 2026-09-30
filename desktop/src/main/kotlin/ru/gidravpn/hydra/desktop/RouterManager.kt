package ru.gidravpn.hydra.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.gidravpn.hydra.router.RouterClient
import ru.gidravpn.hydra.router.RouterException
import ru.gidravpn.hydra.router.RouterLink
import ru.gidravpn.hydra.router.RouterLogEntry
import ru.gidravpn.hydra.router.RouterNode
import ru.gidravpn.hydra.router.RouterSection
import ru.gidravpn.hydra.router.RouterSubscription

/** Что известно о выбранном роутере после последнего опроса. */
data class RouterUi(
    val selected: String? = null,        // baseUrl выбранного роутера
    val busy: Boolean = false,
    val online: Boolean = false,
    val error: String? = null,
    val version: String = "",
    val state: String = "",
    val uptime: String = "",
    val lastError: String = "",
    val sections: List<RouterSection> = emptyList(),
    val subscriptions: List<RouterSubscription> = emptyList(),
    val nodes: List<RouterNode> = emptyList(),
    val nodesError: String? = null,
    val logs: List<RouterLogEntry> = emptyList(),
    val delays: Map<String, Int> = emptyMap(),
)

/**
 * Управление роутерами HydraVPN for Router через их API (`RouterClient`): сопряжение
 * по ссылке, статус, секции, узлы, подписки, журнал, перезапуск. Все сетевые вызовы — в
 * IO и строго по очереди (роутер сам сериализует запись конфига, но так проще UI).
 */
class RouterManager(
    private val scope: CoroutineScope,
    private val routers: () -> List<RouterLink>,
    private val saveRouters: (List<RouterLink>) -> Unit,
    private val toast: (String) -> Unit,
) {
    private val _ui = MutableStateFlow(RouterUi(selected = routers().firstOrNull()?.baseUrl))
    val ui: StateFlow<RouterUi> = _ui
    private val lock = Mutex()

    val current: RouterLink? get() = routers().firstOrNull { it.baseUrl == _ui.value.selected }

    /** Ссылка `hydravpn-router://…` или адрес + отдельно токен. */
    fun add(link: String, token: String, name: String): Boolean {
        val l = RouterLink.parse(link, token.ifBlank { null })
            ?: return false.also { toast("Не похоже на ссылку роутера. Возьмите её в веб-интерфейсе роутера или командой `hydravpn-router pair`.") }
        val named = l.copy(name = name.trim().take(40).ifEmpty { l.host })
        saveRouters(routers().filterNot { it.baseUrl == named.baseUrl } + named)
        _ui.update { RouterUi(selected = named.baseUrl) }
        if (named.insecure) toast("Роутер без TLS: токен передаётся по сети открыто. Включите api_tls_cert/api_tls_key на роутере и сопрягитесь заново.")
        refresh()
        return true
    }

    fun remove(baseUrl: String) {
        val rest = routers().filterNot { it.baseUrl == baseUrl }
        saveRouters(rest)
        _ui.update { RouterUi(selected = rest.firstOrNull()?.baseUrl) }
    }

    fun select(baseUrl: String) {
        if (_ui.value.selected == baseUrl) return
        _ui.update { RouterUi(selected = baseUrl) }
        refresh()
    }

    /** Полный опрос: версия, статус, секции, подписки, узлы, журнал. */
    fun refresh() = call(quiet = true) { c ->
        val (ver, _) = c.version()
        val st = c.status()
        val sections = c.sections()
        val subs = c.subscriptions()
        val nodes = runCatching { c.nodes() }
        val logs = runCatching { c.logs(150) }.getOrDefault(emptyList())
        _ui.update {
            it.copy(online = true, error = null, version = ver,
                state = st.optString("state"), uptime = st.optString("uptime"), lastError = st.optString("last_error"),
                sections = sections, subscriptions = subs,
                nodes = nodes.getOrDefault(emptyList()),
                nodesError = nodes.exceptionOrNull()?.message,
                logs = logs.takeLast(150))
        }
    }

    fun selectNode(group: String, node: String) = call("Узел «$node» выбран") { c -> c.selectNode(group, node); reload(c) }

    fun testNode(node: String) = call(quiet = true) { c ->
        val ms = c.testNode(node)
        _ui.update { it.copy(delays = it.delays + (node to ms)) }
    }

    fun addSubscription(section: String, url: String) = call("Подписка добавлена на роутер") { c -> c.addSubscription(section, url); reload(c) }

    fun refreshSubscription(index: Int) = call("Подписка на роутере обновлена") { c -> c.refreshSubscription(index); reload(c) }

    fun deleteSubscription(index: Int) = call("Подписка удалена с роутера") { c -> c.deleteSubscription(index); reload(c) }

    fun restart() = call("Роутер перезапускает службу…") { c -> c.restart() }

    fun reloadConfig() = call("Конфигурация роутера перечитана") { c -> c.reload(); reload(c) }

    private fun reload(c: RouterClient) {
        val subs = c.subscriptions()
        val nodes = runCatching { c.nodes() }
        _ui.update { it.copy(subscriptions = subs, nodes = nodes.getOrDefault(it.nodes), nodesError = nodes.exceptionOrNull()?.message) }
    }

    private fun call(done: String? = null, quiet: Boolean = false, block: (RouterClient) -> Unit) {
        val link = current ?: return
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                _ui.update { it.copy(busy = true) }
                try {
                    block(RouterClient(link))
                    done?.let(toast)
                } catch (e: Exception) {
                    val msg = when (e) {
                        is RouterException -> e.message
                        is javax.net.ssl.SSLException -> "TLS: ${e.message}"
                        is java.net.ConnectException, is java.net.SocketTimeoutException -> "роутер ${link.host}:${link.port} не отвечает"
                        else -> e.message ?: e.toString()
                    }
                    _ui.update { it.copy(online = e is RouterException, error = msg) }
                    if (!quiet) toast("Роутер: $msg")
                } finally {
                    _ui.update { it.copy(busy = false) }
                }
            }
        }
    }
}
