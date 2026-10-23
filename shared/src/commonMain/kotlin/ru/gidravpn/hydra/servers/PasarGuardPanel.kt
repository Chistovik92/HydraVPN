package ru.gidravpn.hydra.servers

import org.json.JSONArray
import org.json.JSONObject

/**
 * Панель PasarGuard (наследник Marzban) — 0.8.2-a3. Ставится официальными скриптами закреплённого выпуска
 * (`PasarGuard/scripts`): панель и нода — контейнеры Docker. Без сертификата панель слушает только `localhost:8000`
 * (так её делает сам установщик) — в Hydra она сразу «закрыта извне»: сайт открывается через SSH-туннель, API — `curl` на
 * сервере. Порядок проверен вживую на стенде 16.10.2026 (PasarGuard v5.4.1, нода с Xray 26.3.27):
 * Docker → `pasarguard.sh install --no-ssl` → `pg-node.sh install --api-key … -y` → владелец по одноразовому ключу
 * (`pasarguard-cli generate-temp-key` → `POST /api/setup/owner`) → вход (`POST /api/admin/token`, JWT) → нода (`POST /api/node`).
 */
object PasarGuardPanel {
    const val SCRIPTS_TAG = "v0.1.10"
    const val DIR = "/opt/pasarguard"
    const val NODE_DIR = "/opt/pg-node"
    const val DATA = "/var/lib/pasarguard"
    const val NODE_DATA = "/var/lib/pg-node"
    const val PORT = 8000
    const val NODE_CERT = "$NODE_DATA/certs/ssl_cert.pem"
    val MARK = "${ServerOps.STATE_DIR}/pasarguard.installed"
    private const val PANEL_SH = "/root/.hydra-pasarguard.sh"
    private const val NODE_SH = "/root/.hydra-pg-node.sh"

    fun scriptUrl(name: String) = "https://raw.githubusercontent.com/PasarGuard/scripts/$SCRIPTS_TAG/$name"

    /** Шаги установки. [user]/[pass] — владелец панели, [nodeKey] — API-ключ ноды (UUID); оба придумывает Hydra. */
    fun installSteps(nodeKey: String): List<Step> = listOf(
        Step("pg-exists", "Проверить, что PasarGuard на сервере ещё нет", "test ! -e $DIR && test ! -e $NODE_DIR",
            failHint = "На сервере уже стоит PasarGuard — удалите её или подключитесь к ней на сайте"),
        Step("pg-docker", "Поставить Docker, если его нет (PasarGuard работает в контейнерах)",
            "(command -v docker >/dev/null || (curl -fsSL -m 180 https://get.docker.com | sh) >/tmp/.hydra-docker.log 2>&1) && systemctl enable --now docker >/dev/null 2>&1; docker --version",
            failHint = "Docker поставить не удалось"),
        Step("pg-fetch", "Загрузить установщики PasarGuard (закреплённый выпуск $SCRIPTS_TAG)",
            "curl -fsSL -m 180 ${Installer.shq(scriptUrl("pasarguard.sh"))} -o $PANEL_SH && curl -fsSL -m 180 ${Installer.shq(scriptUrl("pg-node.sh"))} -o $NODE_SH && " +
                "chmod 700 $PANEL_SH $NODE_SH && sha256sum $PANEL_SH $NODE_SH" +
                listOf(ScriptPins.verify("PasarGuard/scripts", SCRIPTS_TAG, "pasarguard.sh", PANEL_SH), ScriptPins.verify("PasarGuard/scripts", SCRIPTS_TAG, "pg-node.sh", NODE_SH))
                    .filter { it.isNotEmpty() }.joinToString("") { " && $it" },
            rollback = "rm -f $PANEL_SH $NODE_SH", failHint = "Не удалось скачать установщики с GitHub или их SHA-256 не совпал с проверенным"),
        // установщик спрашивает «ставить ноду?» (нет — ставим отдельно) и в конце бесконечно показывает журнал: ждём, пока
        // панель ответит, и останавливаем его
        Step("pg-install", "Установить панель PasarGuard (Docker; без сертификата — только для самого сервера)",
            // своя группа процессов (setsid): по её номеру останавливаем установщик и его «logs -f», не задевая сам шаг
            // (pkill -f по пути установщика убивал и этот шаг — путь есть в его же командной строке)
            "setsid sh -c \"printf 'n\\\\n' | bash $PANEL_SH install --no-ssl\" > /tmp/.hydra-pg.log 2>&1 < /dev/null & pid=\$!; ok=0; for i in \$(seq 1 180); do " +
                "c=\$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:$PORT/api/admin/token -X POST); " +
                "case \$c in 4*|2*) ok=1; break;; esac; sleep 5; done; kill -TERM -- -\$pid 2>/dev/null; sleep 1; kill -KILL -- -\$pid 2>/dev/null; " +
                "if [ \$ok = 1 ]; then echo 'панель отвечает'; else tail -n 6 /tmp/.hydra-pg.log; fi; rm -f /tmp/.hydra-pg.log; [ \$ok = 1 ]",
            rollback = uninstallCommand(), failHint = "Панель PasarGuard не запустилась"),
        Step("pg-node", "Установить ноду PasarGuard (ядро Xray) на этом же сервере",
            "bash $NODE_SH install --api-key ${Installer.shq(nodeKey)} -y < /dev/null > /tmp/.hydra-pgn.log 2>&1; rc=\$?; " +
                "if [ \$rc -ne 0 ]; then tail -n 6 /tmp/.hydra-pgn.log | sed -E 's/[0-9a-f]{8}-[0-9a-f-]{27}/***/g'; fi; rm -f /tmp/.hydra-pgn.log; test -s $NODE_CERT",
            failHint = "Нода PasarGuard не установилась"),
        Step("pg-mark", "Отметить, что PasarGuard поставила Hydra",
            "mkdir -p ${ServerOps.STATE_DIR} && chmod 700 ${ServerOps.STATE_DIR} && touch $MARK && rm -f $PANEL_SH $NODE_SH"),
    )

