package ru.gidravpn.hydra.servers

import org.json.JSONArray
import org.json.JSONObject

/**
 * Свой сервер из вкладки «Мои серверы». Секреты входа (пароль/ключ SSH) сюда **не** попадают — только отпечаток хоста.
 * Ключи AmneziaWG хранятся: без них нельзя добавить клиента или пересоздать конфиг.
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
    /** Узел OpenFlux (0.8.0); у сервера либо [awg], либо [ofx]. */
    val ofx: OfxNodeSpec? = null,
    /** Панель 3x-ui (0.8.1-a); токен API и пароль хранятся так же, как ключи AmneziaWG. */
    val panel: PanelInstance? = null,
    val clients: List<AwgClient> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("host", host).put("sshPort", sshPort).put("sshUser", sshUser)
        .put("fingerprint", fingerprint).put("createdAt", createdAt)
        .also { o ->
            awg?.let { a ->
                o.put("awg", JSONObject().put("host", a.host).put("port", a.port).put("gen", a.generation.name)
                    .put("params", JSONObject(a.params as Map<*, *>)).put("priv", a.privateKey).put("pub", a.publicKey)
                    .put("subnet", a.subnet).put("dns", a.dns).put("mtu", a.mtu).put("iface", a.iface))
            }
            ofx?.let { o.put("ofx", it.toJson()) }
            panel?.let { o.put("panel", it.toJson()) }
            o.put("clients", JSONArray(clients.map {
                JSONObject().put("name", it.name).put("priv", it.privateKey).put("pub", it.publicKey)
                    .put("psk", it.presharedKey).put("addr", it.address)
            }))
        }

    companion object {
        fun fromJson(o: JSONObject): MyServer {
            val awg = o.optJSONObject("awg")?.let { a ->
                val p = a.getJSONObject("params")
                AwgServerSpec(
                    a.getString("host"), a.getInt("port"), AwgGeneration.valueOf(a.getString("gen")),
                    p.keys().asSequence().associateWith { k -> p.getString(k) },
                    a.getString("priv"), a.getString("pub"), a.optString("subnet", "10.8.1"),
                    a.optString("dns", "1.1.1.1"), a.optInt("mtu", 1280), a.optString("iface", "awg0"),
                )
            }
            val cl = o.optJSONArray("clients") ?: JSONArray()
            return MyServer(
                o.getString("id"), o.getString("name"), o.getString("host"), o.optInt("sshPort", 22),
                o.optString("sshUser", "root"), o.getString("fingerprint"), awg, o.optJSONObject("ofx")?.let(OfxNodeSpec::fromJson), o.optJSONObject("panel")?.let(PanelInstance::fromJson),
                (0 until cl.length()).map { cl.getJSONObject(it) }.map {
                    AwgClient(it.getString("name"), it.getString("priv"), it.getString("pub"), it.getString("psk"), it.getString("addr"))
                },
                o.optLong("createdAt"),
            )
        }

        fun listToJson(l: List<MyServer>): String = JSONArray(l.map { it.toJson() }).toString()
        fun listFromJson(s: String): List<MyServer> =
            runCatching { JSONArray(s).let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
    }
}
