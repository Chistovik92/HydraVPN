package ru.gidravpn.hydra.servers

import org.json.JSONArray
import org.json.JSONObject

/**
 * Свой сервер из вкладки «Мои серверы».
 *
 * С 0.8.0-rc4 сервер — это прежде всего **доступ** (адрес, пользователь, закреплённый отпечаток SSH), а службы на нём
 * ([awg], [ofx]) ставятся и снимаются отдельно, уже после входа; на одном сервере могут стоять обе.
 * Секреты входа (пароль/ключ SSH) сюда **не** попадают — они в зашифрованном [CredentialVault].
 * Ключи AmneziaWG хранятся здесь и копией на самом сервере (`/etc/hydra/awg.json`, 0600): без них нельзя добавить клиента
 * или показать конфиг, а копия на сервере позволяет управлять им с другого устройства.
 */
data class MyServer(
    val id: String,
    val name: String,
    val host: String,
    val sshPort: Int = 22,
    val sshUser: String = "root",
    /** Закреплённый отпечаток SSH-ключа хоста (`SHA256:…`). */
    val fingerprint: String,
    val awg: AwgServerSpec? = null,
    /** Узел OpenFlux (0.8.0). С 0.8.0-rc4 может стоять вместе с [awg]. */
    val ofx: OfxNodeSpec? = null,
    /**
     * Панель 3x-ui (0.8.1): поставленная Hydra по SSH или подключённая готовая по адресу и токену. У подключённой нет SSH
     * ([fingerprint] пуст) — с ней работают только через API панели. Токен и пароль хранятся так же, как ключи AmneziaWG.
     */
    val panel: PanelInstance? = null,
    val clients: List<AwgClient> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** Что показала последняя проверка: система и архитектура (для карточки; пусто — ещё не проверяли). */
    val osName: String = "",
    val arch: String = "",
    /** Сервер работает нодой чужой панели из «Моих серверов» (0.8.2-a4). */
    val nodeOf: NodeRole? = null,
    /**
     * Поля записи, которых эта версия не знает (0.8.0-rc4 / 0.8.1-b): сохраняются как есть, чтобы переход между версиями
     * (например, 0.8.1 → 0.8.0) не стирал данные более новой — так в rc4 потерялось поле `panel` записей из 0.8.1-a.
     */
    val unknown: String = "{}",
) {
    val hasServices get() = awg != null || ofx != null || panel != null || nodeOf != null
    /** Панель, подключённая без SSH: ни проверки, ни установки служб, ни очистки — только API панели. */
    val panelOnly get() = fingerprint.isBlank() && panel != null
    /** Запись без отпечатка и без панели — её не к чему подключать (так выглядит панель 0.8.1-a, пересохранённая 0.8.0-rc4). */
    val broken get() = fingerprint.isBlank() && panel == null

    fun toJson(): JSONObject = (runCatching { JSONObject(unknown) }.getOrNull() ?: JSONObject())
        .put("id", id).put("name", name).put("host", host).put("sshPort", sshPort).put("sshUser", sshUser)
        .put("fingerprint", fingerprint).put("createdAt", createdAt).put("osName", osName).put("arch", arch)
        .also { o ->
            awg?.let { o.put("awg", it.toJson()) }
            ofx?.let { o.put("ofx", it.toJson()) }
            panel?.let { o.put("panel", it.toJson()) }
            nodeOf?.let { o.put("nodeOf", it.toJson()) }
            o.put("clients", clientsToJson(clients))
        }

    companion object {
        fun fromJson(o: JSONObject): MyServer = MyServer(
            o.getString("id"), o.getString("name"), o.getString("host"), o.optInt("sshPort", 22),
            o.optString("sshUser", "root"), o.getString("fingerprint"),
            o.optJSONObject("awg")?.let(AwgServerSpec::fromJson), o.optJSONObject("ofx")?.let(OfxNodeSpec::fromJson),
            o.optJSONObject("panel")?.let(PanelInstance::fromJson),
            clientsFromJson(o.optJSONArray("clients")),
            o.optLong("createdAt"), o.optString("osName"), o.optString("arch"),
            o.optJSONObject("nodeOf")?.let { runCatching { NodeRole.fromJson(it) }.getOrNull() },
            JSONObject().also { u -> o.keys().forEach { k -> if (k !in KNOWN) u.put(k, o.get(k)) } }.toString(),
        )

        private val KNOWN = setOf("id", "name", "host", "sshPort", "sshUser", "fingerprint", "awg", "ofx", "panel", "clients", "createdAt", "osName", "arch", "nodeOf")

        fun clientsToJson(l: List<AwgClient>) = JSONArray(l.map {
            JSONObject().put("name", it.name).put("priv", it.privateKey).put("pub", it.publicKey)
                .put("psk", it.presharedKey).put("addr", it.address)
        })

        fun clientsFromJson(a: JSONArray?): List<AwgClient> = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }.map {
            AwgClient(it.getString("name"), it.optString("priv"), it.getString("pub"), it.optString("psk"), it.getString("addr"))
        }

        fun listToJson(l: List<MyServer>): String = JSONArray(l.map { it.toJson() }).toString()
        fun listFromJson(s: String): List<MyServer> =
            runCatching { JSONArray(s).let { a -> (0 until a.length()).mapNotNull { runCatching { fromJson(a.getJSONObject(it)) }.getOrNull() } } }
                .getOrDefault(emptyList())
    }
}
