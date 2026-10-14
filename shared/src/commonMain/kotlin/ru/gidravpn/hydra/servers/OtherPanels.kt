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

    fun spec(kind: PanelKind): Spec? = when (kind) { PanelKind.SUI -> SUI; PanelKind.XUI -> XUI; PanelKind.XUI3 -> null }

    fun latestTag(s: Spec, timeoutMs: Int = 15_000): String {
        val c = URL("https://api.github.com/repos/${s.repo}/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.setRequestProperty("Accept", "application/vnd.github+json")
        val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        return JSONObject(text).getString("tag_name").also { require(XuiPanel.validTag(it)) { "Неожиданный тег $it" } }
    }

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
    }

    fun installSteps(s: Spec, p: Params, openFirewall: Boolean, firewall: String): List<Step> = buildList {
        require(XuiPanel.validTag(p.tag)) { "Неверный тег ${p.tag}" }
        val sc = script(s)
        // 3x-ui и x-ui (alireza0) ставятся в один и тот же каталог — вторая затёрла бы первую
        add(Step("panel-exists", "Проверить, что ${s.kind.title} и другой панели в ${s.home} ещё нет", "test ! -e ${s.home}",
            failHint = "На сервере уже стоит панель в ${s.home} — удалите её или подключите существующую"))
        add(Step("panel-fetch", "Загрузить установщик ${s.kind.title} ${p.tag} (закреплённый тег)",
            "(curl -fsSL ${Installer.shq(scriptUrl(s, p.tag))} -o $sc || wget -q -O $sc ${Installer.shq(scriptUrl(s, p.tag))}) && chmod 700 $sc && sha256sum $sc",
            rollback = "rm -f $sc", failHint = "Не удалось скачать установщик с GitHub"))
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

    /** Шаг сохранения копии записи панели на сервере (`/etc/hydra/panel.json`, 0600). */
    fun stateSteps(panel: PanelInstance) = listOf(
        Step("panel-state-dir", "Каталог настроек Hydra на сервере", "mkdir -p ${ServerOps.STATE_DIR} && chmod 700 ${ServerOps.STATE_DIR}"),
        Step("panel-state", "Сохранить копию данных панели на сервере (только для root)", upload = Step.Upload(PANEL_STATE, panel.toJson().toString(2).toByteArray(), 384)),
    )
}
