package ru.gidravpn.hydra.servers

import org.json.JSONArray
import org.json.JSONObject

/**
 * Nexora v0.0.6: панель ставится официальным закреплённым установщиком, а ноды — штатными SSH-заданиями самой панели.
 * Используется только стабильный `/api/v1`; пароль и ключ ноды передаются панели в запросе и нигде ею не сохраняются.
 * Сайт панели слушает 127.0.0.1 и открывается через SSH-туннель Hydra.
 */
object NexoraPanel {
    const val TAG = "v0.0.6"
    const val REPO = "Nexora-VPN/panel"
    const val HOME = "/opt/nexora-panel"
    const val DATA = "/var/opt/nexora"
    const val SERVICE = "nexora-panel"
    const val NODE_PORT = 62050
    const val MARK = "${ServerOps.STATE_DIR}/nexora.installed"
    const val NODE_MARK = "${ServerOps.STATE_DIR}/nexora-node.installed"
    private const val SCRIPT = "/root/.hydra-nexora-install.sh"

    fun scriptUrl(tag: String = TAG) = "https://raw.githubusercontent.com/$REPO/$tag/install.sh"

    private fun fetchStep() = Step("nexora-fetch", "Загрузить официальный установщик Nexora $TAG",
        "curl -fsSL -m 180 ${Installer.shq(scriptUrl())} -o $SCRIPT && chmod 700 $SCRIPT && sha256sum $SCRIPT && " +
            ScriptPins.verify(REPO, TAG, "install.sh", SCRIPT),
        rollback = "rm -f $SCRIPT", failHint = "Не удалось скачать установщик Nexora или его SHA-256 не совпал с проверенным")

    fun installSteps(): List<Step> = listOf(
        Step("nexora-exists", "Проверить, что Nexora ещё не установлена", "test ! -e $HOME",
            failHint = "На сервере уже стоит Nexora — удалите её или выберите другой сервер"),
        fetchStep(),
        Step("nexora-install", "Установить Nexora Panel $TAG и подготовить Nexora Node $TAG",
            "bash $SCRIPT --version $TAG --node-version $TAG < /dev/null > /tmp/.hydra-nexora.log 2>&1; rc=\$?; " +
                "if [ \$rc -ne 0 ]; then tail -n 8 /tmp/.hydra-nexora.log; fi; rm -f /tmp/.hydra-nexora.log $SCRIPT; exit \$rc",
            rollback = uninstallCommand(), failHint = "Установка Nexora не удалась"),
        Step("nexora-active", "Проверить службу Nexora", "systemctl is-active $SERVICE && test -x $HOME/nexora-panel",
            failHint = "Панель не запустилась (journalctl -u $SERVICE)"),
        Step("nexora-mark", "Отметить, что панель поставила Hydra",
            "mkdir -p ${ServerOps.STATE_DIR} && chmod 700 ${ServerOps.STATE_DIR} && touch $MARK"),
    )

    fun setupPreviewSteps() = listOf(
        Step("nexora-setup", "Создать владельца панели; панель будет слушать только 127.0.0.1", "curl … /api/setup"),
        Step("nexora-token", "Выпустить API-токен Hydra (его открытый текст панель показывает один раз)", "curl … /api/tokens"),
    )

    /** Официальный uninstall сохраняет базу; полная очистка Hydra удаляет и её после явного подтверждения пользователя. */
    fun uninstallCommand(): String =
        "curl -fsSL -m 180 ${Installer.shq(scriptUrl())} -o $SCRIPT && chmod 700 $SCRIPT && " +
            ScriptPins.verify(REPO, TAG, "install.sh", SCRIPT) + " && bash $SCRIPT --uninstall < /dev/null; rc=\$?; " +
            "rm -f $SCRIPT; [ \$rc -eq 0 ] && rm -rf $DATA && rm -f $MARK ${OtherPanels.PANEL_STATE}; exit \$rc"

