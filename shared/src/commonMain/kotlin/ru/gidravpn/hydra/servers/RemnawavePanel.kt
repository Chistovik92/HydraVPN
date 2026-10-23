package ru.gidravpn.hydra.servers

import org.json.JSONArray
import org.json.JSONObject

/**
 * Панель Remnawave (0.8.2-a3): панель (`remnawave/backend`, PostgreSQL, Valkey) и нода (`remnawave/node`, ядро Xray) в Docker,
 * образы закреплённых версий. Сама панель пускает только запросы через обратный прокси с HTTPS (заголовки `X-Forwarded-For`
 * и `X-Forwarded-Proto: https`, иначе рвёт соединение), поэтому Hydra ставит рядом Caddy на `127.0.0.1:[WEB_PORT]`, который
 * добавляет эти заголовки: сайт панели открывается через SSH-туннель Hydra, наружу панель не видна; API — `curl` на сервере.
 */
object RemnawavePanel {
    const val TAG = "3.4.7"
    const val NODE_TAG = "3.4.3"
    const val CADDY = "caddy:2.10.0-alpine"
    const val DIR = "/opt/remnawave"
    const val NODE_DIR = "/opt/remnanode"
    const val API_PORT = 3000
    const val WEB_PORT = 3080
    const val NODE_PORT = 2222
    /** Профиль и отряд, которые панель создаёт сама при первом запуске. */
    const val DEFAULT_PROFILE = "00000000-0000-0000-0000-000000000000"
    val MARK = "${ServerOps.STATE_DIR}/remnawave.installed"
    val PORTS = "${ServerOps.STATE_DIR}/remnawave.ports"
    private const val ENV_SH = "/tmp/.hydra-rw-env.sh"

    fun composeUrl() = "https://raw.githubusercontent.com/remnawave/backend/$TAG/docker-compose-prod.yml"
    fun envUrl() = "https://raw.githubusercontent.com/remnawave/backend/$TAG/.env.sample"

    /**
     * Caddy: http на 127.0.0.1 с заголовками «за прокси по HTTPS»; CSP и HSTS панели убираем — сайт открывается по http через
     * туннель. Окно панели на ПК (JavaFX) шлёт `Upgrade: h2c`: Caddy передал бы его панели как «смену протокола», та отвечает
     * 404, и JavaFX бросает загрузку, — заголовок убираем (WebSocket не трогаем), сервер — только HTTP/1.1.
     */
    const val CADDYFILE = "{\n\tservers {\n\t\tprotocols h1\n\t}\n}\n:$WEB_PORT {\n\t@h2c header Upgrade h2c\n\trequest_header @h2c -Upgrade\n\treverse_proxy remnawave:$API_PORT {\n\t\theader_up X-Forwarded-Proto https\n\t\theader_up X-Forwarded-For {remote_host}\n" +
        "\t\theader_down -Content-Security-Policy\n\t\theader_down -Strict-Transport-Security\n\t}\n}\n"

    const val OVERRIDE = "services:\n  remnawave:\n    image: remnawave/backend:$TAG\n  hydra-caddy:\n    image: $CADDY\n    container_name: hydra-caddy\n" +
        "    restart: always\n    networks:\n      - remnawave-network\n    ports:\n      - 127.0.0.1:$WEB_PORT:$WEB_PORT\n" +
        "    volumes:\n      - ./Caddyfile:/etc/caddy/Caddyfile:ro\n    depends_on:\n      - remnawave\n"

    fun nodeCompose(secretKey: String) = "services:\n  remnanode:\n    container_name: remnanode\n    hostname: remnanode\n    image: remnawave/node:$NODE_TAG\n" +
        "    restart: always\n    network_mode: host\n    cap_add:\n      - NET_ADMIN\n    ulimits:\n      nofile:\n        soft: 1048576\n        hard: 1048576\n" +
        "    environment:\n      - NODE_PORT=$NODE_PORT\n      - SECRET_KEY=$secretKey\n"

