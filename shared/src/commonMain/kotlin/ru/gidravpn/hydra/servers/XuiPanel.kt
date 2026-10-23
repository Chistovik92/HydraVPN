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
    /** s-ui (alireza0, на sing-box) — 0.8.1: установка и вход на сайт; пользователей заводят на сайте. */
    SUI("s-ui", "alireza0/s-ui"),
    /** x-ui (alireza0) — 0.8.1: так же, как s-ui. */
    XUI("x-ui", "alireza0/x-ui"),
    /** PasarGuard (наследник Marzban, Docker) — 0.8.2-a3: установка, вход на сайт, подключения ([PasarGuardPanel]). */
    PASARGUARD("PasarGuard", "PasarGuard/panel"),
    /** Remnawave (Docker, за прокси Caddy на 127.0.0.1) — 0.8.2-a3 ([RemnawavePanel]). */
    REMNAWAVE("Remnawave", "remnawave/panel");

    /** Панель в Docker, видна только с самого сервера: сайт — через туннель Hydra, без сертификата на IP и «открыть извне». */
    val tunnelOnly: Boolean get() = this == PASARGUARD || this == REMNAWAVE
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
    /**
     * Порт панели закрыт извне (0.8.1-rc2): панель слушает только 127.0.0.1 сервера; сайт открывается через SSH-туннель
     * приложения, API 3x-ui — `curl` на сервере по SSH. [baseUrl] остаётся внешним адресом (на него выписан сертификат).
     */
    val local: Boolean = false,
    /** Ноды панели на других серверах (0.8.2-a4). */
    val nodes: List<PanelNode> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("baseUrl", baseUrl).put("user", username).put("password", password)
        .put("token", token).put("version", version).put("inboundId", inboundId).put("client", clientEmail).put("local", local)
        .also { if (nodes.isNotEmpty()) it.put("nodes", PanelNode.listToJson(nodes)) }

    companion object {
        fun fromJson(o: JSONObject) = PanelInstance(
            runCatching { PanelKind.valueOf(o.optString("kind", "XUI3")) }.getOrDefault(PanelKind.XUI3),
            o.getString("baseUrl"), o.optString("user"), o.optString("password"), o.getString("token"),
            o.optString("version"), o.optInt("inboundId"), o.optString("client"), o.optBoolean("local"),
            PanelNode.listFromJson(o.optJSONArray("nodes")),
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
        c.instanceFollowRedirects = false   // токен администратора не должен уходить на другой адрес по редиректу
        c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        if (code !in 200..299) throw XuiApiException("HTTP $code ${text.take(200)}")
        return text
    }
}

/**
 * Вызовы идут `curl`-ом на сервере: [localBase] — `http(s)://127.0.0.1:порт/путь`, [hostHeader] — внешний адрес для ссылок клиентов.
 * `-k`: с HTTPS (0.8.1-rc1) сертификат выписан на внешний IP, а запрос идёт на 127.0.0.1 — проверять его здесь не с чем.
 */
class SshXuiTransport(private val ssh: SshSession, private val localBase: String, private val token: String, private val hostHeader: String) : XuiTransport {
    override fun call(method: String, path: String, body: String?): String {
        val bodyFile = "/tmp/.hydra-xui-body.json"
        val hdr = "-H ${Installer.shq("Authorization: Bearer $token")} -H ${Installer.shq("Host: $hostHeader")}"
        val cmd = if (body != null) {
            ssh.upload(body.toByteArray(), bodyFile, 384)
            "curl -ksS -m 25 -X $method $hdr -H 'Content-Type: application/json' --data @$bodyFile ${Installer.shq(localBase.trimEnd('/') + path)}; rm -f $bodyFile"
        } else "curl -ksS -m 25 -X $method $hdr ${Installer.shq(localBase.trimEnd('/') + path)}"
        val r = ssh.exec(cmd)
        if (r.out.isBlank()) throw XuiApiException("Панель не ответила (curl код ${r.exit})")
        return r.out
    }
}

