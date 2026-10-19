package ru.gidravpn.hydra.servers

import org.json.JSONArray
import org.json.JSONObject

/**
 * Нода панели (0.8.2-a4, запрос владельца «установка и подключение нод серверов, где есть ноды»): второй сервер из «Моих
 * серверов», на котором работают подключения главной панели. Хранится в записи главной панели ([PanelInstance.nodes]);
 * у сервера ноды — [MyServer.nodeOf]. [remoteId] — номер (3x-ui, PasarGuard) или uuid (Remnawave) ноды в самой панели.
 */
data class PanelNode(
    val serverId: String,
    val name: String,
    val host: String,
    val remoteId: String = "",
    /** Порты, открытые на сервере ноды под подключения (закрываются при удалении ноды). */
    val ports: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().put("server", serverId).put("name", name).put("host", host).put("remote", remoteId).put("ports", JSONArray(ports))

    companion object {
        fun fromJson(o: JSONObject) = PanelNode(o.getString("server"), o.optString("name"), o.optString("host"), o.optString("remote"),
            o.optJSONArray("ports")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty())
        fun listToJson(l: List<PanelNode>) = JSONArray(l.map { it.toJson() })
        fun listFromJson(a: JSONArray?): List<PanelNode> = (0 until (a?.length() ?: 0)).mapNotNull { runCatching { fromJson(a!!.getJSONObject(it)) }.getOrNull() }
    }
}

/** Сервер работает нодой панели [kind] с сервера [mainId] (0.8.2-a4). */
data class NodeRole(val kind: PanelKind, val mainId: String, val mainName: String) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("main", mainId).put("mainName", mainName)

    companion object {
        fun fromJson(o: JSONObject) = NodeRole(runCatching { PanelKind.valueOf(o.getString("kind")) }.getOrDefault(PanelKind.XUI3), o.getString("main"), o.optString("mainName"))
    }
}

object PanelNodes {
    /** Панели, у которых Hydra умеет ставить ноды. s-ui и x-ui (alireza0) нод не имеют. */
    fun supports(kind: PanelKind) = kind == PanelKind.XUI3 || kind == PanelKind.PASARGUARD || kind == PanelKind.REMNAWAVE

    val PG_NODE_MARK = "${ServerOps.STATE_DIR}/pg-node.installed"
    val RW_NODE_MARK = "${ServerOps.STATE_DIR}/remnanode.installed"
    const val PG_NODE_SH = "/root/.hydra-pg-node.sh"
    /** Порты ноды PasarGuard: gRPC и API (так их ставит `pg-node.sh`). */
    const val PG_NODE_PORTS = "62050,62051"

    /** Имя ноды в панели: «Hydra-<адрес>» — панели требуют уникальное имя. */
    fun nodeName(host: String) = "Hydra-" + host.removePrefix("[").removeSuffix("]").replace(Regex("[^A-Za-z0-9.-]"), "-").take(40)

    /** IPv4 главного сервера, каким его видит нода: имя хоста разрешается на самой ноде. */
    fun resolveCommand(host: String) = "getent ahostsv4 ${Installer.shq(host.removePrefix("[").removeSuffix("]"))} | awk 'NR==1{print \$1}'"

    /** Частный адрес (стенд, локальная сеть): 3x-ui без явного разрешения к таким нодам не ходит. */
    fun isPrivate(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return h == "localhost" || h.startsWith("fc", true) || h.startsWith("fd", true)
        return p[0] == 10 || p[0] == 127 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || (p[0] == 100 && p[1] in 64..127)
    }

    // ------------------------------------------------------------------------------------------------ 3x-ui

    /** Токен 3x-ui для главной панели: область `node-sync` (только синхронизация, не администратор). */
    fun xuiNodeTokenCommand() = "/usr/local/x-ui/x-ui setting -getApiToken -tokenScope node-sync -tokenName hydra-main 2>/dev/null | sed -n 's/^apiToken: *//p' | tail -1"

    /** Тело `POST /panel/api/nodes/add` (контракт NodeMutationRequest 3x-ui v3.10). */
    fun xuiNodeBody(name: String, scheme: String, host: String, port: Int, basePath: String, token: String): JSONObject =
        JSONObject().put("id", 0).put("name", name).put("remark", "Hydra").put("scheme", scheme).put("address", host.removePrefix("[").removeSuffix("]"))
            .put("port", port).put("basePath", "/" + basePath.trim('/') + "/").put("apiToken", token).put("enable", true)
            .put("allowPrivateAddress", isPrivate(host)).put("tlsVerifyMode", if (scheme == "https") "skip" else "verify")
            .put("inboundSyncMode", "all").put("inboundTags", JSONArray()).put("outboundTag", "").put("pinnedCertSha256", "")

