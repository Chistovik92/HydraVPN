package ru.gidravpn.hydra.servers

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Каталог панелей (0.8.1-a). Пока реализована 3x-ui; s-ui и x-ui — в плане (docs/plans/0.8.1.md).
 */
enum class PanelKind(val title: String, val repo: String) {
    XUI3("3x-ui", "MHSanaei/3x-ui"),
}

/**
 * Развёрнутая панель. Вход в API — **Bearer-токеном** (решение владельца 11.10.2026): его пишет сам установщик
 * 3x-ui в `/etc/x-ui/install-result.env`; пароль нужен только для входа в веб-интерфейс.
 */
data class PanelInstance(
    val kind: PanelKind,
    /** Адрес панели с путём: `http://host:port/path` (без завершающего слеша). */
    val baseUrl: String,
    val username: String,
    val password: String,
    val token: String,
    val version: String = "",
    /** Созданный мастером инбаунд и клиент (для списка и удаления). */
    val inboundId: Int = 0,
    val clientEmail: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("baseUrl", baseUrl).put("user", username).put("password", password)
        .put("token", token).put("version", version).put("inboundId", inboundId).put("client", clientEmail)

    companion object {
        fun fromJson(o: JSONObject) = PanelInstance(
            runCatching { PanelKind.valueOf(o.optString("kind", "XUI3")) }.getOrDefault(PanelKind.XUI3),
            o.getString("baseUrl"), o.optString("user"), o.optString("password"), o.getString("token"),
            o.optString("version"), o.optInt("inboundId"), o.optString("client"),
        )
    }
}

/** Как доходят запросы до панели: напрямую по HTTP или через `curl` на самом сервере по SSH (токен не идёт по сети, закрытый порт не мешает). */
interface XuiTransport {
    fun call(method: String, path: String, body: String? = null): String
}

class XuiApiException(message: String) : Exception(message)

class DirectXuiTransport(private val baseUrl: String, private val token: String, private val timeoutMs: Int = 20_000) : XuiTransport {
    override fun call(method: String, path: String, body: String?): String {
        val c = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        if (code !in 200..299) throw XuiApiException("HTTP $code ${text.take(200)}")
        return text
    }
}

/** Вызовы идут `curl`-ом на сервере: [localBase] — `http://127.0.0.1:порт/путь`, [hostHeader] — внешний адрес для ссылок клиентов. */
class SshXuiTransport(private val ssh: SshSession, private val localBase: String, private val token: String, private val hostHeader: String) : XuiTransport {
    override fun call(method: String, path: String, body: String?): String {
        val bodyFile = "/tmp/.hydra-xui-body.json"
        val hdr = "-H ${Installer.shq("Authorization: Bearer $token")} -H ${Installer.shq("Host: $hostHeader")}"
        val cmd = if (body != null) {
            ssh.upload(body.toByteArray(), bodyFile, 384)
            "curl -sS -m 25 -X $method $hdr -H 'Content-Type: application/json' --data @$bodyFile ${Installer.shq(localBase.trimEnd('/') + path)}; rm -f $bodyFile"
        } else "curl -sS -m 25 -X $method $hdr ${Installer.shq(localBase.trimEnd('/') + path)}"
        val r = ssh.exec(cmd)
        if (r.out.isBlank()) throw XuiApiException("Панель не ответила (curl код ${r.exit})")
        return r.out
    }
}

/** Клиент API 3x-ui (`/panel/api/*`). Ответ панели всегда `{success, msg, obj}`. */
class XuiApi(private val t: XuiTransport) {
    private fun obj(raw: String): Any? {
        val j = runCatching { JSONObject(raw.trim()) }.getOrElse { throw XuiApiException("Некорректный ответ панели: ${raw.take(120)}") }
        if (!j.optBoolean("success")) throw XuiApiException(j.optString("msg").ifBlank { "Панель отклонила запрос" })
        return j.opt("obj")
    }

    /** Проверка токена и версии/настроек панели. */
    fun settings(): JSONObject = obj(t.call("POST", "/panel/api/setting/all")) as? JSONObject ?: JSONObject()

    fun status(): JSONObject = obj(t.call("GET", "/panel/api/server/status")) as? JSONObject ?: JSONObject()