/** Клиент API 3x-ui (пути `/panel/api/…`). Ответ панели всегда `{success, msg, obj}`. */
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

    /** Клиенты всех инбаундов (как отдаёт панель: `email`, `subId`, …). */
    fun clients(): List<JSONObject> {
        val a = obj(t.call("GET", "/panel/api/clients/list")) as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    /** `subId` клиента по `email` — для адреса подписки. */
    fun subIdOf(email: String): String? = clients().firstOrNull { it.optString("email") == email }?.optString("subId")?.takeIf { it.isNotBlank() }

    /**
     * Адрес подписки клиента (0.8.1-rc1): подписка обновляется сама, и правки инбаунда на сайте панели доходят до приложения.
     * null — подписка в панели выключена или у клиента нет `subId`.
     */
    fun subscriptionUrl(host: String, subId: String): String? = XuiPanel.subscriptionUrl(settings(), host, subId)

    /**
     * Подключить уже существующего пользователя к другому инбаунду (0.8.2-a2, `POST /panel/api/clients/{email}/attach`): его
     * подписка сама получает новое подключение, учётные данные под протокол панель заполняет сама.
     */
    fun attachClient(email: String, inboundIds: List<Int>) {
        obj(t.call("POST", "/panel/api/clients/${java.net.URLEncoder.encode(email, "UTF-8")}/attach", JSONObject().put("inboundIds", JSONArray(inboundIds)).toString()))
    }

    fun delClient(email: String) { obj(t.call("POST", "/panel/api/clients/del/${java.net.URLEncoder.encode(email, "UTF-8")}")) }

    /** Готовые ссылки (`vless://…`) клиента по всем его инбаундам. */
    fun links(email: String): List<String> {
        val a = obj(t.call("GET", "/panel/api/clients/links/${java.net.URLEncoder.encode(email, "UTF-8")}")) as? JSONArray ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }

    /** Список клиентов (`email`) всех инбаундов. */
    fun clientEmails(): List<String> = clients().mapNotNull { it.optString("email").takeIf { e -> e.isNotBlank() } }
}

object XuiPanel {
    const val SCRIPT = "/root/.hydra-xui-install.sh"
    const val ENV_FILE = "/tmp/.hydra-xui.env"
    const val LOG = "/tmp/.hydra-xui-install.log"
    const val RESULT = "/etc/x-ui/install-result.env"

    fun scriptUrl(tag: String) = "https://raw.githubusercontent.com/MHSanaei/3x-ui/$tag/install.sh"

    /**
     * Тег, который ставит Hydra (0.8.2-rc2): проверенный выпуск из [ScriptPins], а не «последний» у апстрима — скрипт этого тега
     * сверяется по SHA-256, а выпуск прошёл живой прогон на стенде. Новые выпуски 3x-ui Hydra ставит после проверки (в новой версии Hydra).
     * Имя прежнее, параметр [timeoutMs] оставлен для совместимости: запроса к GitHub больше нет.
     */
    @Suppress("UNUSED_PARAMETER")
    fun latestTag(timeoutMs: Int = 15_000): String = ScriptPins.XUI3_TAG

    /**
     * Адрес панели из того, что вставил пользователь: без схемы — `http://`, без завершающего слеша, без пробелов.
     * null — это не адрес (пусто, нет хоста). Схема `http` пропускается: многие панели стоят без сертификата — экран предупреждает.
     */
    fun normalizeUrl(raw: String): String? {
        val t = raw.trim().trimEnd('/')
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val withScheme = if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "http://$t"
        val u = runCatching { URL(withScheme) }.getOrNull() ?: return null
        return withScheme.takeIf { u.host.isNotBlank() }
    }