    // ------------------------------------------------------------------------------------------------ PasarGuard

    /** Нода PasarGuard на отдельном сервере: Docker и `pg-node.sh` закреплённого выпуска, ключ ноды придумывает Hydra. */
    fun pgNodeSteps(apiKey: String, host: String): List<Step> = listOf(
        Step("pgn-exists", "Проверить, что ноды PasarGuard на сервере ещё нет", "test ! -e ${PasarGuardPanel.NODE_DIR}",
            failHint = "На сервере уже стоит нода PasarGuard"),
        Step("pgn-docker", "Поставить Docker, если его нет (нода работает в контейнере)",
            "(command -v docker >/dev/null || (curl -fsSL -m 180 https://get.docker.com | sh) >/tmp/.hydra-docker.log 2>&1) && systemctl enable --now docker >/dev/null 2>&1; docker --version",
            failHint = "Docker поставить не удалось"),
        Step("pgn-fetch", "Загрузить установщик ноды PasarGuard (закреплённый выпуск ${PasarGuardPanel.SCRIPTS_TAG})",
            "curl -fsSL -m 180 ${Installer.shq(PasarGuardPanel.scriptUrl("pg-node.sh"))} -o $PG_NODE_SH && chmod 700 $PG_NODE_SH && sha256sum $PG_NODE_SH",
            rollback = "rm -f $PG_NODE_SH", failHint = "Не удалось скачать установщик с GitHub"),
        Step("pgn-install", "Установить ноду PasarGuard (ядро Xray)",
            // сертификат ноды — и на её адрес: главная панель сверяет его при подключении (без этого «IP address mismatch»)
            "bash $PG_NODE_SH install --api-key ${Installer.shq(apiKey)} --san-entries ${Installer.shq(host.removePrefix("[").removeSuffix("]"))} -y < /dev/null > /tmp/.hydra-pgn.log 2>&1; rc=\$?; " +
                "if [ \$rc -ne 0 ]; then tail -n 6 /tmp/.hydra-pgn.log | sed -E 's/[0-9a-f]{8}-[0-9a-f-]{27}/***/g'; fi; rm -f /tmp/.hydra-pgn.log $PG_NODE_SH; test -s ${PasarGuardPanel.NODE_CERT} && " +
                // сертификат появляется раньше контейнера: ждём, пока нода начнёт слушать свой порт
                "for i in \$(seq 1 30); do ss -ltnH 2>/dev/null | awk '{print \$4}' | grep -Eq ':62050\$' && { echo 'нода слушает 62050'; exit 0; }; sleep 2; done; " +
                "(cd ${PasarGuardPanel.NODE_DIR} && docker compose logs --tail 6 2>&1 | tail -n 6); exit 1",
            rollback = pgNodeUninstallCommand(), failHint = "Нода PasarGuard не установилась"),
        Step("pgn-mark", "Отметить, что ноду поставила Hydra", "mkdir -p ${ServerOps.STATE_DIR} && chmod 700 ${ServerOps.STATE_DIR} && touch $PG_NODE_MARK"),
    )

    fun pgNodeUninstallCommand() = "(cd ${PasarGuardPanel.NODE_DIR} 2>/dev/null && docker compose down -v >/dev/null 2>&1); " +
        "rm -rf ${PasarGuardPanel.NODE_DIR} ${PasarGuardPanel.NODE_DATA} /usr/local/bin/pg-node; rm -f $PG_NODE_MARK; true"

    // ------------------------------------------------------------------------------------------------ Remnawave

