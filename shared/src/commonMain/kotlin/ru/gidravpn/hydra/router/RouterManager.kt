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
    // 1.3.0+: всё ниже пусто у роутеров, которые этого не умеют (capabilities).
    val caps: RouterCapabilities = RouterCapabilities(),
    /** Самообновление роутера (current, latest, update_available, state, error); null — не поддерживается. */
    val update: JSONObject? = null,
    val cores: List<RouterCore> = emptyList(),
    /** Версии ядра для выбора: имя ядра → релизы (с бетами). */
    val coreVersions: Map<String, List<RouterRelease>> = emptyMap(),
    val rules: List<RouterRule> = emptyList(),
    val lists: List<RouterList> = emptyList(),
    val servers: List<JSONObject> = emptyList(),
    val settings: JSONObject? = null,
    val smsModems: List<String> = emptyList(),
    val sms: List<RouterSms> = emptyList(),
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

    /** Для скриншотов и тестов отрисовки: подменяет состояние экрана (в самом приложении не используется). */
    fun previewState(state: RouterUi) { _ui.value = state }

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
        val caps = c.capabilities()
        val sections = c.sections().map { it.copy(serverUnmasks = caps.has("sections.unmask")) }
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
                logs = logs.takeLast(150), statusDetails = summarize(st), radar = radar ?: it.radar, caps = caps)
        }
        loadManagement(c, caps)
    }

    /** Новое в 1.3.0: обновление, ядра, правила, списки, серверы, настройки, SMS — только то, что роутер умеет. */
    private fun loadManagement(c: RouterClient, caps: RouterCapabilities) {
        val update = if (caps.has("update")) runCatching { c.updateStatus() }.getOrNull() else null
        val cores = if (caps.has("cores")) runCatching { c.cores() }.getOrDefault(emptyList()) else emptyList()
        val rules = if (caps.has("rules")) runCatching { c.rules() }.getOrDefault(emptyList()) else emptyList()
        val lists = if (caps.has("lists")) runCatching { c.lists() }.getOrDefault(emptyList()) else emptyList()
        val servers = if (caps.has("servers")) runCatching { c.servers() }.getOrDefault(emptyList()) else emptyList()
        val settings = if (caps.has("settings")) runCatching { c.settings() }.getOrNull() else null
        val sms = if (caps.has("sms")) runCatching { c.sms() }.getOrNull() else null
        _ui.update {
            it.copy(update = update, cores = cores, rules = rules, lists = lists, servers = servers, settings = settings,
                smsModems = sms?.first.orEmpty(), sms = sms?.second.orEmpty())
        }
    }

    // ------------------------------------------------------------ обновление роутера и ядра

    fun checkUpdate() = call(null) { c ->
        val st = c.updateCheck()
        _ui.update { it.copy(update = st) }
        toast(if (st.optBoolean("update_available")) "Есть обновление роутера: ${st.optString("latest")}" else "Роутер обновлён до последней версии")
    }

    /** [version] — конкретная (в том числе старая: откат); null — последняя. Роутер перезапустится сам. */
    fun installUpdate(version: String?) = call("Роутер обновляется и перезапустится сам — это займёт минуту") { c ->
        c.updateInstall(version)
        _ui.update { st -> st.copy(update = runCatching { c.updateStatus() }.getOrNull() ?: st.update) }
    }

    fun loadCoreVersions(name: String) = call(quiet = true) { c ->
        val rels = c.coreVersions(name)
        _ui.update { it.copy(coreVersions = it.coreVersions + (name to rels)) }
    }

    /** [version]: x.y.z, latest или beta. Установка идёт на роутере в фоне — статус обновится при опросе. */
    fun installCore(name: String, version: String) = call("Роутер устанавливает $name $version") { c ->
        c.installCore(name, version)
        _ui.update { it.copy(cores = runCatching { c.cores() }.getOrDefault(it.cores)) }
    }

    // ------------------------------------------------------------ маршрутизация: правила и списки

    fun saveRule(index: Int?, rule: JSONObject) = call(if (index == null) "Правило добавлено" else "Правило сохранено") { c ->
        if (index == null) c.addRule(rule) else c.replaceRule(index, rule)
        _ui.update { it.copy(rules = c.rules()) }
    }

    fun deleteRule(index: Int) = call("Правило удалено") { c -> c.deleteRule(index); _ui.update { it.copy(rules = c.rules()) } }

    fun saveList(existing: String?, list: JSONObject) = call("Список «${list.optString("name")}» сохранён") { c ->
        if (existing == null) c.addList(list) else c.replaceList(existing, list)
        _ui.update { it.copy(lists = c.lists()) }
    }

    fun deleteList(name: String) = call("Список «$name» удалён") { c -> c.deleteList(name); _ui.update { it.copy(lists = c.lists()) } }

    // ------------------------------------------------------------ серверы, подписки, настройки, SMS

    fun saveServer(existing: String?, server: JSONObject) = call("Сервер «${server.optString("name")}» сохранён") { c ->
        if (existing == null) c.addServer(server) else c.replaceServer(existing, server)
        _ui.update { it.copy(servers = c.servers()) }
    }

    fun deleteServer(name: String) = call("Сервер «$name» удалён") { c -> c.deleteServer(name); _ui.update { it.copy(servers = c.servers()) } }

    /** Правка подписки: раздел и интервал; замаскированный адрес роутер оставит прежним. */
    fun editSubscription(s: RouterSubscription, section: String, updateHours: Int) = call("Подписка сохранена") { c ->
        c.replaceSubscription(s.index, JSONObject().put("section", section).put("url", s.url)
            .put("subscription_update_enabled", updateHours > 0)
            .put("subscription_update_interval", updateHours.coerceAtLeast(1) * 3_600_000_000_000L))
        reload(c)
    }

    /** Меняет одну настройку; значение — Boolean, число, строка ("6h") или список. */
    fun setSetting(key: String, value: Any) = call("Настройка $key сохранена") { c ->
        c.patchSettings(JSONObject().put(key, if (value is List<*>) JSONArray(value) else value))
        _ui.update { it.copy(settings = c.settings()) }
    }

    fun sendSms(to: String, text: String) = call("SMS отправлено на $to") { c -> c.sendSms(to, text) }

    fun refreshSms() = call(quiet = true) { c ->
        val (modems, msgs) = c.sms()
        _ui.update { it.copy(smsModems = modems, sms = msgs) }
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
