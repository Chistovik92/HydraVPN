package ru.gidravpn.hydra.servers

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Панели s-ui и x-ui (alireza0) — 0.8.1. В отличие от 3x-ui, API-токена у них нет: Hydra ставит панель официальным установщиком
 * закреплённой версии, сама задаёт логин, пароль, порт и путь командой панели (`sui admin`/`sui setting`, `x-ui setting`) и
 * даёт вход на сайт панели; пользователей человек заводит на сайте. Данные входа — в записи сервера (зашифрованное хранилище
 * приложения) и копией в `/etc/hydra/panel.json` (0600, только root), чтобы панель видело второе устройство.
 */
object OtherPanels {
    /** Копия записи панели на сервере (для всех видов панелей). */
    const val PANEL_STATE = "${ServerOps.STATE_DIR}/panel.json"

    data class Spec(
        val kind: PanelKind,
        val repo: String,
        /** Каталог программы — по нему видно, что панель стоит. */
        val home: String,
        val service: String,
        /** Метка «поставила Hydra». */
        val mark: String,
    )

    val SUI = Spec(PanelKind.SUI, "alireza0/s-ui", "/usr/local/s-ui", "s-ui", "${ServerOps.STATE_DIR}/sui.installed")
    val XUI = Spec(PanelKind.XUI, "alireza0/x-ui", "/usr/local/x-ui", "x-ui", "${ServerOps.STATE_DIR}/xui-alireza.installed")

    fun spec(kind: PanelKind): Spec? = when (kind) { PanelKind.SUI -> SUI; PanelKind.XUI -> XUI; PanelKind.XUI3, PanelKind.PASARGUARD, PanelKind.REMNAWAVE -> null }

    /** Тег, который ставит Hydra (0.8.2-rc2): проверенный выпуск из [ScriptPins] (скрипт сверяется по SHA-256), а не «последний» у апстрима. */
    @Suppress("UNUSED_PARAMETER")
    fun latestTag(s: Spec, timeoutMs: Int = 15_000): String = if (s.kind == PanelKind.SUI) ScriptPins.SUI_TAG else ScriptPins.XUI_TAG

    fun scriptUrl(s: Spec, tag: String) = "https://raw.githubusercontent.com/${s.repo}/$tag/install.sh"

    /** Учётные данные и адрес, которые задаёт Hydra. */
    class Params(
        val tag: String,
        val username: String = "hy" + XuiPanel.random(6),
        val password: String = XuiPanel.random(16),
        val port: Int = 20000 + SecureRandom().nextInt(40000),
        val path: String = XuiPanel.random(12),
    )

    private fun script(s: Spec) = "/root/.hydra-${s.service}-install.sh"
    private fun cfg(s: Spec) = "/tmp/.hydra-${s.service}-cfg.sh"
    private fun log(s: Spec) = "/tmp/.hydra-${s.service}-install.log"

    /** Команды настройки панели (идут файлом 0600 — пароль не виден в `ps`). */
    fun configScript(s: Spec, p: Params): String = when (s.kind) {
        PanelKind.SUI -> "set -e\n/usr/local/s-ui/sui setting -port ${p.port} -path '/${p.path}/'\n" +
            "/usr/local/s-ui/sui admin -username '${p.username}' -password '${p.password}'\nsystemctl restart s-ui\n"
        PanelKind.XUI -> "set -e\n/usr/local/x-ui/x-ui setting -username '${p.username}' -password '${p.password}' -port ${p.port} -webBasePath '/${p.path}/'\n" +
            "systemctl restart x-ui\n"
        PanelKind.XUI3 -> error("3x-ui ставится через XuiPanel")
        PanelKind.PASARGUARD, PanelKind.REMNAWAVE -> error("${s.kind.title} ставится в Docker своим классом")
    }

