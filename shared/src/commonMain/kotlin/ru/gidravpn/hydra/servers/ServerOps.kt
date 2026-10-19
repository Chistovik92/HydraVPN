package ru.gidravpn.hydra.servers

import org.json.JSONObject

/** Пир AmneziaWG по данным `awg show <iface> dump`: когда было последнее рукопожатие и сколько передано. */
data class AwgPeerStat(val publicKey: String, val endpoint: String, val latestHandshake: Long, val rx: Long, val tx: Long)

/** Что есть на сервере сейчас (кнопка «Проверить», 0.8.0-rc4). */
data class ServerStatus(
    /** Вывод проверки дошёл до маркера `---END`. Оборванный (сеанс упал на полпути) не применяется: иначе запись о службах стёрлась бы. */
    val complete: Boolean = true,
    val checkedAt: Long = System.currentTimeMillis(),
    val osName: String = "",
    val arch: String = "",
    val uptimeSec: Long = 0,
    val load: String = "",
    val mem: String = "",
    val disk: String = "",
    /** Служба AmneziaWG от Hydra установлена (есть unit) и её состояние systemd (`active`, `failed`, …). */
    val awgInstalled: Boolean = false,
    val awgState: String = "",
    val awgPeers: List<AwgPeerStat> = emptyList(),
    /** Каналы узла OpenFlux: имя → состояние systemd. */
    val ofxChannels: Map<String, String> = emptyMap(),
    /** Копии состояния на сервере (`/etc/hydra`): по ним другое устройство берёт сервер под управление. */
    val awgStateJson: String = "",
    val awgServerConf: String = "",
    val ofxStateJson: List<String> = emptyList(),
    /** Панель 3x-ui на сервере (0.8.1): установлена ли, поставила ли её Hydra (метка) и состояние службы x-ui. */
    val xuiInstalled: Boolean = false,
    val xuiByHydra: Boolean = false,
    val xuiState: String = "",
    /** `/etc/x-ui/install-result.env` панели, поставленной Hydra, — чтобы другое устройство взяло её под управление. */
    val xuiResult: String = "",
    /** s-ui на сервере и метки панелей, поставленных Hydra (0.8.1). */
    val suiInstalled: Boolean = false,
    val suiByHydra: Boolean = false,
    val xuiAlirezaByHydra: Boolean = false,
    /** PasarGuard на сервере и метка «поставила Hydra» (0.8.2-a3). */
    val pgInstalled: Boolean = false,
    val pgByHydra: Boolean = false,
    /** Remnawave на сервере и метка «поставила Hydra» (0.8.2-a3). */
    val rwInstalled: Boolean = false,
    val rwByHydra: Boolean = false,
    /** Копия записи панели `/etc/hydra/panel.json`. */
    val panelStateJson: String = "",
    /** Ошибка проверки (сервер недоступен и т. п.); null — проверка прошла. */
    val error: String? = null,
) {
    val awgActive get() = awgState == "active"
}

/**
 * Команды и файлы, которыми Hydra управляет сервером после установки служб (0.8.0-rc4):
 * проверка состояния одной командой, копия состояния в `/etc/hydra` (0700, файлы 0600), полная очистка.
 */
object ServerOps {
    const val STATE_DIR = "/etc/hydra"
    const val AWG_STATE = "$STATE_DIR/awg.json"
    /** Открытые Hydra порты файрвола (`ufw 51820/udp`, `firewalld 51820/udp`) — их закрывает очистка. */
    const val FW_LIST = "$STATE_DIR/fw.list"
    /**
     * Копии баз панелей перед удалением (0.8.1-b): `/root/hydra-backups/<панель>-<дата>.db`, только для root. Каталог вне
     * `/etc/hydra`, поэтому полная очистка его не трогает — пользователей и настройки панели можно вернуть или перенести.
     */
    const val BACKUP_DIR = "/root/hydra-backups"

    /** Где панели хранят базу (SQLite). */
    val PANEL_DBS = mapOf("x-ui" to "/etc/x-ui/x-ui.db", "s-ui" to "/usr/local/s-ui/db/s-ui.db", "pasarguard" to "/var/lib/pasarguard/db.sqlite3")