    fun inbounds(): JSONArray = obj(t.call("GET", "/panel/api/inbounds/list/slim")) as? JSONArray ?: JSONArray()

    /** Ключи Reality генерирует сама панель (x25519 xray). */
    fun newRealityKeys(): Pair<String, String> {
        val o = obj(t.call("GET", "/panel/api/server/getNewX25519Cert")) as? JSONObject ?: throw XuiApiException("Панель не выдала ключи Reality")
        return o.getString("privateKey") to o.getString("publicKey")
    }

    /** Создаёт инбаунд из готового JSON и возвращает его id. */
    fun addInbound(json: JSONObject): Int = (obj(t.call("POST", "/panel/api/inbounds/add", json.toString())) as? JSONObject)?.optInt("id") ?: 0

    fun delInbound(id: Int) { obj(t.call("POST", "/panel/api/inbounds/del/$id")) }

    fun addClient(email: String, subId: String, inboundIds: List<Int>, flow: String = "xtls-rprx-vision", comment: String = "Hydra") {
        val client = JSONObject().put("email", email).put("subId", subId).put("totalGB", 0).put("expiryTime", 0).put("limitIp", 0)
            .put("enable", true).put("flow", flow).put("comment", comment)
        obj(t.call("POST", "/panel/api/clients/add", JSONObject().put("client", client).put("inboundIds", JSONArray(inboundIds)).toString()))
    }

    fun delClient(email: String) { obj(t.call("POST", "/panel/api/clients/del/${java.net.URLEncoder.encode(email, "UTF-8")}")) }

    /** Готовые ссылки (`vless://…`) клиента по всем его инбаундам. */
    fun links(email: String): List<String> {
        val a = obj(t.call("GET", "/panel/api/clients/links/${java.net.URLEncoder.encode(email, "UTF-8")}")) as? JSONArray ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }

    /** Список клиентов (`email`) всех инбаундов. */
    fun clientEmails(): List<String> {
        val a = obj(t.call("GET", "/panel/api/clients/list")) as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("email")?.takeIf { e -> e.isNotBlank() } }
    }
}

object XuiPanel {
    const val SCRIPT = "/root/.hydra-xui-install.sh"
    const val ENV_FILE = "/tmp/.hydra-xui.env"
    const val RESULT = "/etc/x-ui/install-result.env"

    fun scriptUrl(tag: String) = "https://raw.githubusercontent.com/MHSanaei/3x-ui/$tag/install.sh"