    /** Шаги после установки — только для показа в плане (их выполняет [MyServerService.deployPasarGuard] через API). */
    fun setupPreviewSteps(): List<Step> = listOf(
        Step("pg-owner", "Создать владельца панели (логин и пароль придумывает Hydra)"),
        Step("pg-node-add", "Подключить ноду к панели"),
        Step("panel-state", "Сохранить копию данных панели на сервере (только для root)"),
    )

    /** Порты подключений, открытые Hydra для PasarGuard (по строке «порт/протокол»), — чтобы закрыть их при удалении. */
    val PORTS = "${ServerOps.STATE_DIR}/pasarguard.ports"

    fun closePortsCommand(file: String = PORTS): String = "if [ -f $file ]; then while read -r p; do ${ServerOps.closePortCommand("\$p").replace("'", "\"")}; done < $file; rm -f $file; fi; true"

    /** Удаление панели и ноды (копия базы — до этого, шагом [ServerOps.backupPanelsStep]); Docker остаётся. */
    /**
     * Скрипт ноды v0.1.10 ставит её нативной службой systemd `pg-node-service` (бинарь node-serviced), а не контейнером: после
     * «удаления» она оставалась работать на порту 3000 и мешала установке других панелей (0.8.2-rc2).
     */
    const val NODE_SERVICE_REMOVE = "systemctl disable --now pg-node-service >/dev/null 2>&1; " +
        "rm -f /etc/systemd/system/pg-node-service.service /usr/local/bin/pg-node-service; systemctl daemon-reload >/dev/null 2>&1; "

    fun uninstallCommand(): String = NODE_SERVICE_REMOVE +
        "(cd $DIR 2>/dev/null && docker compose -p pasarguard down -v >/dev/null 2>&1); (cd $NODE_DIR 2>/dev/null && docker compose down -v >/dev/null 2>&1); " +
            "rm -rf $DIR $NODE_DIR $DATA $NODE_DATA /usr/local/bin/pasarguard /usr/local/bin/pg-node; rm -f $MARK ${OtherPanels.PANEL_STATE}; true"

    /** Одноразовый ключ владельца из CLI панели (в контейнере). */
    fun tempKeyCommand() = "cd $DIR && docker compose -p pasarguard exec -T pasarguard pasarguard-cli generate-temp-key 2>&1 | sed -n 's/.*Temp key:[^A-Za-z0-9]*\\([A-Za-z0-9_-]\\{8,\\}\\).*/\\1/p' | head -1"

    /** Пароль владельца: PasarGuard требует не меньше двух цифр и спецсимвол (проверено на v5.4.1). */
    fun newPassword(r: java.security.SecureRandom = java.security.SecureRandom()): String =
        XuiPanel.random(14, r) + "-" + r.nextInt(10) + r.nextInt(10) + "_Kq"

    /** REALITY-ключ Xray в виде base64url без «=» (так его читает Xray), из ключа X25519 Hydra. */
    fun xrayKey(b64: String) = b64.replace('+', '-').replace('/', '_').trimEnd('=')