    /** Скопировать базы стоящих панелей в [BACKUP_DIR] (если базы нет — ничего не делает). */
    fun backupPanelsCommand(): String = "mkdir -p $BACKUP_DIR && chmod 700 $BACKUP_DIR; d=\$(date +%Y%m%d-%H%M%S); ok=0; " +
        PANEL_DBS.entries.joinToString(" ") { (n, f) ->
            "if [ -f $f ]; then if cp -a $f $BACKUP_DIR/$n-\$d.db && [ -s $BACKUP_DIR/$n-\$d.db ] && chmod 600 $BACKUP_DIR/$n-\$d.db; " +
                "then echo \"копия базы $n: $BACKUP_DIR/$n-\$d.db\"; else rm -f $BACKUP_DIR/$n-\$d.db; echo \"ОШИБКА: копия базы $n не создана\"; ok=1; fi; fi;"
        } + " [ \$ok -eq 0 ]"

    /** Шаг строгий: не удалась копия существующей базы — выполнение останавливается, база не удаляется (раньше шаг всегда «успешен»). */
    fun backupPanelsStep() = Step("panel-backup", "Сохранить копию базы панели (пользователи, настройки) в $BACKUP_DIR", backupPanelsCommand(),
        failHint = "Копия базы панели не создана (нет места на диске?) — удаление остановлено, чтобы не потерять пользователей")

    /** Метка «3x-ui поставила Hydra»: только такую панель снимает полная очистка. */
    const val XUI_MARK = "$STATE_DIR/xui.installed"
    fun ofxState(channel: String) = "$STATE_DIR/ofx-$channel.json"

    private const val AWG_ENV = "WG_QUICK_USERSPACE_IMPLEMENTATION=amneziawg-go"

    fun statusCommand(iface: String = "awg0") = buildString {
        append("echo OS=\$(. /etc/os-release 2>/dev/null && echo \"\$PRETTY_NAME\"); ")
        append("echo ARCH=\$(uname -m); ")
        append("echo UPTIME=\$(cut -d. -f1 /proc/uptime 2>/dev/null); ")
        append("echo LOAD=\$(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null); ")
        append("echo MEM=\$(free -m 2>/dev/null | awk '/^Mem:/{print \$3\"/\"\$2\" МБ\"}'); ")
        append("echo DISK=\$(df -h / 2>/dev/null | awk 'NR==2{print \$3\"/\"\$2}'); ")
        append("echo AWG_UNIT=\$([ -f /etc/systemd/system/${AwgInstallPlan.SERVICE}.service ] && echo 1 || echo 0); ")
        append("echo AWG_STATE=\$(systemctl is-active ${AwgInstallPlan.SERVICE} 2>/dev/null); ")
        append("echo OFX=\$(systemctl list-units --all --plain --no-legend 'openflux-node@*' 2>/dev/null | awk '{n=\$1; sub(/^openflux-node@/,\"\",n); sub(/\\.service\$/,\"\",n); printf \"%s:%s \", n, \$3}'); ")
        append("echo XUI=\$([ -e /usr/local/x-ui/x-ui ] && echo 1 || echo 0); ")
        append("echo XUI_MARK=\$([ -f $XUI_MARK ] && echo 1 || echo 0); ")
        append("echo XUI_STATE=\$(systemctl is-active x-ui 2>/dev/null); ")
        append("echo SUI=\$([ -e /usr/local/s-ui ] && echo 1 || echo 0); ")
        append("echo SUI_MARK=\$([ -f ${OtherPanels.SUI.mark} ] && echo 1 || echo 0); ")
        append("echo XUIA_MARK=\$([ -f ${OtherPanels.XUI.mark} ] && echo 1 || echo 0); ")
        append("echo PG=\$([ -e ${PasarGuardPanel.DIR}/docker-compose.yml ] && echo 1 || echo 0); ")
        append("echo PG_MARK=\$([ -f ${PasarGuardPanel.MARK} ] && echo 1 || echo 0); ")
        append("echo RW=\$([ -e ${RemnawavePanel.DIR}/docker-compose.yml ] && echo 1 || echo 0); ")
        append("echo RW_MARK=\$([ -f ${RemnawavePanel.MARK} ] && echo 1 || echo 0); ")
        append("echo '---AWGDUMP'; $AWG_ENV ${AwgInstallPlan.BIN_DIR}/awg show $iface dump 2>/dev/null; ")
        append("echo '---AWGSTATE'; cat $AWG_STATE 2>/dev/null; echo; ")
        append("echo '---AWGCONF'; cat ${AwgInstallPlan.CONF_DIR}/$iface.conf 2>/dev/null; ")
        append("echo '---OFXSTATE'; for f in $STATE_DIR/ofx-*.json; do [ -f \"\$f\" ] && cat \"\$f\" && echo; done; ")
        append("echo '---PANELSTATE'; cat ${OtherPanels.PANEL_STATE} 2>/dev/null; echo; ")
        append("echo '---XUIRESULT'; [ -f $XUI_MARK ] && cat ${XuiPanel.RESULT} 2>/dev/null; ")
        append("echo '---END'")
    }