    /** Подстановки в `.env` панели: секреты придумывает Hydra (скрипт идёт файлом 0600 — в `ps` их не видно). */
    fun envScript(host: String, r: java.security.SecureRandom = java.security.SecureRandom()): String {
        fun hex(n: Int) = ByteArray(n).also { r.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val pg = XuiPanel.random(24, r)
        val set = linkedMapOf(
            "APP_SECRET" to hex(64), "METRICS_USER" to "hy" + XuiPanel.random(6, r).lowercase(), "METRICS_PASS" to XuiPanel.random(24, r),
            "WEBHOOK_SECRET_HEADER" to XuiPanel.random(64, r), "POSTGRES_PASSWORD" to pg,
            "DATABASE_URL" to "\"postgresql://postgres:$pg@remnawave-db:5432/postgres\"",
            "PANEL_DOMAIN" to host, "FRONT_END_DOMAIN" to "*", "SUB_PUBLIC_DOMAIN" to "127.0.0.1:$WEB_PORT/api/sub",
        )
        return "set -e\ncd $DIR\n" + set.entries.joinToString("") { (k, v) -> "sed -i 's|^$k=.*|$k=$v|' .env\n" } +
            set.keys.joinToString("") { k -> "grep -q '^$k=' .env\n" }
    }

    fun installSteps(host: String): List<Step> = listOf(
        Step("rw-exists", "Проверить, что Remnawave на сервере ещё нет", "test ! -e $DIR && test ! -e $NODE_DIR",
            failHint = "На сервере уже стоит Remnawave — удалите её или подключитесь к ней на сайте"),
        Step("rw-docker", "Поставить Docker, если его нет (Remnawave работает в контейнерах)",
            "(command -v docker >/dev/null || (curl -fsSL -m 180 https://get.docker.com | sh) >/tmp/.hydra-docker.log 2>&1) && systemctl enable --now docker >/dev/null 2>&1; docker --version",
            failHint = "Docker поставить не удалось"),
        Step("rw-fetch", "Загрузить файлы Remnawave $TAG (закреплённый выпуск)",
            "mkdir -p $DIR && chmod 700 $DIR && curl -fsSL -m 180 ${Installer.shq(composeUrl())} -o $DIR/docker-compose.yml && curl -fsSL -m 180 ${Installer.shq(envUrl())} -o $DIR/.env && " +
                "chmod 600 $DIR/.env && sha256sum $DIR/docker-compose.yml", rollback = "rm -rf $DIR", failHint = "Не удалось скачать файлы с GitHub"),
        Step("rw-env", "Задать секреты панели (придумывает Hydra)", upload = Step.Upload(ENV_SH, envScript(host).toByteArray(), 384)),
        Step("rw-env-apply", "Записать настройки панели", "sh $ENV_SH; rc=\$?; rm -f $ENV_SH; exit \$rc", failHint = "Не удалось записать настройки"),
        Step("rw-caddy", "Прокси для сайта панели (только 127.0.0.1)", upload = Step.Upload("$DIR/Caddyfile", CADDYFILE.toByteArray(), 420)),
        Step("rw-override", "Закрепить версию панели и добавить прокси", upload = Step.Upload("$DIR/docker-compose.override.yml", OVERRIDE.toByteArray(), 384)),
        Step("rw-up", "Запустить панель Remnawave (Docker: панель, база, кэш, прокси)",
            "cd $DIR && docker compose up -d > /tmp/.hydra-rw.log 2>&1; rc=\$?; if [ \$rc -ne 0 ]; then tail -n 8 /tmp/.hydra-rw.log; rm -f /tmp/.hydra-rw.log; exit \$rc; fi; rm -f /tmp/.hydra-rw.log; " +
                "ok=0; for i in \$(seq 1 120); do c=\$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 ${RemnawaveApi.HEADERS} http://127.0.0.1:$API_PORT/api/auth/status); " +
                "[ \"\$c\" = 200 ] && { ok=1; break; }; sleep 5; done; if [ \$ok = 1 ]; then echo 'панель отвечает'; else docker compose logs --tail 8 remnawave 2>&1 | tail -n 8; fi; [ \$ok = 1 ]",
            rollback = uninstallCommand(), failHint = "Панель Remnawave не запустилась"),
        Step("rw-mark", "Отметить, что Remnawave поставила Hydra", "mkdir -p ${ServerOps.STATE_DIR} && chmod 700 ${ServerOps.STATE_DIR} && touch $MARK"),
    )

    /** Ноду ставим после регистрации владельца: её ключ (`SECRET_KEY`) выдаёт панель. */
    fun nodeSteps(secretKey: String): List<Step> = listOf(
        Step("rw-node-files", "Файлы ноды Remnawave", "mkdir -p $NODE_DIR && chmod 700 $NODE_DIR"),
        Step("rw-node-compose", "Настройки ноды (ключ от панели)", upload = Step.Upload("$NODE_DIR/docker-compose.yml", nodeCompose(secretKey).toByteArray(), 384)),
        Step("rw-node-up", "Запустить ноду Remnawave (ядро Xray) на этом же сервере",
            "cd $NODE_DIR && docker compose up -d > /tmp/.hydra-rwn.log 2>&1; rc=\$?; [ \$rc -ne 0 ] && tail -n 8 /tmp/.hydra-rwn.log; rm -f /tmp/.hydra-rwn.log; " +
                // панель ходит к ноде из сети Docker на адрес шлюза — при включённом ufw пропускаем только из подсетей Docker
                "if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q 'Status: active'; then ufw allow from 172.16.0.0/12 to any port $NODE_PORT proto tcp >/dev/null; fi; exit \$rc",
            failHint = "Нода Remnawave не запустилась"),
    )

    fun setupPreviewSteps(): List<Step> = listOf(
        Step("rw-owner", "Создать владельца панели (логин и пароль придумывает Hydra)"),
        Step("rw-node-up", "Установить ноду (ядро Xray) и подключить её к панели"),
        Step("panel-state", "Сохранить копию данных панели на сервере (только для root)"),
    )

    /** Копия базы (pg_dump) в [ServerOps.BACKUP_DIR] — до удаления. */
    fun backupCommand() = "mkdir -p ${ServerOps.BACKUP_DIR} && chmod 700 ${ServerOps.BACKUP_DIR}; d=\$(date +%Y%m%d-%H%M%S); " +
        // Строго: контейнер базы есть (запускаем, если остановлен), но копия не создана или пуста — код возврата не 0, и удаление не идёт дальше.
        "if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -qx remnawave-db; then docker start remnawave-db >/dev/null 2>&1; sleep 3; " +
        "if docker exec remnawave-db pg_dump -U postgres postgres > ${ServerOps.BACKUP_DIR}/remnawave-\$d.sql 2>/dev/null && [ -s ${ServerOps.BACKUP_DIR}/remnawave-\$d.sql ] && " +
        "chmod 600 ${ServerOps.BACKUP_DIR}/remnawave-\$d.sql; then echo \"копия базы remnawave: ${ServerOps.BACKUP_DIR}/remnawave-\$d.sql\"; " +
        "else rm -f ${ServerOps.BACKUP_DIR}/remnawave-\$d.sql; echo 'ОШИБКА: копия базы remnawave не создана'; false; fi; else true; fi"

    fun uninstallCommand(): String =
        "(cd $NODE_DIR 2>/dev/null && docker compose down -v >/dev/null 2>&1); (cd $DIR 2>/dev/null && docker compose down -v >/dev/null 2>&1); " +
            "if command -v ufw >/dev/null; then ufw delete allow from 172.16.0.0/12 to any port $NODE_PORT proto tcp >/dev/null 2>&1; fi; " +
            "rm -rf $DIR $NODE_DIR; rm -f $MARK ${OtherPanels.PANEL_STATE}; true"

    /** Что Hydra создаёт в Remnawave: VMess панель не принимает (проверено на 3.4.5). */
    val TYPES = listOf(XuiConnection.VLESS_TCP_REALITY, XuiConnection.VLESS_GRPC_REALITY, XuiConnection.VLESS_XHTTP_REALITY, XuiConnection.TROJAN_REALITY)

    /** Пароль владельца: не короче 24 символов, с заглавными, строчными и цифрами (требование Remnawave). */
    fun newPassword(r: java.security.SecureRandom = java.security.SecureRandom()): String =
        XuiPanel.random(24, r) + "Kq" + r.nextInt(10) + r.nextInt(10)
}

/** API Remnawave по SSH (`curl` на сервере, с заголовками «за прокси»): ответы — в поле `response`. Тела — файлами 0600. */
class RemnawaveApi(private val ssh: SshSession, private val user: String, private val pass: String) {
    var token = ""
    private val base = "http://127.0.0.1:${RemnawavePanel.API_PORT}"