    /**
     * Инбаунд для ядра PasarGuard (чистый Xray): из шаблона Hydra ([XuiConnection]) — без полей, которые нужны только
     * 3x-ui. Пользователей в инбаунде нет: PasarGuard подставляет их сама из групп.
     */
    fun xrayInbound(type: XuiConnection, tag: String, port: Int, priv: String, pub: String, mask: XuiPanel.RealityMask): JSONObject {
        require(type in TYPES) { "${type.title} в PasarGuard из Hydra пока не создаётся" }
        val ib = XuiConnection.inbound(type, port, tag, mask, xrayKey(priv), xrayKey(pub))
        val st = ib.optJSONObject("streamSettings") ?: JSONObject()
        st.remove("externalProxy")
        st.optJSONObject("realitySettings")?.let { r -> r.remove("settings"); r.remove("mldsa65Seed"); r.remove("minClientVer"); r.remove("maxClientVer") }
        val settings = ib.getJSONObject("settings").put("clients", JSONArray())
        return JSONObject().put("tag", tag).put("listen", "0.0.0.0").put("port", port).put("protocol", ib.getString("protocol"))
            .put("settings", settings).put("streamSettings", st)
            .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray(listOf("http", "tls", "quic"))))
    }

    /** Что Hydra создаёт в PasarGuard (ядро Xray ноды, без сертификата). */
    val TYPES = listOf(XuiConnection.VLESS_TCP_REALITY, XuiConnection.VLESS_GRPC_REALITY, XuiConnection.VLESS_XHTTP_REALITY,
        XuiConnection.TROJAN_REALITY, XuiConnection.VMESS_WS)
}

/** API PasarGuard по SSH (`curl` на сервере): вход логином и паролем владельца, дальше — JWT. Тела — файлами 0600. */
class PasarGuardApi(private val ssh: SshSession, private val user: String, private val pass: String) {
    private var token = ""
    private val base = "http://127.0.0.1:${PasarGuardPanel.PORT}"

    private fun exec(args: String, body: String?): Pair<Int, String> {
        val f = body?.let { "/tmp/.hydra-pg-body-${XuiPanel.random(6).lowercase()}".also { p -> ssh.upload(it.toByteArray(), p, 384) } }
        try {
            val r = ssh.exec("curl -sS -m 30 -w '\\n%{http_code}' $args${f?.let { " --data-binary @$it" } ?: ""}")
            val code = r.out.trimEnd().substringAfterLast('\n').trim().toIntOrNull() ?: 0
            return code to r.out.trimEnd().substringBeforeLast('\n')
        } finally { f?.let { ssh.exec("rm -f $it") } }
    }

    fun login() {
        val (c, out) = exec("-X POST -H 'Content-Type: application/x-www-form-urlencoded' $base/api/admin/token",
            "username=${java.net.URLEncoder.encode(user, "UTF-8")}&password=${java.net.URLEncoder.encode(pass, "UTF-8")}")
        token = runCatching { JSONObject(out).getString("access_token") }.getOrNull() ?: throw XuiApiException("PasarGuard не пустила владельца (HTTP $c)")
    }

    /** Запрос API; ответ — JSON (объект или массив) либо пустая строка. */
    fun call(method: String, path: String, body: Any? = null): Any? {
        if (token.isBlank()) login()
        // токен — файлом `curl -K`, а не в аргументах (видны в `ps`)
        val (c, out) = CurlAuth.withConfig(ssh, token) { cfg -> exec("-X $method -K $cfg -H 'Content-Type: application/json' ${Installer.shq(base + path)}", body?.toString()) }
        if (c !in 200..299) throw XuiApiException("PasarGuard: HTTP $c ${out.take(200)}")
        val t = out.trim()
        return when { t.startsWith("{") -> JSONObject(t); t.startsWith("[") -> JSONArray(t); else -> t }
    }

    /** Создать владельца по одноразовому ключу ([PasarGuardPanel.tempKeyCommand]). */
    fun createOwner(key: String) {
        val (c, out) = exec("-X POST -H 'Content-Type: application/json' $base/api/setup/owner",
            JSONObject().put("key", key).put("username", user).put("password", pass).toString())
        if (c !in 200..299) throw XuiApiException("PasarGuard не создала владельца (HTTP $c ${out.take(160)})")
    }

    /** Версия панели (`/api/system`). */
    fun version(): String = runCatching { (call("GET", "/api/system") as JSONObject).optString("version") }.getOrDefault("")

    /** Зарегистрировать ноду на этом же сервере (gRPC 62050, API 62051; [ca] — её сертификат). */
    fun addLocalNode(apiKey: String, ca: String) {
        require(ca.contains("BEGIN CERTIFICATE")) { "Нет сертификата ноды" }
        call("POST", "/api/node", JSONObject().put("name", "Hydra").put("address", "127.0.0.1").put("port", 62050).put("api_port", 62051)
            .put("connection_type", "grpc").put("server_ca", ca).put("keep_alive", 60).put("core_config_id", 1).put("api_key", apiKey))
    }