    fun parseStatus(out: String): ServerStatus {
        val sections = mutableMapOf<String, MutableList<String>>()
        var cur = "head"
        for (l in out.lines()) {
            if (l.startsWith("---")) { cur = l.removePrefix("---").trim(); continue }
            sections.getOrPut(cur) { mutableListOf() } += l
        }
        val kv = sections["head"].orEmpty().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1).trim() } }.toMap()
        val arch = when (kv["ARCH"]) { "x86_64", "amd64" -> "amd64"; "aarch64", "arm64" -> "arm64"; "armv7l", "armv7" -> "armv7"; else -> kv["ARCH"].orEmpty() }
        // dump: первая строка — сам интерфейс, дальше пиры: pub psk endpoint allowed handshake rx tx keepalive
        val peers = sections["AWGDUMP"].orEmpty().filter { it.isNotBlank() }.drop(1).mapNotNull { l ->
            val f = l.split('\t')
            if (f.size < 7) null else AwgPeerStat(f[0], f[2].takeIf { it != "(none)" }.orEmpty(), f[4].toLongOrNull() ?: 0, f[5].toLongOrNull() ?: 0, f[6].toLongOrNull() ?: 0)
        }
        val ofx = kv["OFX"].orEmpty().split(' ').filter { ':' in it }.associate { it.substringBefore(':') to it.substringAfter(':') }
        return ServerStatus(
            complete = out.lines().any { it.trim() == "---END" },
            osName = kv["OS"].orEmpty(), arch = arch, uptimeSec = kv["UPTIME"]?.toLongOrNull() ?: 0, load = kv["LOAD"].orEmpty(),
            mem = kv["MEM"].orEmpty(), disk = kv["DISK"].orEmpty(),
            awgInstalled = kv["AWG_UNIT"] == "1", awgState = kv["AWG_STATE"].orEmpty(), awgPeers = peers, ofxChannels = ofx,
            awgStateJson = sections["AWGSTATE"].orEmpty().joinToString("\n").trim(),
            awgServerConf = sections["AWGCONF"].orEmpty().joinToString("\n").trim(),
            ofxStateJson = sections["OFXSTATE"].orEmpty().filter { it.trim().startsWith("{") },
            xuiInstalled = kv["XUI"] == "1", xuiByHydra = kv["XUI_MARK"] == "1", xuiState = kv["XUI_STATE"].orEmpty(),
            xuiResult = sections["XUIRESULT"].orEmpty().joinToString("\n").trim(),
            suiInstalled = kv["SUI"] == "1", suiByHydra = kv["SUI_MARK"] == "1", xuiAlirezaByHydra = kv["XUIA_MARK"] == "1",
            pgInstalled = kv["PG"] == "1", pgByHydra = kv["PG_MARK"] == "1", rwInstalled = kv["RW"] == "1", rwByHydra = kv["RW_MARK"] == "1",
            panelStateJson = sections["PANELSTATE"].orEmpty().joinToString("\n").trim(),
        )
    }

    // ------------------------------------------------------------------ Копия состояния на сервере

    fun awgStateJson(spec: AwgServerSpec, clients: List<AwgClient>): String =
        JSONObject().put("v", 1).put("awg", spec.toJson()).put("clients", MyServer.clientsToJson(clients)).toString(2)

    fun parseAwgState(json: String): Pair<AwgServerSpec, List<AwgClient>>? = runCatching {
        val o = JSONObject(json)
        AwgServerSpec.fromJson(o.getJSONObject("awg")) to MyServer.clientsFromJson(o.optJSONArray("clients"))
    }.getOrNull()

    private const val MKDIR = "mkdir -p $STATE_DIR && chmod 700 $STATE_DIR"

    fun awgStateSteps(spec: AwgServerSpec, clients: List<AwgClient>) = listOf(
        Step("state-dir", "Каталог настроек Hydra на сервере", MKDIR),
        Step("state", "Сохранить копию настроек на сервере (только для root)", upload = Step.Upload(AWG_STATE, awgStateJson(spec, clients).toByteArray(), 384)),
    )

    fun ofxStateSteps(spec: OfxNodeSpec) = listOf(Step("state-dir", "Каталог настроек Hydra на сервере", MKDIR),
        Step("ofx-state", "Сохранить копию настроек узла на сервере (только для root)", upload = Step.Upload(ofxState(spec.channel), spec.toJson().toString(2).toByteArray(), 384)))

    /** Открыть порт и запомнить это в [FW_LIST] — чтобы очистка сервера закрыла его. */
    fun openPortStep(firewall: String, port: String): Step? = when (firewall) {
        "ufw" -> Step("fw", "Открыть $port в ufw", "ufw allow $port && $MKDIR && echo 'ufw $port' >> $FW_LIST", rollback = "ufw delete allow $port")
        "firewalld" -> Step("fw", "Открыть $port в firewalld",
            "firewall-cmd --permanent --add-port=$port && firewall-cmd --reload && $MKDIR && echo 'firewalld $port' >> $FW_LIST",
            rollback = "firewall-cmd --permanent --remove-port=$port && firewall-cmd --reload")
        else -> null
    }

    /**
     * Открыть порт только для одного адреса (0.8.2-a4: порт ноды — только главному серверу) и запомнить это в [FW_LIST]
     * строкой `ufw-from <ip> <порт/протокол>` — её закрывают [closePortFromCommand] и очистка сервера.
     */
    fun openPortFromStep(firewall: String, port: String, fromIp: String): Step? {
        require(Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(fromIp)) { "Неверный адрес $fromIp" }
        require(Regex("""^\d{1,5}/(tcp|udp)$""").matches(port)) { "Неверный порт $port" }
        val (n, proto) = port.split('/')
        return when (firewall) {
            "ufw" -> Step("fw-from", "Открыть $port в ufw только для $fromIp",
                "ufw allow from $fromIp to any port $n proto $proto && $MKDIR && echo 'ufw-from $fromIp $port' >> $FW_LIST",
                rollback = "ufw delete allow from $fromIp to any port $n proto $proto")
            "firewalld" -> {
                val rule = "rule family=ipv4 source address=$fromIp port port=$n protocol=$proto accept"
                Step("fw-from", "Открыть $port в firewalld только для $fromIp",
                    "firewall-cmd --permanent --add-rich-rule='$rule' && firewall-cmd --reload && $MKDIR && echo 'firewalld-from $fromIp $port' >> $FW_LIST",
                    rollback = "firewall-cmd --permanent --remove-rich-rule='$rule' && firewall-cmd --reload")
            }
            else -> null
        }
    }

    /** Закрыть порт, открытый [openPortFromStep]. */
    fun closePortFromCommand(port: String, fromIp: String): String {
        val (n, proto) = port.split('/')
        return "if grep -qx 'ufw-from $fromIp $port' $FW_LIST 2>/dev/null; then ufw delete allow from $fromIp to any port $n proto $proto >/dev/null 2>&1; fi; " +
            "if grep -qx 'firewalld-from $fromIp $port' $FW_LIST 2>/dev/null; then firewall-cmd --permanent --remove-rich-rule='rule family=ipv4 source address=$fromIp port port=$n protocol=$proto accept' >/dev/null 2>&1 && firewall-cmd --reload >/dev/null 2>&1; fi; " +
            "[ -f $FW_LIST ] && sed -i '\\|-from $fromIp $port\$|d' $FW_LIST; true"
    }

    /** Закрыть порт, если его открывала Hydra (есть в [FW_LIST]). */
    fun closePortCommand(port: String) =
        "if grep -qx 'ufw $port' $FW_LIST 2>/dev/null; then ufw delete allow $port >/dev/null 2>&1; fi; " +
        "if grep -qx 'firewalld $port' $FW_LIST 2>/dev/null; then firewall-cmd --permanent --remove-port=$port >/dev/null 2>&1 && firewall-cmd --reload >/dev/null 2>&1; fi; " +
        "[ -f $FW_LIST ] && sed -i '\\| $port\$|d' $FW_LIST; true"

    // ------------------------------------------------------------------ Полная очистка

    /**
     * Убрать с сервера всё, что ставила Hydra (кнопка «Очистить сервер»), — независимо от того, что записано в приложении:
     * службу и файлы AmneziaWG, все каналы и ядро узла OpenFlux (штатный `uninstall` его скрипта), открытые Hydra порты
     * файрвола, настройки пересылки, копии состояния и временные файлы. Чужие программы и системные пакеты не трогаются.
     */
    fun wipeSteps(): List<Step> = listOf(
        Step("wipe-awg", "Остановить и удалить AmneziaWG (служба ${AwgInstallPlan.SERVICE}, конфиги, программы)",
            "systemctl disable --now ${AwgInstallPlan.SERVICE} >/dev/null 2>&1; " +
                "rm -f /etc/systemd/system/${AwgInstallPlan.SERVICE}.service; " +
                "for f in ${AwgInstallPlan.CONF_DIR}/*.conf; do [ -f \"\$f\" ] && grep -q '^PostUp = iptables -I FORWARD' \"\$f\" && rm -f \"\$f\"; done; " +
                "rmdir ${AwgInstallPlan.CONF_DIR} /etc/amnezia 2>/dev/null; " +
                "rm -f ${AwgInstallPlan.BIN_DIR}/amneziawg-go ${AwgInstallPlan.BIN_DIR}/awg ${AwgInstallPlan.BIN_DIR}/awg-quick " +
                "${AwgInstallPlan.BIN_DIR}/*.hydra-new /etc/sysctl.d/99-hydra-awg.conf; " +
                "systemctl daemon-reload; echo 'AmneziaWG: удалено'"),
        Step("wipe-ofx", "Удалить узел OpenFlux (все каналы, ядро, автообновление)",
            "if [ -f /opt/openflux-node/node-install.sh ]; then sh /opt/openflux-node/node-install.sh uninstall; else echo 'OpenFlux: не установлен'; fi; " +
                "rm -f ${OpenFluxNode.SCRIPT}; true"),
        backupPanelsStep(),
        Step("wipe-xui", "Удалить панель 3x-ui, если её ставила Hydra",
            "if [ -f $XUI_MARK ] && [ -x /usr/bin/x-ui ]; then printf 'y\\n' | /usr/bin/x-ui uninstall >/dev/null 2>&1; echo '3x-ui: удалена'; " +
                "else echo '3x-ui: не ставилась Hydra'; fi; true"),
        Step("wipe-panels", "Удалить панели s-ui, x-ui, PasarGuard и Remnawave, если их ставила Hydra",
            listOf(OtherPanels.SUI, OtherPanels.XUI).joinToString(" ") { s ->
                "if [ -f ${s.mark} ]; then ${OtherPanels.uninstallCommand(s)}; echo '${s.kind.title}: удалена'; fi;"
            } + " if [ -f ${PasarGuardPanel.MARK} ]; then ${PasarGuardPanel.uninstallCommand()}; echo 'PasarGuard: удалена'; fi;" +
                " if [ -f ${RemnawavePanel.MARK} ]; then if ( ${RemnawavePanel.backupCommand()} ); then ${RemnawavePanel.uninstallCommand()}; echo 'Remnawave: удалена'; " +
                "else echo 'Remnawave: НЕ удалена — копия базы не создана'; fi; fi;" +
                " if [ -f ${PanelNodes.PG_NODE_MARK} ]; then ${PanelNodes.pgNodeUninstallCommand()}; echo 'нода PasarGuard: удалена'; fi;" +
                " if [ -f ${PanelNodes.RW_NODE_MARK} ]; then ${PanelNodes.rwNodeUninstallCommand()}; echo 'нода Remnawave: удалена'; fi;" +
                " rm -f ${OtherPanels.PANEL_STATE}; true"),
        Step("wipe-fw", "Закрыть порты, которые открывала Hydra",
            "if [ -f $FW_LIST ]; then while read -r fw p q; do case \"\$fw\" in " +
                "ufw) ufw delete allow \"\$p\" >/dev/null 2>&1 && echo \"закрыт \$p (ufw)\";; " +
                "ufw-from) ufw delete allow from \"\$p\" to any port \"\${q%/*}\" proto \"\${q#*/}\" >/dev/null 2>&1 && echo \"закрыт \$q для \$p (ufw)\";; " +
                "firewalld-from) firewall-cmd --permanent --remove-rich-rule=\"rule family=ipv4 source address=\$p port port=\${q%/*} protocol=\${q#*/} accept\" >/dev/null 2>&1 && echo \"закрыт \$q для \$p (firewalld)\";; " +
                "firewalld) firewall-cmd --permanent --remove-port=\"\$p\" >/dev/null 2>&1 && echo \"закрыт \$p (firewalld)\";; esac; done < $FW_LIST; " +
                "command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --reload >/dev/null 2>&1; fi; true"),
        Step("wipe-state", "Удалить копии настроек и временные файлы Hydra",
            "rm -rf $STATE_DIR /tmp/.hydra-* /root/.hydra-*; echo 'Hydra: следов не осталось'"),
        Step("wipe-check", "Проверить, что ничего не осталось",
            "! systemctl is-active --quiet ${AwgInstallPlan.SERVICE} && [ ! -e /etc/systemd/system/${AwgInstallPlan.SERVICE}.service ] && " +
                "[ ! -e ${AwgInstallPlan.BIN_DIR}/amneziawg-go ] && [ ! -e /opt/openflux-node ] && [ ! -e $STATE_DIR ]",
            failHint = "Часть файлов удалить не удалось"),
    )
}