    private fun exec(args: String, body: String?): Pair<Int, String> {
        val f = body?.let { "/tmp/.hydra-rw-body-${XuiPanel.random(6).lowercase()}".also { p -> ssh.upload(it.toByteArray(), p, 384) } }
        try {
            val r = ssh.exec("curl -sS -m 40 -w '\\n%{http_code}' $HEADERS $args${f?.let { " --data-binary @$it" } ?: ""}")
            val code = r.out.trimEnd().substringAfterLast('\n').trim().toIntOrNull() ?: 0
            return code to r.out.trimEnd().substringBeforeLast('\n')
        } finally { f?.let { ssh.exec("rm -f $it") } }
    }

    private fun auth(path: String): String {
        val (c, out) = exec("-X POST -H 'Content-Type: application/json' $base$path", JSONObject().put("username", user).put("password", pass).toString())
        return runCatching { JSONObject(out).getJSONObject("response").getString("accessToken") }.getOrNull()
            ?: throw XuiApiException("Remnawave не пустила владельца (HTTP $c ${out.take(160)})")
    }

    /** Первый и единственный владелец (регистрация открыта, пока владельца нет). */
    fun register() { token = auth("/api/auth/register") }
    fun login() { token = auth("/api/auth/login") }

    /** Запрос API; результат — содержимое `response`. */
    fun call(method: String, path: String, body: Any? = null): Any? {
        if (token.isBlank()) login()
        val (c, out) = exec("-X $method -H ${Installer.shq("Authorization: Bearer $token")} -H 'Content-Type: application/json' ${Installer.shq(base + path)}", body?.toString())
        if (c !in 200..299) throw XuiApiException("Remnawave: HTTP $c ${out.take(240)}")
        return runCatching { JSONObject(out).opt("response") }.getOrNull()
    }