    fun installSteps(s: Spec, p: Params, openFirewall: Boolean, firewall: String): List<Step> = buildList {
        require(XuiPanel.validTag(p.tag)) { "Неверный тег ${p.tag}" }
        val sc = script(s)
        // 3x-ui и x-ui (alireza0) ставятся в один и тот же каталог — вторая затёрла бы первую
        add(Step("panel-exists", "Проверить, что ${s.kind.title} и другой панели в ${s.home} ещё нет", "test ! -e ${s.home}",
            failHint = "На сервере уже стоит панель в ${s.home} — удалите её или подключите существующую"))
        add(Step("panel-fetch", "Загрузить установщик ${s.kind.title} ${p.tag} (закреплённый тег)",
            "(curl -fsSL -m 180 ${Installer.shq(scriptUrl(s, p.tag))} -o $sc || wget -q -O $sc ${Installer.shq(scriptUrl(s, p.tag))}) && chmod 700 $sc && sha256sum $sc" +
                ScriptPins.verify(s.repo, p.tag, "install.sh", sc).let { if (it.isEmpty()) "" else " && $it" },
            rollback = "rm -f $sc", failHint = "Не удалось скачать установщик с GitHub или его SHA-256 не совпал с проверенным"))
        val run = when (s.kind) {
            // s-ui спрашивает «изменить настройки?» — отвечаем «нет»: логин и порт задаём сами следующим шагом
            PanelKind.SUI -> "printf 'n\\n' | SUI_LANG=en bash $sc ${p.tag}"
            else -> "XUI_NONINTERACTIVE=1 bash $sc ${p.tag} < /dev/null"
        }
        add(Step("panel-install", "Установить ${s.kind.title} ${p.tag}",
            // журнал установщика может содержать случайные логин и пароль: при успехе не читаем, при сбое — хвост с вырезанием
            "$run > ${log(s)} 2>&1; rc=\$?; if [ \$rc -ne 0 ]; then tail -n 6 ${log(s)} | sed -E 's/(assword|sername)[^:=]*[:=].*/\\1: ***/'; fi; rm -f ${log(s)}; exit \$rc",
            rollback = uninstallCommand(s), failHint = "Установка ${s.kind.title} не удалась"))
        add(Step("panel-config", "Задать логин, пароль, порт ${p.port} и путь панели (файл 0600; данные не показываются)",
            "sh ${cfg(s)}; rc=\$?; rm -f ${cfg(s)}; exit \$rc", upload = Step.Upload(cfg(s), configScript(s, p).toByteArray(), 384),
            failHint = "Не удалось задать логин и порт панели"))
        if (openFirewall) ServerOps.openPortStep(firewall, "${p.port}/tcp")?.let { add(Step("panel-fw", "Открыть порт панели ${p.port}/tcp", it.command, rollback = it.rollback)) }
        add(Step("panel-active", "Проверить: служба ${s.service} запущена и панель отвечает",
            "sleep 2; systemctl is-active ${s.service} && curl -s -o /dev/null -w '%{http_code}\\n' --max-time 10 http://127.0.0.1:${p.port}/${p.path}/",
            accept = { r -> r.out.lineSequence().any { it.trim() == "active" } && r.out.lineSequence().any { it.trim().matches(Regex("[23]\\d\\d")) } },
            failHint = "Панель не отвечает на порту ${p.port}"))
        add(Step("panel-mark", "Отметить, что панель поставила Hydra", "mkdir -p ${ServerOps.STATE_DIR} && chmod 700 ${ServerOps.STATE_DIR} && touch ${s.mark} && rm -f $sc"))
    }

    fun uninstallCommand(s: Spec) =
        "systemctl disable --now ${s.service} >/dev/null 2>&1; rm -f /etc/systemd/system/${s.service}.service; systemctl daemon-reload; " +
            "rm -rf /etc/${s.service} ${s.home} /usr/bin/${s.service}; rm -f ${s.mark} $PANEL_STATE"

    /** Служба панели: 3x-ui и x-ui называются одинаково. */
    fun serviceOf(kind: PanelKind) = if (kind == PanelKind.SUI) "s-ui" else "x-ui"

    /** База настроек панели (SQLite; у всех трёх — таблица `settings(key, value)`). */
    fun dbPath(kind: PanelKind) = ServerOps.PANEL_DBS.getValue(serviceOf(kind))

    private val SAFE_VALUE = Regex("^[A-Za-z0-9./:_-]*$")

