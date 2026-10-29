package ru.gidravpn.hydra.servers

import org.json.JSONObject
import ru.gidravpn.hydra.data.subscription.OpenFluxShare
import java.security.SecureRandom

/**
 * Узел (exit) OpenFlux на своём сервере (0.8.0). Тот же путь, что у мастера «Своя нода» в официальных приложениях:
 * скрипт апстрима `deploy/node-install.sh` закреплённого коммита загружается на сервер, сверяется его SHA-256 и
 * запускается по SSH (`plan` -> `apply`); он сам ставит ядро узла из релиза `node-v*`, пользователя, systemd-службу
 * `openflux-node@<канал>` и (по возможности) открывает порт. Секреты в аргументах не передаются — конфиг лежит в файле 0600,
 * который скрипт читает и удаляет.
 */
data class OfxNodeSpec(
    val host: String,
    val channel: String,
    val port: Int,
    /** 64 hex-символа — ключ канала (он же секрет сессии в ссылке). */
    val key: String,
    /** Документ Яндекса, публичный документ Mail.ru, список комнат cups.online (всё необязательно; direct есть всегда). */
    val vyandex: String = "",
    val mailru: String = "",
    val cupsonline: String = "",
    val autoupdate: Boolean = true,
) {
    /** Строки конфигурации для скрипта; [withPort] = false — для `plan` (порт выбирает скрипт). */
    fun config(withPort: Boolean): String = buildString {
        appendLine("channel=$channel")
        appendLine("key=$key")
        if (withPort) appendLine("port=$port")
        if (vyandex.isNotBlank()) appendLine("vyandex=${vyandex.trim()}")
        if (mailru.isNotBlank()) appendLine("mailru=${mailru.trim()}")
        if (cupsonline.isNotBlank()) appendLine("cupsonline=${cupsonline.trim()}")
        appendLine("autoupdate=${if (autoupdate) "yes" else "no"}")
    }

    /** Официальная ссылка `openflux://v1/…`: сессия из direct (наш узел) и выбранных транспортов. */
    fun shareLink(name: String): String {
        val ts = buildList {
            if (vyandex.isNotBlank()) add(OpenFluxShare.Transport("vyandex", url = vyandex.trim(), priority = 100))
            if (mailru.isNotBlank()) add(OpenFluxShare.Transport("mailru", url = mailru.trim(), priority = 90))
            if (cupsonline.isNotBlank()) add(OpenFluxShare.Transport("cupsonline", url = cupsonline.trim(), priority = 70))
            add(OpenFluxShare.Transport("direct", dial = "${if (host.contains(':')) "[$host]" else host}:$port", priority = 50))
        }
        // контекст шифрования — URL транспорта с высшим приоритетом (кроме cups.online), как session_context в скрипте
        val context = vyandex.trim().ifEmpty { mailru.trim() }
        return OpenFluxShare.encode(OpenFluxShare.Config(name = name, negotiate = true, secret = key, context = context, transports = ts))
    }

    fun toJson(): JSONObject = JSONObject().put("host", host).put("channel", channel).put("port", port).put("key", key)
        .put("vyandex", vyandex).put("mailru", mailru).put("cupsonline", cupsonline).put("autoupdate", autoupdate)

    companion object {
        fun fromJson(o: JSONObject) = OfxNodeSpec(o.getString("host"), o.getString("channel"), o.getInt("port"), o.getString("key"),
            o.optString("vyandex"), o.optString("mailru"), o.optString("cupsonline"), o.optBoolean("autoupdate", true))

        fun newKey(rnd: SecureRandom = SecureRandom()): String = ByteArray(32).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        fun validChannel(c: String) = Regex("^[a-z0-9][a-z0-9-]{0,30}$").matches(c)
    }
}

object OpenFluxNode {
    /** Тот же коммит апстрима, что и у встроенного клиента (scripts/build-openflux.sh). */
    const val SCRIPT_REF = "f492a1a33b7581ad8f88118f97e0b866adda3cdc"
    const val SCRIPT_URL = "https://raw.githubusercontent.com/p1neappleXpress/OpenFlux/$SCRIPT_REF/deploy/node-install.sh"
    /** SHA-256 файла по [SCRIPT_URL]; при смене коммита пересчитать (`curl -sL URL | sha256sum`). */
    const val SCRIPT_SHA256 = "5795c109bc37837238b8487df9977702eb5549167e47e7f08a07af7d9abeca83"
    const val SCRIPT = "/root/.hydra-openflux-node-install.sh"

    private val okJson = { r: ExecResult -> r.out.lineSequence().any { it.trim().startsWith("{") && it.contains("\"ok\":true") } }

    /** Ошибка скрипта (`{"ok":false,"step":"…","error":"…"}`) человеческим текстом. */
    fun errorOf(out: String): String? = out.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") }?.let {
        runCatching { JSONObject(it) }.getOrNull()?.takeIf { j -> !j.optBoolean("ok", true) }?.let { j -> "${j.optString("step")}: ${j.optString("error")}" }
    }

    fun portOfPlan(out: String): Int? = out.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") }
        ?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { it.optBoolean("ok") }?.optInt("port")?.takeIf { it > 0 }

    fun fetchSteps(): List<Step> = listOf(
        Step("ofx-fetch", "Загрузить скрипт установки узла OpenFlux (закреплённый коммит)",
            "(curl -fsSL -m 180 ${Installer.shq(SCRIPT_URL)} -o $SCRIPT || wget -q -O $SCRIPT ${Installer.shq(SCRIPT_URL)}) && chmod 700 $SCRIPT",
            rollback = "rm -f $SCRIPT", failHint = "Не удалось скачать скрипт с GitHub"),
        Step("ofx-verify", "Проверить SHA-256 скрипта",
            "echo '$SCRIPT_SHA256  $SCRIPT' | sha256sum -c -", failHint = "Контрольная сумма скрипта не совпала — установка остановлена"),
    )

    fun planCommand(cfgPath: String) = "sh $SCRIPT plan < ${Installer.shq(cfgPath)}; rm -f ${Installer.shq(cfgPath)}"

    fun applySteps(spec: OfxNodeSpec): List<Step> = listOf(
        Step("ofx-cfg", "Записать конфигурацию канала «${spec.channel}» (файл 0600; ключ не показывается)",
            upload = Step.Upload("/tmp/.hydra-ofx.cfg", spec.config(true).toByteArray(), 384)),
        Step("ofx-apply", "Установить узел: ядро, пользователь, служба openflux-node@${spec.channel}, порт ${spec.port}/tcp",
            "sh $SCRIPT apply /tmp/.hydra-ofx.cfg", accept = okJson,
            rollback = "sh $SCRIPT remove ${spec.channel}", failHint = "Установка узла не удалась"),
        Step("ofx-status", "Проверить: служба запущена", "systemctl is-active openflux-node@${spec.channel}",
            accept = { it.out.lineSequence().any { l -> l.trim() == "active" } }, failHint = "Служба узла не активна"),
    )

    fun removeSteps(channel: String): List<Step> = listOf(
        Step("ofx-remove", "Удалить канал «$channel» (последний канал убирает и ядро узла)",
            "sh /opt/openflux-node/node-install.sh remove ${Installer.shq(channel)}; rm -f ${Installer.shq(ServerOps.ofxState(channel))}",
            accept = okJson, failHint = "Канал удалить не удалось"),
    )

    /** Перезапустить канал узла. */
    fun restartCommand(channel: String) = "systemctl restart ${Installer.shq("openflux-node@$channel")} && systemctl is-active ${Installer.shq("openflux-node@$channel")}"
}
