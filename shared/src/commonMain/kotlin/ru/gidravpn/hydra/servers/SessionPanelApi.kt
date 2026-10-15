package ru.gidravpn.hydra.servers

import org.json.JSONArray
import org.json.JSONObject

/**
 * API панелей со входом по логину и паролю (cookie-сессия) — x-ui (alireza0) и s-ui (0.8.2-a2): токенов API у них нет.
 * Запросы идут `curl` на самом сервере (как у 3x-ui по SSH): панель может быть закрыта извне, пароль не идёт по сети.
 * Cookie сессии — во временном файле 0600 на сервере, удаляется в [close].
 */
class SessionPanelApi(private val ssh: SshSession, private val panel: PanelInstance) : AutoCloseable {
    private val u = java.net.URL(panel.baseUrl)
    /** Адрес панели на самом сервере: та же схема, порт и путь, хост 127.0.0.1 (сертификат выписан на внешний адрес — `-k`). */
    val local = "${u.protocol}://127.0.0.1:${u.port}${u.path.trimEnd('/')}/"
    private val jar = "/tmp/.hydra-panel-${XuiPanel.random(8).lowercase()}.cookie"
    private val host = u.host.removePrefix("[").removeSuffix("]")
    /** s-ui без заголовка Origin вход не принимает. */
    private val origin = "${u.protocol}://127.0.0.1:${u.port}"

    private fun curl(args: String): String {
        val r = ssh.exec("umask 077; curl -ksS -m 25 -b $jar -c $jar -H ${Installer.shq("Host: $host")} $args")
        if (r.out.isBlank()) throw XuiApiException("Панель не ответила (curl код ${r.exit})")
        return r.out
    }

    /** Тело запроса — файлом 0600 (не в командной строке: пароль и ключи не видны в `ps`). */
    private fun post(path: String, body: String, contentType: String): String {
        val f = "/tmp/.hydra-panel-body-${XuiPanel.random(6).lowercase()}"
        ssh.upload(body.toByteArray(), f, 384)
        return try { curl("-X POST -H ${Installer.shq("Content-Type: $contentType")} -H 'X-Requested-With: XMLHttpRequest' -H ${Installer.shq("Origin: $origin")} --data-binary @$f ${Installer.shq(local + path)}") }
        finally { ssh.exec("rm -f $f") }
    }

    private fun obj(raw: String): Any? {
        val j = runCatching { JSONObject(raw.trim()) }.getOrElse { throw XuiApiException("Некорректный ответ панели: ${raw.take(120)}") }
        if (!j.optBoolean("success")) throw XuiApiException(j.optString("msg").ifBlank { "Панель отклонила запрос" })
        return j.opt("obj")
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    fun login() {
        val l = PanelWeb.login(panel, local) ?: throw XuiApiException("Нет логина и пароля панели")
        obj(post(l.url.removePrefix(local), l.body, "application/x-www-form-urlencoded"))
    }

    // ------------------------------------------------------------------------------------------ x-ui (alireza0)

    /** Инбаунды x-ui: `GET {адрес}xui/API/inbounds/`. */
    fun xuiInbounds(): JSONArray = obj(curl(Installer.shq(local + "xui/API/inbounds/"))) as? JSONArray ?: JSONArray()

    /** Добавить инбаунд x-ui: поля settings/streamSettings/sniffing — строками JSON (модель старой 3x-ui). */
    fun xuiAddInbound(ib: JSONObject): Int {
        val body = JSONObject(ib.toString())
        for (k in listOf("settings", "streamSettings", "sniffing")) body.opt(k)?.let { if (it is JSONObject) body.put(k, it.toString()) }
        return (obj(post("xui/API/inbounds/add", body.toString(), "application/json")) as? JSONObject)?.optInt("id") ?: 0
    }

    fun xuiDelInbound(id: Int) { obj(post("xui/API/inbounds/del/$id", "", "application/x-www-form-urlencoded")) }

    // ------------------------------------------------------------------------------------------ s-ui

    /** Всё состояние s-ui: `GET {адрес}api/load` (inbounds, clients, tls, endpoints …). */
    fun suiLoad(): JSONObject = obj(curl(Installer.shq(local + "api/load"))) as? JSONObject ?: JSONObject()

    /** Сохранить объект s-ui: `POST {адрес}api/save` (object = inbounds / clients / tls / endpoints, action = new / edit / del). */
    fun suiSave(objName: String, action: String, data: Any, extra: Map<String, String> = emptyMap()): Any? =
        obj(post("api/save", "object=${enc(objName)}&action=${enc(action)}&data=${enc(data.toString())}" +
            extra.entries.joinToString("") { (k, v) -> "&${enc(k)}=${enc(v)}" }, "application/x-www-form-urlencoded"))

    /** Готовые ссылки пользователя s-ui (`api/clients` отдаёт их в поле links). */
    fun suiRaw(path: String): String = curl(Installer.shq(local + path))

    // ------------------------------------------------------------------------------------------ подписка

    /** Тело подписки по адресу на самом сервере (`http(s)://127.0.0.1:порт/путь/subId`), расшифрованное из base64. */
    fun subscription(port: Int, path: String, subId: String, https: Boolean): List<String> {
        val r = ssh.exec("curl -ksS -m 20 -H 'Accept: */*' -H ${Installer.shq("Host: $host")} ${Installer.shq("${if (https) "https" else "http"}://127.0.0.1:$port/${path.trim('/')}/$subId")}")
        val body = r.out.trim()
        val text = runCatching { String(java.util.Base64.getMimeDecoder().decode(body)) }.getOrDefault(body)
        return text.lines().map { it.trim() }.filter { "://" in it }
    }

    override fun close() { runCatching { ssh.exec("rm -f $jar") } }
}
