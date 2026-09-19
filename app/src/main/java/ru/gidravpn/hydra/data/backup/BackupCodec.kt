package ru.gidravpn.hydra.data.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.Subscription

/** Содержимое резервной копии в памяти. prefs: имя DataStore-файла → ключ → значение. */
data class Backup(
    val appVersion: String,
    val createdAt: Long,
    val prefs: Map<String, Map<String, Any>>,
    val servers: List<ServerProfile>,
    val subscriptions: List<Subscription>,
)

/** Файл не похож на бэкап Hydra или повреждён — [message] показывается пользователю. */
class BackupFormatException(message: String) : Exception(message)

/**
 * JSON-формат резервной копии (Фаза 6d). Без Android-зависимостей —
 * покрыт JVM-тестами (BackupCodecTest). Значения настроек хранятся с тегом
 * типа: DataStore Preferences различает Int/Long/String и т.д., и при
 * восстановлении ключ должен получить ровно тот же тип, иначе репозиторий
 * его не прочитает.
 */
object BackupCodec {
    const val FORMAT = "hydra-backup"
    const val VERSION = 1

    fun encode(b: Backup): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("appVersion", b.appVersion)
        put("createdAt", b.createdAt)
        put("prefs", JSONObject().apply {
            b.prefs.forEach { (store, values) ->
                put(store, JSONObject().apply { values.forEach { (k, v) -> put(k, encodeValue(v)) } })
            }
        })
        put("servers", JSONArray().apply { b.servers.forEach { put(encodeServer(it)) } })
        put("subscriptions", JSONArray().apply { b.subscriptions.forEach { put(encodeSubscription(it)) } })
    }.toString(2)

    fun decode(text: String): Backup {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw BackupFormatException("Файл не является резервной копией Hydra (не JSON)")
        }
        if (root.optString("format") != FORMAT) {
            throw BackupFormatException("Файл не является резервной копией Hydra")
        }
        val version = root.optInt("version", -1)
        if (version > VERSION) {
            throw BackupFormatException("Копия сделана более новой версией Hydra — обновите приложение")
        }
        return try {
            val prefsJson = root.optJSONObject("prefs") ?: JSONObject()
            Backup(
                appVersion = root.optString("appVersion"),
                createdAt = root.optLong("createdAt"),
                prefs = prefsJson.keys().asSequence().associateWith { store ->
                    val values = prefsJson.getJSONObject(store)
                    values.keys().asSequence().associateWith { key -> decodeValue(values.getJSONObject(key)) }
                },
                servers = root.optJSONArray("servers").objects().map(::decodeServer),
                subscriptions = root.optJSONArray("subscriptions").objects().map(::decodeSubscription),
            )
        } catch (e: JSONException) {
            throw BackupFormatException("Резервная копия повреждена: ${e.message}")
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun encodeValue(v: Any): JSONObject = JSONObject().apply {
        when (v) {
            is Boolean -> put("t", "b").put("v", v)
            is Int -> put("t", "i").put("v", v)
            is Long -> put("t", "l").put("v", v)
            is Float -> put("t", "f").put("v", v.toDouble())
            is Double -> put("t", "d").put("v", v)
            is String -> put("t", "s").put("v", v)
            is Set<*> -> put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
            else -> throw IllegalArgumentException("Неподдерживаемый тип настройки: ${v::class.java}")
        }
    }

    private fun decodeValue(o: JSONObject): Any = when (val t = o.getString("t")) {
        "b" -> o.getBoolean("v")
        "i" -> o.getInt("v")
        "l" -> o.getLong("v")
        "f" -> o.getDouble("v").toFloat()
        "d" -> o.getDouble("v")
        "s" -> o.getString("v")
        "ss" -> o.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        else -> throw JSONException("неизвестный тип настройки '$t'")
    }

    private fun encodeServer(s: ServerProfile) = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("protocolId", s.protocolId)
        put("address", s.address); put("port", s.port); put("uuidOrPassword", s.uuidOrPassword)
        put("flow", s.flow); put("sni", s.sni); put("transport", s.transport)
        put("transportPath", s.transportPath); put("security", s.security); put("alpn", s.alpn)
        put("fingerprint", s.fingerprint); put("extra", s.extra)
        put("subscriptionId", s.subscriptionId ?: JSONObject.NULL)
        put("pingMs", s.pingMs); put("flag", s.flag)
    }

    private fun decodeServer(o: JSONObject): ServerProfile {
        val d = ServerProfile(name = "", protocolId = "", address = "", port = 0)
        return ServerProfile(
            id = o.optLong("id", 0),
            name = o.getString("name"),
            protocolId = o.getString("protocolId"),
            address = o.getString("address"),
            port = o.getInt("port"),
            uuidOrPassword = o.optString("uuidOrPassword", d.uuidOrPassword),
            flow = o.optString("flow", d.flow),
            sni = o.optString("sni", d.sni),
            transport = o.optString("transport", d.transport),
            transportPath = o.optString("transportPath", d.transportPath),
            security = o.optString("security", d.security),
            alpn = o.optString("alpn", d.alpn),
            fingerprint = o.optString("fingerprint", d.fingerprint),
            extra = o.optString("extra", d.extra),
            subscriptionId = if (o.isNull("subscriptionId")) null else o.getLong("subscriptionId"),
            pingMs = o.optInt("pingMs", d.pingMs),
            flag = o.optString("flag", d.flag),
        )
    }

    private fun encodeSubscription(s: Subscription) = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("url", s.url); put("userAgent", s.userAgent)
        put("lastUpdated", s.lastUpdated); put("autoUpdateHours", s.autoUpdateHours)
        put("serverTitle", s.serverTitle); put("autoUpdate", s.autoUpdate); put("collapsed", s.collapsed)
    }

    private fun decodeSubscription(o: JSONObject): Subscription {
        val d = Subscription(name = "", url = "")
        return Subscription(
            id = o.optLong("id", 0),
            name = o.getString("name"),
            url = o.getString("url"),
            userAgent = o.optString("userAgent", d.userAgent),
            lastUpdated = o.optLong("lastUpdated", d.lastUpdated),
            autoUpdateHours = o.optInt("autoUpdateHours", d.autoUpdateHours),
            serverTitle = o.optString("serverTitle", d.serverTitle),
            autoUpdate = o.optBoolean("autoUpdate", d.autoUpdate),
            collapsed = o.optBoolean("collapsed", d.collapsed),
        )
    }
}