    /** Нода Remnawave на отдельном сервере: контейнер `remnawave/node` с ключом панели ([RemnawaveApi.secretKey]). */
    fun rwNodeSteps(secretKey: String): List<Step> = listOf(
        Step("rwn-exists", "Проверить, что ноды Remnawave на сервере ещё нет", "test ! -e ${RemnawavePanel.NODE_DIR}",
            failHint = "На сервере уже стоит нода Remnawave"),
        Step("rwn-docker", "Поставить Docker, если его нет (нода работает в контейнере)",
            "(command -v docker >/dev/null || (curl -fsSL -m 180 https://get.docker.com | sh) >/tmp/.hydra-docker.log 2>&1) && systemctl enable --now docker >/dev/null 2>&1; docker --version",
            failHint = "Docker поставить не удалось"),
        Step("rwn-files", "Файлы ноды Remnawave", "mkdir -p ${RemnawavePanel.NODE_DIR} && chmod 700 ${RemnawavePanel.NODE_DIR}"),
        Step("rwn-compose", "Настройки ноды (ключ от главной панели)", upload = Step.Upload("${RemnawavePanel.NODE_DIR}/docker-compose.yml", RemnawavePanel.nodeCompose(secretKey).toByteArray(), 384)),
        Step("rwn-up", "Запустить ноду Remnawave ${RemnawavePanel.NODE_TAG} (ядро Xray)",
            "cd ${RemnawavePanel.NODE_DIR} && docker compose up -d > /tmp/.hydra-rwn.log 2>&1; rc=\$?; [ \$rc -ne 0 ] && { tail -n 8 /tmp/.hydra-rwn.log; rm -f /tmp/.hydra-rwn.log; exit \$rc; }; rm -f /tmp/.hydra-rwn.log; " +
                "for i in \$(seq 1 30); do ss -ltnH 2>/dev/null | awk '{print \$4}' | grep -Eq ':${RemnawavePanel.NODE_PORT}\$' && { echo 'нода слушает ${RemnawavePanel.NODE_PORT}'; exit 0; }; sleep 2; done; " +
                "docker compose logs --tail 6 2>&1 | tail -n 6; exit 1",
            rollback = rwNodeUninstallCommand(), failHint = "Нода Remnawave не запустилась"),
        Step("rwn-mark", "Отметить, что ноду поставила Hydra", "mkdir -p ${ServerOps.STATE_DIR} && chmod 700 ${ServerOps.STATE_DIR} && touch $RW_NODE_MARK"),
    )

    fun rwNodeUninstallCommand() = "(cd ${RemnawavePanel.NODE_DIR} 2>/dev/null && docker compose down -v >/dev/null 2>&1); rm -rf ${RemnawavePanel.NODE_DIR}; rm -f $RW_NODE_MARK; true"

    /** Что будет сделано — для показа до запуска. */
    fun planPreview(kind: PanelKind, mainName: String): List<Step> = when (kind) {
        PanelKind.XUI3 -> listOf(
            Step("node-xui", "Поставить на этот сервер 3x-ui (если её нет) и выдать главной панели токен с правом только синхронизации"),
            Step("node-fw", "Открыть порт панели ноды только для главного сервера"),
            Step("node-add", "Подключить ноду к панели «$mainName»"),
            Step("node-conn", "Создать на ноде подключение VLESS + gRPC + REALITY и добавить его в «Серверы»"))
        PanelKind.PASARGUARD -> listOf(
            Step("node-pg", "Поставить ноду PasarGuard (Docker, ядро Xray)"),
            Step("node-fw", "Открыть порты ноды (${PG_NODE_PORTS.replace(",", ", ")}) только для главного сервера"),
            Step("node-add", "Подключить ноду к панели «$mainName» — подключения Hydra заработают и на ней"),
            Step("node-hosts", "Добавить адрес ноды к подключениям и ссылки — в «Серверы»"))
        PanelKind.REMNAWAVE -> listOf(
            Step("node-rw", "Поставить ноду Remnawave (Docker, ядро Xray) с ключом главной панели"),
            Step("node-fw", "Открыть порт ноды ${RemnawavePanel.NODE_PORT} только для главного сервера"),
            Step("node-add", "Подключить ноду к панели «$mainName» со всеми подключениями профиля"),
            Step("node-hosts", "Добавить адрес ноды к подключениям и ссылки — в «Серверы»"))
        else -> emptyList()
    }
}

// ---------------------------------------------------------------------------------------------------- API нод

/** Ноды 3x-ui (`/panel/api/nodes/…`) поверх [XuiTransport] главной панели. */
class XuiNodesApi(private val t: XuiTransport) {
    private fun obj(raw: String): Any? {
        val j = runCatching { JSONObject(raw.trim()) }.getOrElse { throw XuiApiException("Некорректный ответ панели: ${raw.take(120)}") }
        if (!j.optBoolean("success")) throw XuiApiException(j.optString("msg").ifBlank { "Панель отклонила запрос" })
        return j.opt("obj")
    }