    /** Последний выпуск (тег) — показывается до запуска; сам скрипт ставит указанный тег, а не «последний». */
    fun latestTag(timeoutMs: Int = 15_000): String {
        val c = URL("https://api.github.com/repos/MHSanaei/3x-ui/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.setRequestProperty("Accept", "application/vnd.github+json")
        val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        return JSONObject(text).getString("tag_name").also { require(Regex("^v\\d+\\.\\d+\\.\\d+$").matches(it)) { "Неожиданный тег $it" } }
    }

    fun validTag(t: String) = Regex("^v\\d+\\.\\d+\\.\\d+$").matches(t)

    private val alnum = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    fun random(n: Int, rnd: SecureRandom = SecureRandom()) = String(CharArray(n) { alnum[rnd.nextInt(alnum.length)] })

    /** Параметры установки: учётные данные придумывает приложение (они же попадают в запись панели). */
    class InstallParams(
        val tag: String,
        val username: String = "hy" + random(6),
        val password: String = random(16),
        val port: Int = 20000 + SecureRandom().nextInt(40000),
        val webPath: String = random(14),
    )

    /** Шаги установки. Переменные окружения идут файлом 0600 (не в аргументах — не видны в `ps`). */
    fun installSteps(p: InstallParams, openFirewall: Boolean, firewall: String): List<Step> = buildList {
        require(validTag(p.tag)) { "Неверный тег ${p.tag}" }
        add(Step("xui-exists", "Проверить, что 3x-ui на сервере ещё нет", "test ! -e /usr/local/x-ui/x-ui",
            failHint = "На сервере уже стоит 3x-ui — подключите существующую панель по адресу и токену"))
        add(Step("xui-fetch", "Загрузить установщик 3x-ui ${p.tag} (закреплённый тег)",
            "(curl -fsSL ${Installer.shq(scriptUrl(p.tag))} -o $SCRIPT || wget -q -O $SCRIPT ${Installer.shq(scriptUrl(p.tag))}) && chmod 700 $SCRIPT && sha256sum $SCRIPT",
            rollback = "rm -f $SCRIPT", failHint = "Не удалось скачать установщик с GitHub"))
        add(Step("xui-env", "Записать параметры установки (файл 0600; логин и пароль не показываются)",
            upload = Step.Upload(ENV_FILE, ("XUI_NONINTERACTIVE=1\nXUI_USERNAME=${p.username}\nXUI_PASSWORD=${p.password}\nXUI_PANEL_PORT=${p.port}\n" +
                "XUI_WEB_BASE_PATH=${p.webPath}\nXUI_SSL_MODE=none\nXUI_ENABLE_FAIL2BAN=false\n").toByteArray(), 384)))
        add(Step("xui-install", "Установить 3x-ui ${p.tag}: ядро Xray, служба x-ui, панель на порту ${p.port}",
            "set -a; . $ENV_FILE; set +a; bash $SCRIPT ${p.tag} > /tmp/.hydra-xui-install.log 2>&1; rc=\$?; rm -f $ENV_FILE; tail -n 6 /tmp/.hydra-xui-install.log; exit \$rc",
            rollback = "/usr/local/x-ui/x-ui uninstall -y 2>/dev/null; rm -f $ENV_FILE", failHint = "Установка 3x-ui не удалась (журнал: /tmp/.hydra-xui-install.log)"))
        if (openFirewall && firewall == "ufw") add(Step("xui-fw", "Открыть порт панели ${p.port}/tcp в ufw", "ufw allow ${p.port}/tcp", rollback = "ufw delete allow ${p.port}/tcp"))
        if (openFirewall && firewall == "firewalld") add(Step("xui-fw", "Открыть порт панели ${p.port}/tcp в firewalld",
            "firewall-cmd --permanent --add-port=${p.port}/tcp && firewall-cmd --reload", rollback = "firewall-cmd --permanent --remove-port=${p.port}/tcp && firewall-cmd --reload"))
        add(Step("xui-active", "Проверить: служба x-ui запущена", "systemctl is-active x-ui",
            accept = { it.out.lineSequence().any { l -> l.trim() == "active" } }, failHint = "Служба x-ui не активна"))
    }

    /** Читает `/etc/x-ui/install-result.env` (`KEY=value`, значения в `printf %q` — без пробелов у наших случайных значений). */
    fun parseResult(text: String): Map<String, String> = text.lineSequence().mapNotNull { l ->
        val i = l.indexOf('='); if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim().trim('\'', '"').replace("\\", "")
    }.toMap()

    /** Инбаунд VLESS + Reality по умолчанию; ключи — от панели ([newRealityKeys]). */
    fun defaultInbound(port: Int, priv: String, pub: String, target: String = "www.cloudflare.com:443", remark: String = "Hydra", rnd: SecureRandom = SecureRandom()): JSONObject {
        val shortId = ByteArray(8).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val sni = target.substringBefore(':')
        val settings = JSONObject().put("clients", JSONArray()).put("decryption", "none").put("fallbacks", JSONArray())
        val reality = JSONObject().put("show", false).put("xver", 0).put("target", target).put("serverNames", JSONArray(listOf(sni)))
            .put("privateKey", priv).put("minClientVer", "").put("maxClientVer", "").put("maxTimediff", 0).put("shortIds", JSONArray(listOf(shortId)))
            .put("settings", JSONObject().put("publicKey", pub).put("fingerprint", "chrome").put("serverName", "").put("spiderX", "/"))
        val stream = JSONObject().put("network", "tcp").put("security", "reality").put("externalProxy", JSONArray()).put("realitySettings", reality)
            .put("tcpSettings", JSONObject().put("acceptProxyProtocol", false).put("header", JSONObject().put("type", "none")))
        val sniffing = JSONObject().put("enabled", true).put("destOverride", JSONArray(listOf("http", "tls", "quic"))).put("metadataOnly", false).put("routeOnly", false)
        return JSONObject().put("enable", true).put("remark", remark).put("listen", "").put("port", port).put("protocol", "vless")
            .put("expiryTime", 0).put("total", 0).put("settings", settings).put("streamSettings", stream).put("sniffing", sniffing)
    }
}