    /**
     * Записать настройки панели прямо в её базу (0.8.1-rc2): у s-ui нет команды для сертификата и адреса прослушивания,
     * у x-ui и 3x-ui — для адреса и сертификата подписки. Строки, которых ещё нет (панель держит значение по умолчанию), добавляются.
     * Нет `sqlite3` — ставится пакетом. Значения — только пути и адреса (проверяется), поэтому подставляются в SQL как есть.
     */
    fun settingsCommand(kind: PanelKind, values: Map<String, String>): String {
        require(values.all { (k, v) -> Regex("^[A-Za-z]+$").matches(k) && SAFE_VALUE.matches(v) }) { "Недопустимое значение настройки" }
        val sql = values.entries.joinToString(" ") { (k, v) ->
            "UPDATE settings SET value='$v' WHERE key='$k'; INSERT INTO settings(key, value) SELECT '$k', '$v' WHERE NOT EXISTS (SELECT 1 FROM settings WHERE key='$k');"
        }
        return "(command -v sqlite3 >/dev/null || (DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=120 install -y -q sqlite3 || (DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=120 update -q && DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=120 install -y -q sqlite3) || dnf install -y sqlite || yum install -y sqlite) >/dev/null 2>&1) && " +
            "sqlite3 ${dbPath(kind)} ${Installer.shq(sql)}"
    }

    /** Шаг «панель отвечает на сервере» по `http`/`https` (сертификат на внешний адрес — `-k`). */
    fun answersStep(id: String, title: String, scheme: String, port: Int, path: String) = Step(id, title,
        "sleep 1; curl -sk -o /dev/null -w '%{http_code}\\n' --max-time 10 ${Installer.shq("$scheme://127.0.0.1:$port/${path.trim('/').let { if (it.isEmpty()) "" else "$it/" }}")}",
        accept = { r -> r.out.lineSequence().any { it.trim().matches(Regex("[23]\\d\\d")) } }, failHint = "Панель не отвечает на порту $port")

    /**
     * Закрыть порт панели извне или открыть снова (0.8.1-rc2, «ничего наружу без согласия»): панель слушает только 127.0.0.1
     * (`webListen`), правило файрвола, открытое Hydra, снимается. Подписка 3x-ui остаётся на своём порту — клиентам она нужна.
     * Открыть снова — пустой `webListen` (все адреса) и порт в файрволе, если он включён.
     */
    fun listenSteps(p: PanelInstance, local: Boolean, firewall: String): List<Step> {
        val url = java.net.URL(p.baseUrl)
        val port = url.port.takeIf { it > 0 } ?: error("В адресе панели нет порта")
        val svc = serviceOf(p.kind)
        return listOfNotNull(
            Step("panel-listen", if (local) "Панель слушает только сам сервер (127.0.0.1)" else "Панель снова слушает все адреса",
                settingsCommand(p.kind, mapOf("webListen" to if (local) "127.0.0.1" else "")) + " && systemctl restart $svc && sleep 2 && systemctl is-active $svc",
                accept = { it.out.lineSequence().any { l -> l.trim() == "active" } }, failHint = "Панель не запустилась"),
            Step("panel-listen-check", "Проверить, на каком адресе слушает панель", "ss -ltnH 'sport = :$port' | awk '{print \$4}'",
                accept = { r -> val a = r.out.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    a.isNotEmpty() && (if (local) a.all { it.startsWith("127.0.0.1:") } else a.any { !it.startsWith("127.") && !it.startsWith("[::1]") }) },
                failHint = if (local) "Панель всё ещё слушает внешний адрес" else "Панель не слушает внешний адрес"),
            answersStep("panel-local-ok", "Проверить: панель отвечает на сервере", url.protocol, port, url.path),
            if (local) Step("panel-fw-close", "Закрыть порт панели $port/tcp, если его открывала Hydra", ServerOps.closePortCommand("$port/tcp"))
            else ServerOps.openPortStep(firewall, "$port/tcp")?.let { Step("panel-fw", "Открыть порт панели $port/tcp", it.command, rollback = it.rollback) },
        )
    }

    /** Шаг сохранения копии записи панели на сервере (`/etc/hydra/panel.json`, 0600). */
    fun stateSteps(panel: PanelInstance) = listOf(
        Step("panel-state-dir", "Каталог настроек Hydra на сервере", "mkdir -p ${ServerOps.STATE_DIR} && chmod 700 ${ServerOps.STATE_DIR}"),
        Step("panel-state", "Сохранить копию данных панели на сервере (только для root)", upload = Step.Upload(PANEL_STATE, panel.toJson().toString(2).toByteArray(), 384)),
    )
}