    fun list(): List<JSONObject> = (obj(t.call("GET", "/panel/api/nodes/list")) as? JSONArray).let { a -> if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) } }

    /** Добавить ноду; номер — из ответа или из списка по имени. */
    fun add(body: JSONObject): Int {
        val o = obj(t.call("POST", "/panel/api/nodes/add", body.toString()))
        return (o as? JSONObject)?.optInt("id")?.takeIf { it > 0 } ?: list().firstOrNull { it.optString("name") == body.optString("name") }?.optInt("id")
            ?: throw XuiApiException("Панель не вернула номер ноды")
    }

    fun delete(id: Int) { obj(t.call("POST", "/panel/api/nodes/del/$id")) }

    fun probe(id: Int) = runCatching { obj(t.call("POST", "/panel/api/nodes/probe/$id")) }.getOrNull()

    /** Дождаться, пока главная панель увидит ноду (до ~60 с). Ответ — последняя ошибка, если не дождались. */
    fun waitOnline(id: Int): String? {
        var err = ""
        repeat(20) {
            probe(id)
            val n = list().firstOrNull { it.optInt("id") == id }
            if (n?.optString("status") == "online") return null
            err = n?.optString("lastError").orEmpty()
            Thread.sleep(3000)
        }
        return err.ifBlank { "нода не ответила" }
    }
}

/** Ноды и хосты PasarGuard поверх [PasarGuardApi] главной панели. */
fun PasarGuardApi.addRemoteNode(name: String, host: String, apiKey: String, ca: String): Int {
    require(ca.contains("BEGIN CERTIFICATE")) { "Нет сертификата ноды" }
    val o = call("POST", "/api/node", JSONObject().put("name", name).put("address", host.removePrefix("[").removeSuffix("]")).put("port", 62050).put("api_port", 62051)
        .put("connection_type", "grpc").put("server_ca", ca).put("keep_alive", 60).put("core_config_id", 1).put("api_key", apiKey)) as? JSONObject
    return o?.optInt("id")?.takeIf { it > 0 } ?: pgNodes().firstOrNull { it.optString("name") == name }?.optInt("id") ?: throw XuiApiException("PasarGuard не вернула номер ноды")
}

fun PasarGuardApi.pgNodes(): List<JSONObject> {
    val r = call("GET", "/api/nodes")
    val a = (r as? JSONArray) ?: (r as? JSONObject)?.optJSONArray("nodes") ?: JSONArray()
    return (0 until a.length()).map { a.getJSONObject(it) }
}

fun PasarGuardApi.waitNodeConnected(id: Int): String? {
    var err = ""
    repeat(30) {
        val n = runCatching { pgNodes().firstOrNull { it.optInt("id") == id } }.getOrNull()
        if (n?.optString("status") == "connected") return null
        err = n?.optString("message").orEmpty()
        Thread.sleep(3000)
    }
    return err.ifBlank { "нода не подключилась" }
}

/** Хосты подключений Hydra (теги «Hydra …») с адресом ноды: ссылки пользователя получают и её. Возвращает порты инбаундов. */
fun PasarGuardApi.addNodeHosts(host: String, nodeName: String): List<Int> {
    val cfg = (call("GET", "/api/core/1") as JSONObject).getJSONObject("config")
    val ib = cfg.optJSONArray("inbounds") ?: JSONArray()
    val ours = (0 until ib.length()).map { ib.getJSONObject(it) }.filter { it.optString("tag").startsWith("Hydra ") }
    for (i in ours) call("POST", "/api/host/", JSONObject().put("remark", "${i.getString("tag")} {USERNAME} · $nodeName")
        .put("address", JSONArray(listOf(host.removePrefix("[").removeSuffix("]")))).put("inbound_tag", i.getString("tag")).put("priority", 1))
    return ours.map { it.optInt("port") }.filter { it > 0 }
}

/** Убрать ноду из PasarGuard: её хосты (по адресу) и саму ноду. */
fun PasarGuardApi.removeNode(id: Int, host: String) {
    val h = host.removePrefix("[").removeSuffix("]")
    val r = runCatching { call("GET", "/api/hosts") }.getOrNull()
    val a = (r as? JSONArray) ?: (r as? JSONObject)?.optJSONArray("hosts") ?: JSONArray()
    (0 until a.length()).map { a.getJSONObject(it) }.filter { o ->
        val addr = o.opt("address"); (addr as? JSONArray)?.let { x -> (0 until x.length()).any { x.optString(it) == h } } ?: (addr?.toString() == h)
    }.forEach { runCatching { call("DELETE", "/api/host/${it.getInt("id")}") } }
    if (id > 0) runCatching { call("DELETE", "/api/node/$id") }
}

