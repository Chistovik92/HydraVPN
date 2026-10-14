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
    val clients: List<AwgClient> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** Что показала последняя проверка: система и архитектура (для карточки; пусто — ещё не проверяли). */
    val osName: String = "",
    val arch: String = "",
) {
    val hasServices get() = awg != null || ofx != null

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("host", host).put("sshPort", sshPort).put("sshUser", sshUser)
        .put("fingerprint", fingerprint).put("createdAt", createdAt).put("osName", osName).put("arch", arch)
        .also { o ->
            awg?.let { o.put("awg", it.toJson()) }
            ofx?.let { o.put("ofx", it.toJson()) }
            o.put("clients", clientsToJson(clients))
        }

    companion object {
        fun fromJson(o: JSONObject): MyServer = MyServer(
            o.getString("id"), o.getString("name"), o.getString("host"), o.optInt("sshPort", 22),
            o.optString("sshUser", "root"), o.getString("fingerprint"),
            o.optJSONObject("awg")?.let(AwgServerSpec::fromJson), o.optJSONObject("ofx")?.let(OfxNodeSpec::fromJson),
            clientsFromJson(o.optJSONArray("clients")),
            o.optLong("createdAt"), o.optString("osName"), o.optString("arch"),
        )

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
