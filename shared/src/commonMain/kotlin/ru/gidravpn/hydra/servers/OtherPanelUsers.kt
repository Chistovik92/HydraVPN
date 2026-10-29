package ru.gidravpn.hydra.servers

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Пользователи s-ui и x-ui прямо в Hydra (0.8.2-rc2, A1): список, добавить, ссылка/подписка, удалить. Работает по SSH тем же
 * входом по cookie, что и подключения ([SessionPanelApi]). Здесь — только чистая логика (разбор ответов панелей и тела запросов),
 * вызовы — в [MyServersManager].
 *
 * Пользователь x-ui — это набор клиентов с общим `subId` (по клиенту в каждом инбаунде: почта в x-ui должна быть уникальной во всей
 * панели, поэтому `имя-<номер инбаунда>`); пользователь s-ui — один клиент, подключённый ко всем инбаундам.
 */
object OtherPanelUsers {
    /** Пользователь панели: [name] — как в списке, [subId] — общий идентификатор подписки (x-ui) или имя (s-ui). */
    data class User(val name: String, val subId: String)

    /** Протоколы инбаундов x-ui с клиентами; WireGuard, Hysteria 2 и прочее без клиентов Hydra не трогает. */
    val XUI_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks")

    /** Есть ли у Hydra вход в API пользователей этой панели: 3x-ui — по токену, s-ui и x-ui — по SSH. */
    fun supported(s: MyServer): Boolean {
        val p = s.panel ?: return false
        return when (p.kind) {
            PanelKind.XUI3, PanelKind.NEXORA -> p.token.isNotBlank()
            PanelKind.SUI, PanelKind.XUI -> s.fingerprint.isNotBlank()
            else -> false
        }
    }

    /** Имя пользователя из почты клиента x-ui, созданного Hydra: `alice-3` → `alice`. */
    fun nameFromEmail(email: String): String = email.replace(Regex("-\\d+$"), "")

    /** Почта клиента x-ui в инбаунде [inboundId]. */
    fun xuiEmail(name: String, inboundId: Int) = "$name-$inboundId"

    private fun clients(inbound: JSONObject): JSONArray {
        val st = inbound.opt("settings")
        val o = when (st) { is JSONObject -> st; is String -> runCatching { JSONObject(st) }.getOrNull(); else -> null }
        return o?.optJSONArray("clients") ?: JSONArray()
    }

    /** Пользователи x-ui: клиенты всех инбаундов, сгруппированные по `subId` (общая группа подключений Hydra — «hydra»). */
    fun xuiUsers(inbounds: JSONArray, hydraSubId: String): List<User> {
        val seen = LinkedHashMap<String, User>()
        for (i in 0 until inbounds.length()) {
            val cl = clients(inbounds.getJSONObject(i))
            for (j in 0 until cl.length()) {
                val c = cl.getJSONObject(j)
                val sub = c.optString("subId")
                if (sub.isBlank()) continue
                seen.getOrPut(sub) { User(if (sub == hydraSubId) "hydra" else nameFromEmail(c.optString("email")), sub) }
            }
        }
        return seen.values.toList()
    }

    /** Клиент x-ui для инбаунда с протоколом [protocol]; null — протокол без клиентов (WireGuard и др.). */
    fun xuiClient(protocol: String, email: String, subId: String, uuid: String, password: String, ssKey: String): JSONObject? {
        if (protocol !in XUI_PROTOCOLS) return null
        val o = JSONObject().put("email", email).put("enable", true).put("limitIp", 0).put("totalGB", 0).put("expiryTime", 0)
            .put("tgId", "").put("subId", subId).put("reset", 0)
        when (protocol) {
            "vless" -> o.put("id", uuid).put("flow", "")
            "vmess" -> o.put("id", uuid).put("security", "auto")
            "trojan" -> o.put("password", password)
            "shadowsocks" -> o.put("method", "").put("password", ssKey)
        }
        return o
    }

    /** Тело `POST xui/API/inbounds/addClient`: поле `id` — инбаунд, `settings` — JSON-строка с клиентом. */
    fun xuiAddClientForm(inboundId: Int, client: JSONObject): String =
        "id=$inboundId&settings=" + java.net.URLEncoder.encode(JSONObject().put("clients", JSONArray().put(client)).toString(), "UTF-8")

    /** Идентификатор клиента для `delClient`: у VLESS/VMess — `id`, у Trojan/Shadowsocks — пароль. */
    fun xuiClientKey(c: JSONObject): String = c.optString("id").ifBlank { c.optString("password") }

    /** Клиенты x-ui пользователя [subId]: (номер инбаунда, ключ клиента для удаления). */
    fun xuiClientsOf(inbounds: JSONArray, subId: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        for (i in 0 until inbounds.length()) {
            val ib = inbounds.getJSONObject(i); val cl = clients(ib)
            for (j in 0 until cl.length()) cl.getJSONObject(j).takeIf { it.optString("subId") == subId }?.let { out += ib.optInt("id") to xuiClientKey(it) }
        }
        return out
    }

    /** Инбаунды x-ui, куда добавляется новый пользователь: все с клиентами. */
    fun xuiTargets(inbounds: JSONArray): List<JSONObject> =
        (0 until inbounds.length()).map { inbounds.getJSONObject(it) }.filter { it.optString("protocol") in XUI_PROTOCOLS }

    /** Пользователи s-ui из `api/load`. */
    fun suiUsers(load: JSONObject): List<User> {
        val a = load.optJSONArray("clients") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { User(it.optString("name"), it.optString("name")) }.filter { it.name.isNotBlank() }
    }

    /** Номера всех инбаундов s-ui: новый пользователь подключается ко всем (учётные данные клиента — под каждый протокол). */
    fun suiInboundIds(load: JSONObject): List<Int> {
        val a = load.optJSONArray("inbounds") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optInt("id") }.filter { it > 0 }
    }

    /** Новый клиент s-ui для [name]: как у подключения, но ко всем [inboundIds]. */
    fun suiClient(name: String, inboundIds: List<Int>, r: SecureRandom = SecureRandom()): JSONObject {
        val c = OtherPanelConnections.newCreds(XuiConnection.VLESS_TCP_REALITY, name, r)
        return OtherPanelConnections.suiClient(OtherPanelConnections.Creds(name, name, c.uuid, c.password, c.ssKey, c.wgPriv, c.wgPub), 0)
            .put("inbounds", JSONArray(inboundIds))
    }

    /** Имя пользователя: буквы, цифры, `_` и `-`, до 32 знаков — иначе панели отвечают отказом на странных почтах. */
    fun validName(n: String) = Regex("^[A-Za-z0-9_-]{1,32}$").matches(n)
}