fun PasarGuardApi.userLinks(): List<String> {
    val u = call("GET", "/api/user/${PasarGuardApi.USER}") as JSONObject
    return subscriptionLinks(u.optString("subscription_url").ifBlank { throw XuiApiException("У пользователя hydra нет подписки") })
}

/** Ноды и хосты Remnawave поверх [RemnawaveApi] главной панели. */
fun RemnawaveApi.addRemoteNode(name: String, host: String): String {
    val prof = call("GET", "/api/config-profiles/${RemnawavePanel.DEFAULT_PROFILE}/inbounds") as JSONObject
    val inb = prof.optJSONArray("inbounds")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("uuid") } }.orEmpty()
    val o = call("POST", "/api/nodes", JSONObject().put("name", name).put("address", host.removePrefix("[").removeSuffix("]")).put("port", RemnawavePanel.NODE_PORT)
        .put("configProfile", JSONObject().put("activeConfigProfileUuid", RemnawavePanel.DEFAULT_PROFILE).put("activeInbounds", JSONArray(inb)))) as? JSONObject
    return o?.optString("uuid")?.ifBlank { null } ?: nodes().firstOrNull { it.optString("name") == name }?.optString("uuid") ?: throw XuiApiException("Remnawave не вернула ноду")
}

fun RemnawaveApi.waitNodeConnected(uuid: String): String? {
    var err = ""
    repeat(30) {
        val n = runCatching { nodes().firstOrNull { it.optString("uuid") == uuid } }.getOrNull()
        if (n?.optBoolean("isConnected") == true) return null
        err = n?.optString("lastStatusMessage").orEmpty()
        Thread.sleep(3000)
    }
    return err.ifBlank { "нода не подключилась" }
}

/** Хосты подключений Hydra (теги «hydra-…») с адресом ноды. Возвращает порты инбаундов. */
fun RemnawaveApi.addNodeHosts(host: String, nodeName: String): List<Int> {
    val prof = call("GET", "/api/config-profiles/${RemnawavePanel.DEFAULT_PROFILE}/inbounds") as JSONObject
    val a = prof.optJSONArray("inbounds") ?: JSONArray()
    val ours = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("tag").startsWith("hydra-") }
    for (i in ours) call("POST", "/api/hosts", JSONObject().put("inbound", JSONObject().put("configProfileUuid", RemnawavePanel.DEFAULT_PROFILE).put("configProfileInboundUuid", i.getString("uuid")))
        .put("remark", "Hydra ${i.getString("tag").removePrefix("hydra-")} · $nodeName").put("address", host.removePrefix("[").removeSuffix("]")).put("port", i.optInt("port")))
    return ours.map { it.optInt("port") }.filter { it > 0 }
}

fun RemnawaveApi.removeNode(uuid: String, host: String) {
    val h = host.removePrefix("[").removeSuffix("]")
    val hosts = runCatching { call("GET", "/api/hosts") as? JSONArray }.getOrNull() ?: JSONArray()
    (0 until hosts.length()).map { hosts.getJSONObject(it) }.filter { it.optString("address") == h }
        .forEach { runCatching { call("DELETE", "/api/hosts/${it.getString("uuid")}") } }
    if (uuid.isNotBlank()) runCatching { call("DELETE", "/api/nodes/$uuid") }
}

fun RemnawaveApi.userLinks(): List<String> {
    val u = call("GET", "/api/users/by-username/${RemnawaveApi.USER}") as JSONObject
    return subscriptionLinks(u.optString("shortUuid").ifBlank { throw XuiApiException("У пользователя hydra нет подписки") })
}

/** Ссылки на адрес [host] (у vmess:// адрес внутри base64). */
fun linksToHost(links: List<String>, host: String): List<String> {
    val h = host.removePrefix("[").removeSuffix("]")
    return links.filter { l ->
        val head = l.substringBefore("#").substringBefore("?")
        "@$h:" in head || "@[$h]:" in head || (l.startsWith("vmess://") && runCatching {
            val b = l.substringAfter("://").substringBefore("#").let { it + "=".repeat((4 - it.length % 4) % 4) }
            JSONObject(String(java.util.Base64.getDecoder().decode(b))).optString("add") == h
        }.getOrDefault(false))
    }
}