    fun isHttps(url: String) = url.startsWith("https://", true)

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
        /** HTTPS панели и подписки сертификатом Let's Encrypt на IP (0.8.1-rc1): `XUI_SSL_MODE=ip`, нужен открытый 80/tcp. */
        val sslIp: Boolean = false,
        /** Сервер добавлен по имени: сертификат выписывается на домен (`XUI_SSL_MODE=domain`), а не на IP. */
        val domain: String = "",
    )

    /** Шаги установки. Переменные окружения идут файлом 0600 (не в аргументах — не видны в `ps`). */
    fun installSteps(p: InstallParams, openFirewall: Boolean, firewall: String): List<Step> = buildList {
        require(validTag(p.tag)) { "Неверный тег ${p.tag}" }
        add(Step("xui-exists", "Проверить, что 3x-ui на сервере ещё нет", "test ! -e /usr/local/x-ui/x-ui",
            failHint = "На сервере уже стоит 3x-ui — подключите существующую панель по адресу и токену"))
        add(Step("xui-fetch", "Загрузить установщик 3x-ui ${p.tag} (закреплённый тег)",
            "(curl -fsSL -m 180 ${Installer.shq(scriptUrl(p.tag))} -o $SCRIPT || wget -q -O $SCRIPT ${Installer.shq(scriptUrl(p.tag))}) && chmod 700 $SCRIPT && sha256sum $SCRIPT" +
                ScriptPins.verify("MHSanaei/3x-ui", p.tag, "install.sh", SCRIPT).let { if (it.isEmpty()) "" else " && $it" },
            rollback = "rm -f $SCRIPT", failHint = "Не удалось скачать установщик с GitHub или его SHA-256 не совпал с проверенным"))
        add(Step("xui-env", "Записать параметры установки (файл 0600; логин и пароль не показываются)",
            upload = Step.Upload(ENV_FILE, ("XUI_NONINTERACTIVE=1\nXUI_USERNAME=${p.username}\nXUI_PASSWORD=${p.password}\nXUI_PANEL_PORT=${p.port}\n" +
                "XUI_WEB_BASE_PATH=${p.webPath}\nXUI_SSL_MODE=${when { !p.sslIp -> "none"; p.domain.isNotBlank() -> "domain\nXUI_DOMAIN=${p.domain}"; else -> "ip" }}\nXUI_ENABLE_FAIL2BAN=false\n").toByteArray(), 384)))
        // сертификат на IP проверяется по 80/tcp — при выдаче и при каждом продлении (раз в ~6 дней), поэтому порт остаётся открытым
        if (p.sslIp && openFirewall) ServerOps.openPortStep(firewall, "80/tcp")?.let { add(Step("xui-fw80", "Открыть 80/tcp для выдачи и продления сертификата", it.command, rollback = it.rollback)) }
        add(Step("xui-install", "Установить 3x-ui ${p.tag}: ядро Xray, служба x-ui, панель на порту ${p.port}",
            // Журнал установщика содержит логин, пароль и токен: при успехе его не читаем, при сбое показываем хвост с вырезанием; файл удаляется всегда.
            "set -a; . $ENV_FILE; set +a; bash $SCRIPT ${p.tag} > $LOG 2>&1; rc=\$?; rm -f $ENV_FILE; " +
                "if [ \$rc -ne 0 ]; then tail -n 6 $LOG | sed -E 's/(assword|sername|oken)[^:=]*[:=].*/\\1: ***/'; fi; rm -f $LOG; exit \$rc",
            rollback = "printf 'y\\n' | /usr/bin/x-ui uninstall >/dev/null 2>&1; rm -f $ENV_FILE", failHint = "Установка 3x-ui не удалась"))
        if (openFirewall) ServerOps.openPortStep(firewall, "${p.port}/tcp")?.let { add(Step("xui-fw", "Открыть порт панели ${p.port}/tcp", it.command, rollback = it.rollback)) }
        if (openFirewall) ServerOps.openPortStep(firewall, "$SUB_PORT/tcp")?.let { add(Step("xui-fw-sub", "Открыть порт подписки $SUB_PORT/tcp", it.command, rollback = it.rollback)) }
        add(Step("xui-active", "Проверить: служба x-ui запущена", "systemctl is-active x-ui",
            accept = { it.out.lineSequence().any { l -> l.trim() == "active" } }, failHint = "Служба x-ui не активна"))
        // метка «поставила Hydra»: полная очистка сервера снимает только такую панель
        add(Step("xui-mark", "Отметить, что панель поставила Hydra", "mkdir -p ${ServerOps.STATE_DIR} && chmod 700 ${ServerOps.STATE_DIR} && touch ${ServerOps.XUI_MARK}"))
    }

    /** Читает `/etc/x-ui/install-result.env` (`KEY=value`, значения в `printf %q` — без пробелов у наших случайных значений). */
    fun parseResult(text: String): Map<String, String> = text.lineSequence().mapNotNull { l ->
        val i = l.indexOf('='); if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim().trim('\'', '"').replace("\\", "")
    }.toMap()

    /** Порт подписки 3x-ui по умолчанию (`subPort`). */
    const val SUB_PORT = 2096

    /** Каталог сертификата на IP (так его кладёт установщик 3x-ui). */
    const val CERT_DIR = "/root/cert/ip"

    /**
     * Адрес подписки по настройкам панели (`/panel/api/setting/all`): `subURI`, если задан на сайте, иначе схема (https — когда у
     * подписки есть сертификат), `subDomain` или адрес сервера, `subPort`, `subPath`.
     */
    fun subscriptionUrl(s: JSONObject, host: String, subId: String): String? {
        if (!s.optBoolean("subEnable", true) || subId.isBlank()) return null
        s.optString("subURI").trim().takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') + "/" + subId }
        val tls = s.optString("subCertFile").isNotBlank() && s.optString("subKeyFile").isNotBlank()
        val port = s.optInt("subPort", SUB_PORT)
        val h = s.optString("subDomain").trim().ifBlank { host }.let { if (':' in it && !it.startsWith("[")) "[$it]" else it }
        val path = "/" + s.optString("subPath", "/sub/").trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val p = if ((tls && port == 443) || (!tls && port == 80)) "" else ":$port"
        return "${if (tls) "https" else "http"}://$h$p$path$subId"
    }

    /**
     * Маскировка Reality (0.8.1-rc1, обход блокировок в РФ). Reality выдаёт себя за [sni]: для ТСПУ соединение выглядит как HTTPS к
     * этому сайту. С февраля 2026 ТСПУ сверяет SNI с сетью сервера, а мобильные операторы при отключениях пускают только «белый список» —
     * поэтому выбор оставлен пользователю.
     */
    enum class RealityMask(val target: String, val sni: String) {
        /** Российский сайт из белых списков: проходит при отключениях мобильного интернета (выбор владельца 15.10.2026). */
        VK("vk.com:443", "vk.com"),
        /** Ещё один сайт из белых списков. */
        YANDEX("ya.ru:443", "ya.ru"),
        /** Зарубежный сайт без своих узлов в РФ — SNI не спорит с зарубежным IP сервера. */
        GITHUB("github.com:443", "github.com"),
    }

    /** Транспорт инбаунда Hydra (0.8.1-rc1). */
    enum class Transport(val title: String) {
        /** gRPC + Reality — по умолчанию (решение владельца 15.10.2026: подключение «Vless grpc» у него в РФ работает). */
        GRPC("gRPC"),
        /** XHTTP + Reality — запасной: поток режется на HTTP-запросы с паддингом. */
        XHTTP("XHTTP"),
    }

    /**
     * Инбаунд по умолчанию (0.8.1-rc1): **VLESS + gRPC + Reality** (или XHTTP). Прежний TCP + Reality с маскировкой под
     * www.cloudflare.com на сервере владельца в РФ отдавал 1–2 КБ и замирал. `flow` (Vision) у gRPC и XHTTP не используется.
     * Ключи — от панели ([XuiApi.newRealityKeys]).
     */
    fun defaultInbound(port: Int, priv: String, pub: String, mask: RealityMask = RealityMask.VK, remark: String = "Hydra",
                       rnd: SecureRandom = SecureRandom(), transport: Transport = Transport.GRPC): JSONObject {
        val shortId = ByteArray(8).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val settings = JSONObject().put("clients", JSONArray()).put("decryption", "none").put("encryption", "none").put("fallbacks", JSONArray())
        val reality = JSONObject().put("show", false).put("xver", 0).put("target", mask.target).put("serverNames", JSONArray(listOf(mask.sni)))
            .put("privateKey", priv).put("minClientVer", "").put("maxClientVer", "").put("maxTimediff", 0).put("shortIds", JSONArray(listOf(shortId)))
            .put("mldsa65Seed", "")
            .put("settings", JSONObject().put("publicKey", pub).put("fingerprint", "chrome").put("serverName", "").put("spiderX", "/").put("mldsa65Verify", ""))
        val xhttp = JSONObject().put("path", "/" + random(10, rnd).lowercase()).put("host", "").put("mode", "auto")
            .put("xPaddingBytes", "100-1000").put("noSSEHeader", false).put("scMaxBufferedPosts", 30).put("scStreamUpServerSecs", "20-80")
            .put("headers", JSONObject())
        val stream = JSONObject().put("security", "reality").put("externalProxy", JSONArray()).put("realitySettings", reality)
        when (transport) {
            Transport.GRPC -> stream.put("network", "grpc")
                .put("grpcSettings", JSONObject().put("serviceName", random(10, rnd).lowercase()).put("authority", "").put("multiMode", false))
            Transport.XHTTP -> stream.put("network", "xhttp").put("xhttpSettings", xhttp)
        }
        val sniffing = JSONObject().put("enabled", true).put("destOverride", JSONArray(listOf("http", "tls", "quic"))).put("metadataOnly", false).put("routeOnly", false)
        return JSONObject().put("enable", true).put("remark", remark).put("listen", "").put("port", port).put("protocol", "vless")
            .put("expiryTime", 0).put("total", 0).put("settings", settings).put("streamSettings", stream).put("sniffing", sniffing)
    }

    /**
     * Включить HTTPS у уже стоящей 3x-ui (0.8.1-rc1): сертификат Let's Encrypt на IP (профиль shortlived, ~6 дней, продлевается
     * сам по cron acme.sh) или на домен, если сервер добавлен по имени. Так же делает установщик 3x-ui (`setup_ip_certificate`):
     * acme.sh в режиме standalone на 80/tcp, файлы в [CERT_DIR], пути — командой `x-ui cert` (она ставит их и панели, и подписке).
     */
    fun httpsSteps(host: String, openFirewall: Boolean, firewall: String): List<Step> =
        certIssueSteps(host, openFirewall, firewall, "x-ui") + certApplySteps(PanelKind.XUI3)

    /**
     * HTTPS для панели любого вида (0.8.1-rc2): общий выпуск сертификата ([certIssueSteps]) и установка его панели
     * ([certApplySteps]). [port]/[path] — адрес панели на сервере: после перезапуска проверяется, что она отвечает по HTTPS.
     */
    fun httpsSteps(kind: PanelKind, host: String, openFirewall: Boolean, firewall: String, port: Int = 0, path: String = ""): List<Step> =
        certIssueSteps(host, openFirewall, firewall, OtherPanels.serviceOf(kind)) + certApplySteps(kind, port, path)

    /** Выпуск сертификата acme.sh в [CERT_DIR]; продление перезапускает службу [service]. */
    fun certIssueSteps(host: String, openFirewall: Boolean, firewall: String, service: String): List<Step> = buildList {
        val ip = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host)
        require(ip || Regex("""^[A-Za-z0-9.-]+$""").matches(host)) { "Неверный адрес $host" }
        require(Regex("^[a-z-]+$").matches(service)) { "Неверная служба $service" }
        val h = Installer.shq(host)
        val acme = "~/.acme.sh/acme.sh"
        if (openFirewall) ServerOps.openPortStep(firewall, "80/tcp")?.let { add(Step("cert-fw80", "Открыть 80/tcp для выдачи и продления сертификата", it.command, rollback = it.rollback)) }
        add(Step("cert-port80", "Проверить, что порт 80 свободен (его на время проверки займёт acme.sh)",
            "! (ss -ltnH 2>/dev/null | awk '{print \$4}' | grep -Eq '(^|:)80\$')",
            failHint = "Порт 80 занят другой программой (веб-сервер?) — сертификат так не выдать"))
        add(Step("cert-acme", "Поставить acme.sh и socat, если их нет",
            "(command -v socat >/dev/null || (DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=120 install -y -q socat || dnf install -y socat || yum install -y socat) >/dev/null 2>&1) && " +
                "([ -x $acme ] || curl -fsSL -m 180 https://get.acme.sh | sh >/dev/null 2>&1) && [ -x $acme ]",
            failHint = "Не удалось поставить acme.sh"))
        add(Step("cert-issue", "Получить сертификат Let's Encrypt для $host" + if (ip) " (на IP, 6 дней, продлевается сам)" else "",
            "$acme --set-default-ca --server letsencrypt >/dev/null && $acme --issue -d $h --standalone --server letsencrypt --httpport 80 --force" +
                (if (ip) " --certificate-profile shortlived --days 6" else "") + " > /tmp/.hydra-acme.log 2>&1; rc=\$?; tail -n 4 /tmp/.hydra-acme.log; rm -f /tmp/.hydra-acme.log; exit \$rc",
            failHint = "Let's Encrypt не смог проверить сервер — порт 80 закрыт снаружи (файрвол хостинга?)"))
        add(Step("cert-install", "Установить сертификат в $CERT_DIR и настроить его продление",
            "mkdir -p $CERT_DIR && $acme --installcert --force -d $h --key-file $CERT_DIR/privkey.pem --fullchain-file $CERT_DIR/fullchain.pem " +
                "--reloadcmd 'systemctl restart $service' >/dev/null 2>&1; chmod 600 $CERT_DIR/privkey.pem; test -s $CERT_DIR/fullchain.pem",
            failHint = "Файлы сертификата не появились"))
    }

    /**
     * Отдать панели сертификат из [CERT_DIR]: 3x-ui и x-ui — своей командой `x-ui cert` (у 3x-ui она ставит его и подписке),
     * у x-ui подписке и у s-ui (своей команды нет) — записью путей в таблицу настроек панели ([OtherPanels.settingsCommand]).
     */
    fun certApplySteps(kind: PanelKind, port: Int = 0, path: String = ""): List<Step> = buildList {
        val crt = "$CERT_DIR/fullchain.pem"; val key = "$CERT_DIR/privkey.pem"
        val svc = OtherPanels.serviceOf(kind)
        val cmd = when (kind) {
            PanelKind.XUI3 -> "/usr/local/x-ui/x-ui cert -webCert $crt -webCertKey $key >/dev/null"
            PanelKind.XUI -> "/usr/local/x-ui/x-ui cert -webCert $crt -webCertKey $key >/dev/null && " +
                OtherPanels.settingsCommand(kind, mapOf("subCertFile" to crt, "subKeyFile" to key))
            PanelKind.SUI -> OtherPanels.settingsCommand(kind, mapOf("webCertFile" to crt, "webKeyFile" to key, "subCertFile" to crt, "subKeyFile" to key))
            PanelKind.PASARGUARD, PanelKind.REMNAWAVE -> error("${kind.title} в Hydra — только через туннель, без сертификата на IP")
        }
        add(Step("cert-panel", "Включить HTTPS у панели и подписки",
            "$cmd && systemctl restart $svc && sleep 2 && systemctl is-active $svc",
            accept = { it.out.lineSequence().any { l -> l.trim() == "active" } }, failHint = "Панель не запустилась с сертификатом"))
        if (port > 0) add(OtherPanels.answersStep("cert-check", "Проверить: панель отвечает по HTTPS", "https", port, path))
    }

    /** Адрес панели на `https://` (тот же хост, порт и путь). */
    fun toHttps(url: String) = if (url.startsWith("http://", true)) "https://" + url.substring(7) else url
}