    fun uninstallSteps(): List<Step> = listOf(
        Step("nexora-backup", "Сохранить копию данных Nexora в ${ServerOps.BACKUP_DIR}",
            "mkdir -p ${ServerOps.BACKUP_DIR} && chmod 700 ${ServerOps.BACKUP_DIR}; if [ -d $DATA ]; then " +
                "cp -a $DATA ${ServerOps.BACKUP_DIR}/nexora-\$(date +%Y%m%d-%H%M%S) || exit 1; fi"),
        Step("nexora-remove", "Удалить Nexora официальным установщиком и очистить её данные", uninstallCommand(),
            failHint = "Nexora удалить не удалось; данные сохранены в ${ServerOps.BACKUP_DIR}"),
    )

    /** Аварийная очистка ноды, если главная панель уже недоступна. Обычное удаление идёт через install-job панели. */
    fun nodeUninstallCommand() =
        "systemctl disable --now nexora-node >/dev/null 2>&1; rm -f /etc/systemd/system/nexora-node.service /usr/local/bin/nexora-node; " +
            "systemctl daemon-reload; rm -rf /opt/nexora-node /var/opt/nexora-node; rm -f $NODE_MARK; true"

    fun setupBody(setupToken: String, username: String, password: String, port: Int): JSONObject = JSONObject()
        .put("token", setupToken).put("username", username).put("password", password)
        .put("listenIp", "127.0.0.1").put("listenPort", port.toString()).put("basepath", "")
        .put("subBasepath", "sub").put("tlsMode", "none").put("timezone", "UTC")

    fun tokenBody() = JSONObject().put("desc", "Hydra").put("role", "sudo").put("scopes", JSONArray()).put("rateLimit", 0).put("expiry", 0)

    fun userBody(name: String) = JSONObject().put("name", name).put("enable", true).put("allTemplates", true)

    fun nodeBody(name: String, target: SshTarget): JSONObject = JSONObject()
        .put("name", name).put("address", target.host.removePrefix("[").removeSuffix("]"))
        .put("port", NODE_PORT).put("linkAddress", target.host.removePrefix("[").removeSuffix("]"))
        .put("isLocal", false).put("remark", "Hydra")
        .put("sshHost", target.host.removePrefix("[").removeSuffix("]")).put("sshPort", target.port).put("sshUser", target.user)

    fun credentials(target: SshTarget): JSONObject = JSONObject()
        .put("sshHost", target.host.removePrefix("[").removeSuffix("]")).put("sshPort", target.port).put("sshUser", target.user)
        .also {
            if (target.password.orEmpty().isNotEmpty()) it.put("password", target.password)
            if (target.privateKey.orEmpty().isNotEmpty()) it.put("privateKey", target.privateKey)
            if (target.keyPassphrase.orEmpty().isNotEmpty()) it.put("passphrase", target.keyPassphrase)
        }

    fun installBody(target: SshTarget, uninstall: Boolean = false): JSONObject = credentials(target)
        .put("method", "script").put("source", "upload").put("installKey", true).put("rememberHost", true).put("uninstall", uninstall)

    /** POST по SSH с телом во временном файле 0600: пароль и setup-token не попадают в командную строку/`ps`. */
    private fun post(ssh: SshSession, url: String, body: JSONObject): JSONObject {
        val suffix = XuiPanel.random(8).lowercase()
        val input = "/tmp/.hydra-nexora-$suffix.json"
        val output = "/tmp/.hydra-nexora-$suffix.out"
        try {
            ssh.upload(body.toString().toByteArray(), input, 384)
            val r = ssh.exec("curl -sS -m 30 -X POST -H 'Content-Type: application/json' --data @$input ${Installer.shq(url)} -o $output -w '%{http_code}'")
            val text = ssh.exec("cat $output 2>/dev/null").out
            val code = r.out.trim().toIntOrNull() ?: 0
            if (code !in 200..299) throw XuiApiException("Nexora HTTP $code ${text.take(200)}")
            return runCatching { JSONObject(text) }.getOrElse { throw XuiApiException("Nexora вернула некорректный JSON") }
        } finally {
            ssh.exec("rm -f $input $output")
        }
    }