    fun secretKey(): String = (call("GET", "/api/keygen") as JSONObject).getString("secretKey")

    /** Ноду на этом же сервере: панель (сеть Docker) ходит к ней на адрес шлюза. */
    fun addLocalNode(address: String) {
        val inb = profileInbounds().map { it.getString("uuid") }
        call("POST", "/api/nodes", JSONObject().put("name", NODE).put("address", address).put("port", RemnawavePanel.NODE_PORT)
            .put("configProfile", JSONObject().put("activeConfigProfileUuid", RemnawavePanel.DEFAULT_PROFILE).put("activeInbounds", JSONArray(inb))))
    }

    fun nodes(): List<JSONObject> = (call("GET", "/api/nodes") as? JSONArray).let { a -> if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) } }

    fun waitNodeConnected(): Boolean {
        repeat(30) {
            if (runCatching { nodes().any { it.optString("name") == NODE && it.optBoolean("isConnected") } }.getOrDefault(false)) return true
            Thread.sleep(3000)
        }
        return false
    }

    private fun profileInbounds(): List<JSONObject> {
        val r = call("GET", "/api/config-profiles/${RemnawavePanel.DEFAULT_PROFILE}/inbounds") as JSONObject
        val a = r.optJSONArray("inbounds") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    /**
     * Создать подключение: инбаунд — в профиль ядра по умолчанию, нода его включает, отряд «Hydra» его содержит, хост — с
     * адресом сервера, пользователь «hydra» — в отряде. Ссылки — из подписки пользователя (с самого сервера).
     */
    fun createConnection(type: XuiConnection, port: Int, host: String, mask: XuiPanel.RealityMask): List<String> {
        val profile = call("GET", "/api/config-profiles/${RemnawavePanel.DEFAULT_PROFILE}") as JSONObject
        val cfg = profile.getJSONObject("config")
        val inbounds = cfg.optJSONArray("inbounds") ?: JSONArray().also { cfg.put("inbounds", it) }
        if ((0 until inbounds.length()).any { inbounds.getJSONObject(it).optInt("port") == port })
            throw XuiApiException("Порт $port уже занят другим подключением панели — выберите другой")
        val tag = "hydra-${type.name.lowercase().replace('_', '-')}-$port"
        val kp = if (type.reality) X25519.generate() else null
        inbounds.put(PasarGuardPanel.xrayInbound(type, tag, port, kp?.privateKey.orEmpty(), kp?.publicKey.orEmpty(), mask))
        call("PATCH", "/api/config-profiles", JSONObject().put("uuid", RemnawavePanel.DEFAULT_PROFILE).put("config", cfg))
        val inb = profileInbounds().firstOrNull { it.optString("tag") == tag }?.getString("uuid") ?: throw XuiApiException("Remnawave не приняла инбаунд $tag")
        // нода: включить новый инбаунд
        val node = nodes().firstOrNull { it.optString("name") == NODE } ?: throw XuiApiException("Нет ноды $NODE")
        val active = node.optJSONObject("configProfile")?.optJSONArray("activeInbounds")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("uuid") } }.orEmpty()
        call("PATCH", "/api/nodes", JSONObject().put("uuid", node.getString("uuid"))
            .put("configProfile", JSONObject().put("activeConfigProfileUuid", RemnawavePanel.DEFAULT_PROFILE).put("activeInbounds", JSONArray(active + inb))))
        // отряд «Hydra»
        val squads = (call("GET", "/api/internal-squads") as JSONObject).optJSONArray("internalSquads") ?: JSONArray()
        val sq = (0 until squads.length()).map { squads.getJSONObject(it) }.firstOrNull { it.optString("name") == SQUAD }
        val squadId = if (sq == null) (call("POST", "/api/internal-squads", JSONObject().put("name", SQUAD).put("inbounds", JSONArray(listOf(inb)))) as JSONObject).getString("uuid")
        else {
            val have = sq.optJSONArray("inbounds")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("uuid") } }.orEmpty()
            call("PATCH", "/api/internal-squads", JSONObject().put("uuid", sq.getString("uuid")).put("inbounds", JSONArray(have + inb)))
            sq.getString("uuid")
        }
        call("POST", "/api/hosts", JSONObject().put("inbound", JSONObject().put("configProfileUuid", RemnawavePanel.DEFAULT_PROFILE).put("configProfileInboundUuid", inb))
            .put("remark", "Hydra ${type.title}").put("address", host).put("port", port))
        val u = runCatching { call("GET", "/api/users/by-username/$USER") as JSONObject }.getOrNull()
        val user = if (u == null) call("POST", "/api/users", JSONObject().put("username", USER).put("expireAt", "2099-12-31T00:00:00.000Z")
            .put("activeInternalSquads", JSONArray(listOf(squadId)))) as JSONObject
        else {
            val ids = u.optJSONArray("activeInternalSquads")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("uuid") } }.orEmpty()
            if (squadId !in ids) call("PATCH", "/api/users", JSONObject().put("username", USER).put("activeInternalSquads", JSONArray(ids + squadId)))
            u
        }
        val short = user.optString("shortUuid").ifBlank { throw XuiApiException("Remnawave не выдала подписку пользователя") }
        // 0.8.2-rc2: нода иногда не подхватывает новый инбаунд сама («handler not found», порт не слушается, пока ноду не перезапустят) —
        // чаще у первого подключения после установки. Порт не слушается — перезапуск ноды через API панели (до двух раз).
        for (attempt in 0 until 3) {
            if (ssh.exec("ss -ltnuH 2>/dev/null | grep -q ':$port '").ok) break
            if (attempt == 2) break
            runCatching { call("POST", "/api/nodes/${node.getString("uuid")}/actions/restart", JSONObject().put("forceRestart", true)) }
            Thread.sleep(8000)
        }
        var links = emptyList<String>()
        repeat(6) { links = subscriptionLinks(short).filter { l -> OtherPanelConnections.linkHasPort(l, port) }
            if (links.isNotEmpty()) return links; Thread.sleep(2500) }
        return links
    }

    /** Подписка пользователя (base64-список ссылок), с самого сервера. */
    fun subscriptionLinks(shortUuid: String): List<String> {
        val r = ssh.exec("curl -sS -m 20 $HEADERS -H 'User-Agent: Hydra' ${Installer.shq("$base/api/sub/$shortUuid")}")
        val body = r.out.trim()
        val text = runCatching { String(java.util.Base64.getMimeDecoder().decode(body)) }.getOrDefault(body)
        return text.lines().map { it.trim() }.filter { "://" in it }
    }

    companion object {
        // вход владельца (JWT) панель принимает только от «браузера» — заголовок, как у её сайта
        const val HEADERS = "-H 'X-Forwarded-For: 127.0.0.1' -H 'X-Forwarded-Proto: https' -H 'X-Remnawave-Client-Type: browser'"
        const val NODE = "Hydra"; const val SQUAD = "Hydra"; const val USER = "hydra"
    }
}
