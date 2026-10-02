package ru.gidravpn.hydra.router

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class RouterRadar(val linked: Boolean, val server: String = "", val username: String = "")

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
    /** Остальные поля `/status` (провайдеры, DNS, файрвол…): ключ → краткое значение. */
    val statusDetails: List<Pair<String, String>> = emptyList(),
    /** Аккаунт бота «Радар», подключённый к самому роутеру; null — ещё не узнавали. */
    val radar: RouterRadar? = null,
    /** Результаты диагностики: имя проверки → ответ роутера. */
    val checks: Map<String, String> = emptyMap(),
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
        val radar = runCatching { c.radar() }.getOrNull()?.let { RouterRadar(it.optBoolean("linked"), it.optString("server"), it.optString("username")) }
        _ui.update {
            it.copy(online = true, error = null, version = ver,
                state = st.optString("state"), uptime = st.optString("uptime"), lastError = st.optString("last_error"),
                sections = sections, subscriptions = subs,
                nodes = nodes.getOrDefault(emptyList()),
                nodesError = nodes.exceptionOrNull()?.message,
                logs = logs.takeLast(150), statusDetails = summarize(st), radar = radar ?: it.radar)
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

    // ------------------------------------------------------------ разделы

    /** Что можно поменять в разделе из приложения; остальные поля раздела остаются как на роутере. */
    data class SectionEdit(
        val label: String, val enabled: Boolean, val action: String, val provider: String,
        val communityLists: List<String>, val ruleSet: List<String>, val fullyRoutedIps: List<String>,
    )

    private fun merged(base: JSONObject?, name: String, e: SectionEdit): JSONObject = JSONObject((base ?: JSONObject()).toString()).apply {
        put("name", name); put("label", e.label); put("enabled", e.enabled); put("action", e.action); put("provider", e.provider)
        put("community_lists", JSONArray(e.communityLists)); put("rule_set", JSONArray(e.ruleSet)); put("fully_routed_ips", JSONArray(e.fullyRoutedIps))
    }

    fun setSectionEnabled(s: RouterSection, on: Boolean) {
        if (!s.editable) return toast("В разделе «${s.title}» личные ссылки — роутер скрывает их от приложения, поэтому правьте его в веб-интерфейсе роутера.")
        saveSection(s, SectionEdit(s.label, on, s.action, s.provider, s.list("community_lists"), s.list("rule_set"), s.list("fully_routed_ips")))
    }

    fun saveSection(s: RouterSection, e: SectionEdit) {
        if (!s.editable) return toast("В разделе «${s.title}» личные ссылки — правьте его в веб-интерфейсе роутера.")
        call("Раздел «${s.title}» сохранён на роутере") { c -> c.replaceSection(s.name, merged(s.raw, s.name, e)); reloadSections(c) }
    }

    fun addSection(name: String, e: SectionEdit) = call("Раздел «$name» добавлен на роутер") { c ->
        c.addSection(merged(null, name, e)); reloadSections(c)
    }

    fun deleteSection(name: String) = call("Раздел «$name» удалён с роутера") { c -> c.deleteSection(name); reloadSections(c) }

    private fun reloadSections(c: RouterClient) {
        val sections = c.sections()
        _ui.update { it.copy(sections = sections) }
    }

    // ------------------------------------------------------------ аккаунт бота на роутере и диагностика

    fun radarLink(server: String, code: String, section: String?) = call(null) { c ->
        val r = c.radarLink(server, code, section)
        _ui.update { it.copy(radar = RouterRadar(true, server)) }
        toast(radarText(r))
        refreshRadar(c); reload(c)
    }

    fun radarSync(section: String?) = call(null) { c -> toast(radarText(c.radarSync(section))); reload(c) }

    fun radarUnlink() = call("Роутер отключён от бота") { c -> c.radarUnlink(); refreshRadar(c) }

    private fun refreshRadar(c: RouterClient) {
        val r = runCatching { c.radar() }.getOrNull() ?: return
        _ui.update { it.copy(radar = RouterRadar(r.optBoolean("linked"), r.optString("server"), r.optString("username"))) }
    }

    private fun radarText(o: JSONObject): String {
        val r = o.optJSONObject("result") ?: return if (o.optString("status") == "applying") "Роутер применяет изменения — подписки появятся через минуту." else "Готово."
        return "Подписки бота на роутере: добавлено ${r.optInt("added")}, уже были ${r.optInt("present")}, пропущено ${r.optInt("skipped")}."
    }

    /** Диагностика роутера (`global`, `dns`, `singbox`, `nft`, `proxy`): ответ показывается в карточке. */
    fun runCheck(name: String) = call(quiet = true) { c ->
        val text = c.check(name).trim().take(4000)
        _ui.update { it.copy(checks = it.checks + (name to text)) }
    }

    private fun summarize(st: JSONObject): List<Pair<String, String>> = st.keys().asSequence()
        .filter { it !in setOf("state", "uptime", "last_error") }
        .mapNotNull { k ->
            val v = st.opt(k)
            val text = when (v) {
                null, JSONObject.NULL -> return@mapNotNull null
                is JSONArray -> if (v.length() == 0) return@mapNotNull null else "${v.length()} шт."
                is JSONObject -> v.keys().asSequence().take(6).joinToString(", ") { kk -> "$kk=${v.opt(kk)}".take(40) }
                else -> v.toString()
            }
            k to text.take(160)
        }.take(14).toList()

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
                        // Android без исключения для HTTP (network_security_config): работает только TLS.
                        else -> if (e.message?.contains("cleartext", true) == true)
                            "система запрещает HTTP без TLS — включите api_tls_cert/api_tls_key на роутере и сопрягитесь заново по https-ссылке"
                        else e.message ?: e.toString()
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