    /** Вход веб-сессией и создание долговременного Bearer-токена; открытый текст возвращается только здесь. */
    fun createToken(ssh: SshSession, localBase: String, username: String, password: String): String {
        val base = localBase.trimEnd('/')
        val suffix = XuiPanel.random(8).lowercase()
        val cookies = "/tmp/.hydra-nexora-$suffix.cookie"
        try {
            // post() использует cookie-файл по своему суффиксу, поэтому два запроса выполняются одной curl-сессией ниже.
            val loginIn = "/tmp/.hydra-nexora-$suffix-login.json"; val tokenIn = "/tmp/.hydra-nexora-$suffix-token.json"
            val loginOut = "/tmp/.hydra-nexora-$suffix-login.out"; val tokenOut = "/tmp/.hydra-nexora-$suffix-token.out"
            try {
                ssh.upload(JSONObject().put("username", username).put("password", password).put("remember", true).toString().toByteArray(), loginIn, 384)
                ssh.upload(tokenBody().toString().toByteArray(), tokenIn, 384)
                val r = ssh.exec("curl -sS -m 30 -c $cookies -H 'Content-Type: application/json' --data @$loginIn ${Installer.shq("$base/api/login")} -o $loginOut -w '%{http_code}'; " +
                    "curl -sS -m 30 -b $cookies -H 'Content-Type: application/json' --data @$tokenIn ${Installer.shq("$base/api/tokens")} -o $tokenOut -w ' %{http_code}'")
                val codes = r.out.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                if (codes.size != 2 || codes.any { it !in 200..299 }) {
                    val detail = ssh.exec("cat $loginOut $tokenOut 2>/dev/null").out.take(200)
                    throw XuiApiException("Nexora: вход или создание токена отклонены (${codes.joinToString("/")}) $detail")
                }
                val token = runCatching { JSONObject(ssh.exec("cat $tokenOut").out).getString("token") }.getOrDefault("")
                if (token.isBlank()) throw XuiApiException("Nexora не выдала API-токен")
                return token
            } finally {
                ssh.exec("rm -f $loginIn $tokenIn $loginOut $tokenOut")
            }
        } finally {
            ssh.exec("rm -f $cookies")
        }
    }

    fun setup(ssh: SshSession, localBase: String, setupToken: String, username: String, password: String, port: Int) {
        post(ssh, localBase.trimEnd('/') + "/api/setup", setupBody(setupToken, username, password, port))
    }
}