    /** Подождать, пока нода подключится (до ~60 с). */
    fun waitNodeConnected(): Boolean {
        repeat(20) {
            val nodes = runCatching { call("GET", "/api/nodes") }.getOrNull()
            val arr = (nodes as? JSONArray) ?: (nodes as? JSONObject)?.optJSONArray("nodes")
            if (arr != null && (0 until arr.length()).any { arr.getJSONObject(it).optString("status") == "connected" }) return true
            Thread.sleep(3000)
        }
        return false
    }

    /**
     * Создать подключение (0.8.2-a3): инбаунд — в ядро Xray ноды (`PUT /api/core/1`), хост с адресом сервера, группа «hydra» с
     * этим инбаундом и пользователь «hydra» в группе; учётные данные пользователю выдаёт сама панель. Возвращает ссылки
     * пользователя на этом порту (подписка — с самого сервера: панель без сертификата наружу не видна).
     */
    fun createConnection(type: XuiConnection, port: Int, host: String, mask: XuiPanel.RealityMask): List<String> {
        val core = call("GET", "/api/core/1") as JSONObject
        val cfg = core.getJSONObject("config")
        val inbounds = cfg.optJSONArray("inbounds") ?: JSONArray().also { cfg.put("inbounds", it) }
        if ((0 until inbounds.length()).any { inbounds.getJSONObject(it).optInt("port") == port })
            throw XuiApiException("Порт $port уже занят другим подключением панели — выберите другой")
        val tag = "Hydra ${type.title} $port"
        val kp = if (type.reality) X25519.generate() else null
        inbounds.put(PasarGuardPanel.xrayInbound(type, tag, port, kp?.privateKey.orEmpty(), kp?.publicKey.orEmpty(), mask))
        call("PUT", "/api/core/1?restart_nodes=true", JSONObject().put("name", core.optString("name", "Default Core Config")).put("config", cfg).put("type", core.optString("type", "xray"))
            .put("exclude_inbound_tags", core.optJSONArray("exclude_inbound_tags") ?: JSONArray()).put("fallbacks_inbound_tags", core.optJSONArray("fallbacks_inbound_tags") ?: JSONArray()))
        call("POST", "/api/host/", JSONObject().put("remark", "Hydra ${type.title} {USERNAME}").put("address", JSONArray(listOf(host))).put("inbound_tag", tag).put("priority", 0))
        // группа «hydra»: все инбаунды Hydra
        val groups = (call("GET", "/api/groups") as? JSONObject)?.optJSONArray("groups") ?: JSONArray()
        val g = (0 until groups.length()).map { groups.getJSONObject(it) }.firstOrNull { it.optString("name") == GROUP }
        val gid = if (g == null) (call("POST", "/api/group", JSONObject().put("name", GROUP).put("inbound_tags", JSONArray(listOf(tag)))) as JSONObject).getInt("id")
        else {
            val tags = g.optJSONArray("inbound_tags") ?: JSONArray()
            call("PUT", "/api/group/${g.getInt("id")}", JSONObject().put("name", GROUP).put("inbound_tags", tags.put(tag)).put("is_disabled", false))
            g.getInt("id")
        }
        val user = runCatching { call("GET", "/api/user/$USER") as JSONObject }.getOrNull()
        val u = if (user == null) call("POST", "/api/user", JSONObject().put("username", USER).put("group_ids", JSONArray(listOf(gid))).put("data_limit", 0)
            .put("expire", JSONObject.NULL).put("status", "active").put("proxy_settings", JSONObject())) as JSONObject
        else {
            val ids = user.optJSONArray("group_ids") ?: JSONArray()
            if ((0 until ids.length()).none { ids.optInt(it) == gid }) call("PUT", "/api/user/$USER", JSONObject().put("group_ids", ids.put(gid)))
            call("GET", "/api/user/$USER") as JSONObject
        }
        val sub = u.optString("subscription_url")
        if (sub.isBlank()) throw XuiApiException("PasarGuard не выдала подписку пользователя")
        var links = emptyList<String>()
        repeat(5) { links = subscriptionLinks(sub).filter { l -> OtherPanelConnections.linkHasPort(l, port) }
            if (links.isNotEmpty()) return links; Thread.sleep(2000) }
        return links
    }

    companion object { const val GROUP = "hydra"; const val USER = "hydra" }

    /** Подписка пользователя, раскрытая из base64 (с самого сервера). */
    fun subscriptionLinks(subPath: String): List<String> {
        val p = if (subPath.startsWith("http")) java.net.URL(subPath).path else subPath
        val r = ssh.exec("curl -sS -m 20 -H 'Accept: */*' ${Installer.shq(base + p)}")
        val body = r.out.trim()
        val text = runCatching { String(java.util.Base64.getMimeDecoder().decode(body)) }.getOrDefault(body)
        return text.lines().map { it.trim() }.filter { "://" in it }
    }
}