/** Стабильный API Nexora `/api/v1` поверх общего Bearer transport (на сервере или напрямую). */
class NexoraApi(
    private val t: XuiTransport,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    data class User(val id: Int, val name: String, val subUrl: String)
    data class Node(val id: Int, val name: String, val status: String, val message: String)
    data class Job(val id: String, val status: String, val message: String)

    private fun objectResponse(raw: String): JSONObject = runCatching { JSONObject(raw.trim()) }
        .getOrElse { throw XuiApiException("Nexora вернула некорректный JSON: ${raw.take(120)}") }

    private fun items(raw: String): List<JSONObject> {
        val trimmed = raw.trim()
        val a = if (trimmed.startsWith("[")) JSONArray(trimmed) else objectResponse(trimmed).optJSONArray("items")
            ?: throw XuiApiException("Nexora: в ответе списка нет items")
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    /** Все страницы стабильного списка, а не только первые 100 записей. */
    private fun list(path: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        var offset = 0
        while (true) {
            val raw = t.call("GET", "$path?limit=1000&offset=$offset")
            val page = items(raw)
            result += page
            val total = if (raw.trim().startsWith("[")) page.size else objectResponse(raw).optInt("total", result.size)
            if (page.isEmpty() || result.size >= total) return result
            offset += page.size
        }
    }

    fun me(): JSONObject = objectResponse(t.call("GET", "/api/v1/me"))

    fun users(): List<User> = list("/api/v1/users").map {
        User(it.optInt("id"), it.optString("name"), it.optString("subUrl"))
    }.filter { it.id > 0 && it.name.isNotBlank() }

    fun addUser(name: String): User {
        val o = objectResponse(t.call("POST", "/api/v1/users", NexoraPanel.userBody(name).toString()))
        return User(o.optInt("id"), o.optString("name", name), o.optString("subUrl")).also {
            if (it.id <= 0 || it.subUrl.isBlank()) throw XuiApiException("Nexora не вернула пользователя и его подписку")
        }
    }

    fun user(name: String): User = users().firstOrNull { it.name == name }
        ?: throw XuiApiException("Пользователя «$name» в Nexora нет")

    fun deleteUser(id: Int) { t.call("DELETE", "/api/v1/users/$id") }

    fun nodes(): List<Node> = list("/api/v1/nodes").map {
        Node(it.optInt("id"), it.optString("name"), it.optString("status"), it.optString("message"))
    }.filter { it.id > 0 }

    fun addNode(name: String, target: SshTarget): Int {
        val o = objectResponse(t.call("POST", "/api/v1/nodes", NexoraPanel.nodeBody(name, target).toString()))
        return o.optInt("id").takeIf { it > 0 } ?: nodes().firstOrNull { it.name == name }?.id
            ?: throw XuiApiException("Nexora не вернула номер ноды")
    }

    fun probe(id: Int, target: SshTarget): JSONObject =
        objectResponse(t.call("POST", "/api/v1/nodes/$id/install/probe", NexoraPanel.credentials(target).toString()))

    private fun job(o: JSONObject): Job = Job(o.optString("id"), o.optString("status"), o.optString("message"))

    fun startInstall(id: Int, target: SshTarget, uninstall: Boolean = false): Job =
        job(objectResponse(t.call("POST", "/api/v1/nodes/$id/install", NexoraPanel.installBody(target, uninstall).toString())))

    fun job(id: String, cursor: Int = 0): Triple<Job, List<String>, Int> {
        val o = objectResponse(t.call("GET", "/api/v1/nodes/install/jobs/$id?cursor=$cursor"))
        val lines = o.optJSONArray("lines")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        return Triple(job(o.optJSONObject("job") ?: o), lines, o.optInt("cursor", cursor))
    }

    fun awaitInstall(id: Int, target: SshTarget, uninstall: Boolean = false, onLines: (List<String>) -> Unit = {}): Job {
        val started = startInstall(id, target, uninstall)
        if (started.id.isBlank()) throw XuiApiException("Nexora не вернула номер задания установки")
        if (started.status == "error") throw XuiApiException(started.message.ifBlank { "Задание Nexora завершилось ошибкой" })
        if (started.status == "done") return started
        var cursor = 0
        repeat(150) {
            val (current, lines, next) = job(started.id, cursor)
            onLines(lines); cursor = next
            when (current.status) {
                "done" -> return current
                "error" -> throw XuiApiException(current.message.ifBlank { lines.lastOrNull().orEmpty().ifBlank { "Установка ноды Nexora не удалась" } })
                "running" -> sleep(2000)
                else -> throw XuiApiException("Nexora: неизвестное состояние задания ${current.status}")
            }
        }
        throw XuiApiException("Задание Nexora не завершилось за 5 минут")
    }

    fun waitConnected(id: Int): String? {
        repeat(30) {
            val n = nodes().firstOrNull { it.id == id }
            if (n?.status == "connected") return null
            if (n == null) return "нода удалена"
            if (n.status == "error" && n.message.isNotBlank()) return n.message
            sleep(2000)
        }
        return nodes().firstOrNull { it.id == id }?.message?.ifBlank { null } ?: "нода не подключилась"
    }

    fun deleteNode(id: Int) { t.call("DELETE", "/api/v1/nodes/$id") }
}
