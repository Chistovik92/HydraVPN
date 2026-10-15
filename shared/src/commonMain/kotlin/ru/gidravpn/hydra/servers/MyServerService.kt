package ru.gidravpn.hydra.servers

/** Итог операции над сервером. */
sealed interface DeployResult {
    data class Ok(val server: MyServer, val links: List<String> = emptyList()) : DeployResult
    data class Failed(val reason: String) : DeployResult
}

/**
 * Сценарии «Моих серверов» поверх SSH-сеанса: проверка, установка служб (AmneziaWG, узел OpenFlux), клиенты, удаление, очистка.
 * Не знает ни об интерфейсе, ни о хранении — вызывающий передаёт сеанс и сохраняет результат.
 */
class MyServerService(private val bundleFor: (arch: String) -> AwgBundle) {

    fun preflight(ssh: SshSession, port: Int): Preflight = PreflightCheck.parse(ssh.exec(PreflightCheck.command(port)).out)

    fun status(ssh: SshSession, iface: String = "awg0"): ServerStatus = ServerOps.parseStatus(ssh.exec(ServerOps.statusCommand(iface)).out)

    /**
     * Сверить запись приложения с сервером (0.8.0-rc4). Сервер — источник правды: копия `/etc/hydra/awg.json` с него
     * побеждает (клиентов могли добавить с другого устройства); если копии нет, а служба стоит — восстанавливаем по конфигу
     * сервера; если службы нет — убираем её из записи.
     * @return обновлённая запись и нужно ли записать копию состояния на сервер (её не было — сервер поставлен rc1–rc3).
     */
    fun reconcile(s: MyServer, st: ServerStatus): Pair<MyServer, Boolean> {
        var r = s.copy(osName = st.osName.ifBlank { s.osName }, arch = st.arch.ifBlank { s.arch })
        var writeState = false
        if (st.awgInstalled) {
            val fromState = st.awgStateJson.takeIf { it.isNotBlank() }?.let(ServerOps::parseAwgState)
            when {
                fromState != null -> {
                    // закрытые ключи клиентов, известные только этому устройству, не теряем
                    val known = s.clients.associateBy { it.publicKey }
                    val merged = fromState.second.map { c -> if (c.privateKey.isEmpty()) known[c.publicKey]?.let { c.copy(privateKey = it.privateKey) } ?: c else c }
                    r = r.copy(awg = fromState.first.copy(host = s.host), clients = merged)
                }
                s.awg != null -> writeState = true
                else -> AwgServerSpec.fromServerConf(st.awgServerConf, s.host)?.let { (spec, cl) -> r = r.copy(awg = spec, clients = cl); writeState = true }
            }
        } else if (s.awg != null) r = r.copy(awg = null, clients = emptyList())

        // панель, поставленная по SSH, исчезла с сервера — убираем; подключённую без SSH (panelOnly) не трогаем
        if (s.panel != null && !s.panelOnly) {
            val present = when (s.panel.kind) { PanelKind.SUI -> st.suiInstalled; PanelKind.PASARGUARD -> st.pgInstalled; PanelKind.REMNAWAVE -> st.rwInstalled; else -> st.xuiInstalled }
            if (!present) r = r.copy(panel = null)
        }
        // копия записи панели на сервере (любой вид) — второе устройство берёт панель под управление
        if (r.panel == null && st.panelStateJson.trim().startsWith("{")) {
            runCatching { PanelInstance.fromJson(org.json.JSONObject(st.panelStateJson)) }.getOrNull()
                ?.takeIf { when (it.kind) { PanelKind.SUI -> st.suiInstalled; PanelKind.PASARGUARD -> st.pgInstalled; PanelKind.REMNAWAVE -> st.rwInstalled; else -> st.xuiInstalled } }
                ?.let { r = r.copy(panel = it) }
        }
        // панель поставлена Hydra с другого устройства — берём под управление по её install-result.env
        if (r.panel == null && st.xuiInstalled && st.xuiByHydra && st.xuiResult.isNotBlank()) {
            val res = XuiPanel.parseResult(st.xuiResult)
            val token = res["XUI_API_TOKEN"].orEmpty()
            val port = res["XUI_PANEL_PORT"]?.toIntOrNull()
            if (token.isNotBlank() && port != null) {
                val path = res["XUI_WEB_BASE_PATH"].orEmpty().trim('/')
                r = r.copy(panel = PanelInstance(PanelKind.XUI3, "http://${if (s.host.contains(':')) "[${s.host}]" else s.host}:$port/$path",
                    res["XUI_USERNAME"].orEmpty(), res["XUI_PASSWORD"].orEmpty(), token, res["XUI_VERSION"].orEmpty()))
            }
        }

        val ofxStates = st.ofxStateJson.mapNotNull { runCatching { OfxNodeSpec.fromJson(org.json.JSONObject(it)) }.getOrNull() }
        val ofx = s.ofx?.takeIf { it.channel in st.ofxChannels } ?: ofxStates.firstOrNull { it.channel in st.ofxChannels }?.copy(host = s.host)
        r = r.copy(ofx = ofx)
        return r to writeState
    }

    // ---------------------------------------------------------------------------------------------------- AmneziaWG

    /** Шаги, которые будут выполнены, — для показа до запуска. */
    fun planPreview(spec: AwgServerSpec, pre: Preflight, openFirewall: Boolean): List<Step> =
        AwgInstallPlan.steps(spec, emptyList(), AwgBundle(pre.arch, dummyBundle()), openFirewall, pre.firewall)

    fun deployAwg(srv: MyServer, generation: AwgGeneration, port: Int, openFirewall: Boolean, pre: Preflight, installer: Installer, onLog: (String) -> Unit): DeployResult {
        val spec = AwgServerSpec.create(srv.host, port, generation)
        val first = AwgServerSpec.newClient("Hydra", spec.nextAddress(emptyList())!!)
        val bundle = try { bundleFor(pre.arch).also { onLog("Серверный пакет AmneziaWG (${pre.arch}) получен, SHA-256 сверены") } }
            catch (e: Exception) { return DeployResult.Failed(e.message ?: "Не удалось получить серверный пакет") }
        installer.addSecret(spec.privateKey); installer.addSecret(first.privateKey); installer.addSecret(first.presharedKey)
        val ok = installer.run(AwgInstallPlan.steps(spec, listOf(first), bundle, openFirewall, pre.firewall))
        if (!ok) return DeployResult.Failed("Установка остановлена")
        return DeployResult.Ok(srv.copy(awg = spec, clients = listOf(first), arch = pre.arch, osName = pre.osName.ifBlank { srv.osName }),
            listOf(spec.clientConf(first)))
    }

    fun addClient(s: MyServer, name: String, installer: Installer): Pair<MyServer, AwgClient>? {
        val spec = s.awg ?: return null
        val addr = spec.nextAddress(s.clients.map { it.address }) ?: return null
        val c = AwgServerSpec.newClient(name, addr)
        installer.addSecret(c.privateKey); installer.addSecret(c.presharedKey)
        val all = s.clients + c
        if (!installer.run(AwgInstallPlan.applyClientsSteps(spec, all))) return null
        return s.copy(clients = all) to c
    }

    fun removeClient(s: MyServer, name: String, installer: Installer): MyServer? {
        val spec = s.awg ?: return null
        val all = s.clients.filterNot { it.name == name }
        if (!installer.run(AwgInstallPlan.applyClientsSteps(spec, all))) return null
        return s.copy(clients = all)
    }

    fun uninstallAwg(s: MyServer, installer: Installer): MyServer? =
        s.awg?.let { if (installer.run(AwgInstallPlan.uninstallSteps(it))) s.copy(awg = null, clients = emptyList()) else null }

    fun restartAwg(installer: Installer): Boolean = installer.run(listOf(
        Step("restart", "Перезапустить службу ${AwgInstallPlan.SERVICE}", "systemctl restart ${AwgInstallPlan.SERVICE} && systemctl is-active ${AwgInstallPlan.SERVICE}",
            failHint = "Служба не запустилась (journalctl -u ${AwgInstallPlan.SERVICE})"),
    ))

    // ---------------------------------------------------------------------------------------------------- OpenFlux

    /** Что будет выполнено при установке узла OpenFlux — для показа до запуска (порт выберет `plan` на сервере). */
    fun planPreviewOfx(spec: OfxNodeSpec): List<Step> = OpenFluxNode.fetchSteps() +
        Step("ofx-plan", "Спросить у скрипта план установки и порт (ничего не меняет)", "sh ${OpenFluxNode.SCRIPT} plan") +
        OpenFluxNode.applySteps(spec) + ServerOps.ofxStateSteps(spec)

    fun deployOfx(
        ssh: SshSession, srv: MyServer, channel: String, vyandex: String, mailru: String, cupsonline: String, autoupdate: Boolean,
        installer: Installer, onLog: (String) -> Unit,
    ): DeployResult {
        val key = OfxNodeSpec.newKey()
        installer.addSecret(key)
        var spec = OfxNodeSpec(srv.host, channel, 0, key, vyandex, mailru, cupsonline, autoupdate)
        if (!installer.run(OpenFluxNode.fetchSteps())) return DeployResult.Failed("Установка остановлена")
        // plan: только чтение; порт выбирает скрипт (свободный и не занятый другими каналами)
        val cfg = "/tmp/.hydra-ofx-plan.cfg"
        ssh.upload(spec.config(false).toByteArray(), cfg, 384)
        val plan = ssh.exec(OpenFluxNode.planCommand(cfg))
        val port = OpenFluxNode.portOfPlan(plan.out)
            ?: return DeployResult.Failed(OpenFluxNode.errorOf(plan.out) ?: "Скрипт не предложил порт: ${plan.out.trim().takeLast(200)}")
        onLog("Порт узла: $port/tcp")
        spec = spec.copy(port = port)
        if (!installer.run(OpenFluxNode.applySteps(spec) + ServerOps.ofxStateSteps(spec))) return DeployResult.Failed("Установка остановлена")
        return DeployResult.Ok(srv.copy(ofx = spec), listOf(spec.shareLink(srv.name)))
    }

    fun uninstallOfx(s: MyServer, installer: Installer): MyServer? =
        s.ofx?.let { if (installer.run(OpenFluxNode.removeSteps(it.channel))) s.copy(ofx = null) else null }

    fun restartOfx(s: MyServer, installer: Installer): Boolean = s.ofx?.let {
        installer.run(listOf(Step("restart", "Перезапустить узел OpenFlux «${it.channel}»", OpenFluxNode.restartCommand(it.channel), failHint = "Узел не запустился")))
    } ?: false

    // ---------------------------------------------------------------------------------------------------- Панель 3x-ui (0.8.1)

    /** Что будет выполнено при установке панели — для показа до запуска. */
    fun planPreviewXui(tag: String, openFirewall: Boolean, pre: Preflight, sslIp: Boolean = false, mask: XuiPanel.RealityMask = XuiPanel.RealityMask.VK,
                       transport: XuiPanel.Transport = XuiPanel.Transport.GRPC): List<Step> =
        XuiPanel.installSteps(XuiPanel.InstallParams(tag, "••••", "••••", 0, "••••", sslIp), openFirewall, pre.firewall) + listOf(
            Step("xui-access", "Создать доступ: инбаунд VLESS + ${transport.title} + Reality (маскировка под ${mask.sni}) и клиент — через API панели, по токену",
                "curl … /panel/api/inbounds/add, /panel/api/clients/add"),
            Step("xui-import", "Добавить подписку клиента в «Серверы» — правки на сайте панели будут приходить сами"),
        )

    fun deployXui(
        ssh: SshSession, srv: MyServer, tag: String, accessPort: Int, openFirewall: Boolean, pre: Preflight,
        installer: Installer, sslIp: Boolean = false, mask: XuiPanel.RealityMask = XuiPanel.RealityMask.VK,
        transport: XuiPanel.Transport = XuiPanel.Transport.GRPC, onLog: (String) -> Unit,
    ): DeployResult {
        // сертификат: на IPv4 — профиль shortlived Let's Encrypt, на имя — обычный; IPv6-адрес установщик 3x-ui не поддерживает
        val v4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(srv.host)
        val ssl = sslIp && (v4 || ':' !in srv.host)
        val params = XuiPanel.InstallParams(tag, sslIp = ssl, domain = if (ssl && !v4) srv.host else "")
        installer.addSecret(params.password); installer.addSecret(params.username)
        if (!installer.run(XuiPanel.installSteps(params, openFirewall, pre.firewall))) return DeployResult.Failed("Установка остановлена")
        val res = XuiPanel.parseResult(ssh.exec("cat ${XuiPanel.RESULT}").out)
        val token = res["XUI_API_TOKEN"].orEmpty()
        val port = res["XUI_PANEL_PORT"]?.toIntOrNull() ?: params.port
        val path = (res["XUI_WEB_BASE_PATH"] ?: params.webPath).trim('/')
        if (token.isBlank()) return DeployResult.Failed("Установщик не выдал API-токен (${XuiPanel.RESULT})")
        installer.addSecret(token)
        // HTTPS: XUI_ACCESS_URL установщик пишет с https:// даже тогда, когда сертификат не выдался (проверено в rc2 на стенде) —
        // поэтому спрашиваем саму панель: отвечает ли она по https на сервере
        // (панель поднимается несколько секунд; http-запрос к https-панели Go отвечает 400 — его успехом не считаем)
        val https = ssl && ssh.exec("for i in \$(seq 1 20); do for s in https http; do c=\$(curl -sk -o /dev/null -w '%{http_code}' --max-time 5 \$s://127.0.0.1:$port/$path/); " +
            "case \$c in 2*|3*) echo \$s; exit 0;; esac; done; sleep 2; done; echo none").out.trim().lines().lastOrNull() == "https"
        if (ssl) onLog(if (https) "Сертификат на IP выдан — панель и подписка по HTTPS" else "Сертификат выдать не удалось (порт 80 закрыт снаружи?) — панель пока по HTTP")
        val scheme = if (https) "https" else "http"
        val api = XuiApi(SshXuiTransport(ssh, "$scheme://127.0.0.1:$port/$path", token, srv.host))
        try {
            val version = res["XUI_VERSION"].orEmpty().ifBlank { tag }
            waitPanelReady(api, onLog) // служба запущена, но порт панель открывает не сразу; заодно проверяется токен
            onLog("Токен принят, панель отвечает")
            val (priv, pub) = api.newRealityKeys()
            val inboundId = api.addInbound(XuiPanel.defaultInbound(accessPort, priv, pub, mask, remark = "Hydra", transport = transport))
            if (openFirewall) ServerOps.openPortStep(pre.firewall, "$accessPort/tcp")?.let { installer.run(listOf(it)) }
            val email = "hydra-1"
            val subId = XuiPanel.random(16).lowercase()
            api.addClient(email, subId, listOf(inboundId), flow = "")
            val links = api.links(email)
            if (links.isEmpty()) return DeployResult.Failed("Панель не выдала ссылку клиента")
            onLog("Создан инбаунд VLESS + ${transport.title} + Reality (маскировка под ${mask.sni}) на порту $accessPort и клиент $email")
            // подписка вместо голой ссылки: правки инбаунда на сайте панели приложение заберёт само (запрос владельца 15.10.2026)
            val sub = runCatching { api.subscriptionUrl(srv.host, subId) }.getOrNull()
            if (sub != null) onLog("Подписка клиента: ${sub.substringBeforeLast('/')}/…")
            val panel = PanelInstance(PanelKind.XUI3, "$scheme://${if (srv.host.contains(':')) "[${srv.host}]" else srv.host}:$port/$path",
                params.username, params.password, token, version, inboundId, email)
            installer.run(OtherPanels.stateSteps(panel))
            return DeployResult.Ok(srv.copy(panel = panel), sub?.let { listOf(it) } ?: links)
        } catch (e: XuiApiException) {
            return DeployResult.Failed("Панель установлена, но доступ создать не удалось: ${e.message}. Адрес: http://${srv.host}:$port/$path")
        }
    }

    /**
     * HTTPS для уже стоящей панели (3x-ui — 0.8.1-rc1, s-ui и x-ui — 0.8.1-rc2): сертификат Let's Encrypt на IP сервера (или на
     * домен) — [XuiPanel.httpsSteps]. Адрес панели в записи переходит на `https://`; подписки после этого тоже отдаются по HTTPS.
     * Панель не поднялась с сертификатом — пути к нему стираются, панель возвращается на HTTP.
     */
    fun enablePanelHttps(s: MyServer, pre: Preflight, openFirewall: Boolean, installer: Installer): MyServer? {
        val panel = s.panel?.takeIf { !it.kind.tunnelOnly } ?: return null
        if (!issueAndApplyCert(s.host, panel, pre, openFirewall, installer)) return null
        val upd = panel.copy(baseUrl = XuiPanel.toHttps(panel.baseUrl))
        installer.run(OtherPanels.stateSteps(upd))
        return s.copy(panel = upd)
    }

    @Deprecated("0.8.1-rc2: для всех панелей", ReplaceWith("enablePanelHttps(s, pre, openFirewall, installer)"))
    fun enableXuiHttps(s: MyServer, pre: Preflight, openFirewall: Boolean, installer: Installer): MyServer? =
        s.panel?.takeIf { it.kind == PanelKind.XUI3 }?.let { enablePanelHttps(s, pre, openFirewall, installer) }

    private fun issueAndApplyCert(host: String, panel: PanelInstance, pre: Preflight, openFirewall: Boolean, installer: Installer): Boolean {
        val url = java.net.URL(panel.baseUrl)
        val h = host.removePrefix("[").removeSuffix("]")
        if (!installer.run(XuiPanel.certIssueSteps(h, openFirewall, pre.firewall, OtherPanels.serviceOf(panel.kind)))) return false
        if (installer.run(XuiPanel.certApplySteps(panel.kind, url.port, url.path))) return true
        installer.run(listOf(certRevertStep(panel.kind)))
        return false
    }

    /** Вернуть панель на HTTP: пути к сертификату в её настройках стираются. */
    fun certRevertStep(kind: PanelKind): Step {
        val svc = OtherPanels.serviceOf(kind)
        return Step("cert-revert", "Вернуть панель на HTTP (с сертификатом она не запустилась)",
            OtherPanels.settingsCommand(kind, listOf("webCertFile", "webKeyFile", "subCertFile", "subKeyFile").associateWith { "" }) + "; systemctl restart $svc")
    }

    /**
     * Закрыть порт панели извне или открыть снова (0.8.1-rc2) — [OtherPanels.listenSteps]. Закрытую панель приложение открывает
     * через SSH-туннель, а API 3x-ui вызывает `curl`-ом на сервере.
     */
    fun setPanelLocal(s: MyServer, local: Boolean, pre: Preflight, installer: Installer): MyServer? {
        // PasarGuard и Remnawave в Hydra слушают только 127.0.0.1 — открыть извне нельзя
        val panel = s.panel?.takeIf { !it.kind.tunnelOnly } ?: return null
        if (!installer.run(OtherPanels.listenSteps(panel, local, pre.firewall))) return null
        val upd = panel.copy(local = local)
        installer.run(OtherPanels.stateSteps(upd))
        return s.copy(panel = upd)
    }

    /** После установки панель поднимается несколько секунд: ждём ответ (до ~60 с); неверный токен и ошибки панели — сразу. */
    private fun waitPanelReady(api: XuiApi, onLog: (String) -> Unit) {
        var last: XuiApiException? = null
        repeat(30) {
            try { api.settings(); return } catch (e: XuiApiException) {
                if (!e.message.orEmpty().startsWith("Панель не ответила")) throw e
                last = e
                if (it == 0) onLog("Жду, пока панель начнёт отвечать…")
                Thread.sleep(2000)
            }
        }
        throw last ?: XuiApiException("Панель не ответила")
    }

    /** Удаление панели с сервера (скрипт самой панели, без вопросов); порт панели закрывается, если его открывала Hydra. */
    fun uninstallXui(s: MyServer, installer: Installer): MyServer? {
        val port = runCatching { java.net.URL(s.panel?.baseUrl ?: "").port }.getOrDefault(-1)
        val ok = installer.run(listOfNotNull(
            ServerOps.backupPanelsStep(),
            Step("xui-remove", "Удалить 3x-ui с сервера", "printf 'y\\n' | /usr/bin/x-ui uninstall; test ! -e /usr/local/x-ui/x-ui && rm -f ${ServerOps.XUI_MARK}",
                failHint = "3x-ui не удалось удалить"),
            port.takeIf { it > 0 }?.let { Step("xui-fw-close", "Закрыть порт панели $it/tcp, если его открывала Hydra", ServerOps.closePortCommand("$it/tcp")) },
        ))
        return if (ok) s.copy(panel = null) else null
    }

    // ---------------------------------------------------------------------------------------------------- s-ui, x-ui (0.8.1)

    fun planPreviewPanel(kind: PanelKind, tag: String, openFirewall: Boolean, pre: Preflight, sslIp: Boolean = false, host: String = ""): List<Step> {
        val s = OtherPanels.spec(kind) ?: return emptyList()
        val h = host.removePrefix("[").removeSuffix("]")
        val cert = if (sslIp && ':' !in h && h.isNotBlank())
            runCatching { XuiPanel.certIssueSteps(h, openFirewall, pre.firewall, s.service) + XuiPanel.certApplySteps(kind) }.getOrDefault(emptyList()) else emptyList()
        return OtherPanels.installSteps(s, OtherPanels.Params(tag, "••••", "••••", 0, "••••"), openFirewall, pre.firewall) + cert +
            Step("panel-state", "Сохранить копию данных панели на сервере (только для root)")
    }

    /**
     * Установка s-ui / x-ui. [sslIp] (0.8.1-rc2) — сразу HTTPS сертификатом Let's Encrypt на IP или имя сервера; не вышло
     * (порт 80 закрыт снаружи) — панель остаётся на HTTP, сертификат можно получить позже кнопкой «Включить HTTPS».
     */
    fun deployPanel(srv: MyServer, kind: PanelKind, tag: String, openFirewall: Boolean, pre: Preflight, installer: Installer,
                    sslIp: Boolean = false, onLog: (String) -> Unit = {}): DeployResult {
        val s = OtherPanels.spec(kind) ?: return DeployResult.Failed("Неизвестная панель")
        val p = OtherPanels.Params(tag)
        installer.addSecret(p.password); installer.addSecret(p.username)
        if (!installer.run(OtherPanels.installSteps(s, p, openFirewall, pre.firewall))) return DeployResult.Failed("Установка остановлена")
        val host = if (srv.host.contains(':')) "[${srv.host}]" else srv.host
        var panel = PanelInstance(kind, "http://$host:${p.port}/${p.path}/", p.username, p.password, "", tag)
        // IPv6-адрес acme.sh standalone не проверит так же надёжно — сертификат только на IPv4 или имя
        if (sslIp && ':' !in srv.host) {
            if (issueAndApplyCert(srv.host, panel, pre, openFirewall, installer)) {
                panel = panel.copy(baseUrl = XuiPanel.toHttps(panel.baseUrl))
                onLog("Сертификат выдан — панель работает по HTTPS")
            } else onLog("Сертификат выдать не удалось (порт 80 закрыт снаружи?) — панель пока по HTTP, HTTPS можно включить позже")
        }
        installer.run(OtherPanels.stateSteps(panel))
        return DeployResult.Ok(srv.copy(panel = panel))
    }

    fun planPreviewPasarGuard(): List<Step> = PasarGuardPanel.installSteps("••••") + PasarGuardPanel.setupPreviewSteps()

    /**
     * Установка PasarGuard (0.8.2-a3): официальными скриптами — панель и нода в Docker; владелец (логин и пароль придумывает Hydra)
     * — по одноразовому ключу CLI; нода регистрируется в панели. Панель без сертификата слушает только 127.0.0.1 — в Hydra она
     * сразу «закрыта извне» (сайт — через туннель, API — `curl` на сервере).
     */
    fun deployPasarGuard(ssh: SshSession, srv: MyServer, installer: Installer, onLog: (String) -> Unit = {}): DeployResult {
        val nodeKey = java.util.UUID.randomUUID().toString()
        val user = "hy" + XuiPanel.random(6).lowercase(); val pass = PasarGuardPanel.newPassword()
        installer.addSecret(nodeKey); installer.addSecret(pass)
        if (!installer.run(PasarGuardPanel.installSteps(nodeKey))) return DeployResult.Failed("Установка остановлена")
        val api = PasarGuardApi(ssh, user, pass)
        try {
            val key = ssh.exec(PasarGuardPanel.tempKeyCommand()).out.trim()
            if (key.isBlank()) return DeployResult.Failed("PasarGuard не выдала ключ владельца")
            installer.addSecret(key)
            api.createOwner(key)
            api.login()
            onLog("Владелец панели создан")
            val ca = ssh.exec("cat ${PasarGuardPanel.NODE_CERT}").out.trim()
            api.addLocalNode(nodeKey, ca)
            onLog(if (api.waitNodeConnected()) "Нода подключена к панели" else "Нода пока не подключилась — проверьте её на сайте панели")
        } catch (e: XuiApiException) { return DeployResult.Failed("PasarGuard: ${e.message}") }
        val host = if (srv.host.contains(':')) "[${srv.host}]" else srv.host
        val panel = PanelInstance(PanelKind.PASARGUARD, "http://$host:${PasarGuardPanel.PORT}/", user, pass, "", api.version(), local = true)
        installer.run(OtherPanels.stateSteps(panel))
        return DeployResult.Ok(srv.copy(panel = panel))
    }

    fun planPreviewRemnawave(host: String): List<Step> = RemnawavePanel.installSteps(host) + RemnawavePanel.setupPreviewSteps()

    /**
     * Установка Remnawave (0.8.2-a3): панель в Docker (закреплённые образы) за Caddy на 127.0.0.1; владелец — регистрацией
     * (открыта, пока владельца нет; наружу панель не видна), нода — с ключом от панели, на адресе шлюза сети Docker.
     */
    fun deployRemnawave(ssh: SshSession, srv: MyServer, installer: Installer, onLog: (String) -> Unit = {}): DeployResult {
        val user = "hy" + XuiPanel.random(6).lowercase(); val pass = RemnawavePanel.newPassword()
        installer.addSecret(pass)
        if (!installer.run(RemnawavePanel.installSteps(srv.host))) return DeployResult.Failed("Установка остановлена")
        val api = RemnawaveApi(ssh, user, pass)
        try {
            api.register()
            onLog("Владелец панели создан")
            val key = api.secretKey()
            installer.addSecret(key)
            if (!installer.run(RemnawavePanel.nodeSteps(key))) return DeployResult.Failed("Нода Remnawave не запустилась")
            val gw = ssh.exec("docker network inspect remnawave-network -f '{{range .IPAM.Config}}{{.Gateway}} {{end}}'").out.trim().split(' ').firstOrNull { it.contains('.') } ?: "172.17.0.1"
            api.addLocalNode(gw)
            onLog(if (api.waitNodeConnected()) "Нода подключена к панели" else "Нода пока не подключилась — проверьте её на сайте панели")
        } catch (e: XuiApiException) { return DeployResult.Failed("Remnawave: ${e.message}") }
        val host = if (srv.host.contains(':')) "[${srv.host}]" else srv.host
        val panel = PanelInstance(PanelKind.REMNAWAVE, "http://$host:${RemnawavePanel.WEB_PORT}/", user, pass, "", RemnawavePanel.TAG, local = true)
        installer.run(OtherPanels.stateSteps(panel))
        return DeployResult.Ok(srv.copy(panel = panel))
    }

    /** Удалить панель любого вида с сервера; порт панели закрывается, если его открывала Hydra. */
    fun uninstallPanel(s: MyServer, installer: Installer): MyServer? {
        val panel = s.panel ?: return null
        if (panel.kind == PanelKind.XUI3) return uninstallXui(s, installer)?.also { installer.run(listOf(Step("panel-state-rm", "Удалить копию данных панели", "rm -f ${OtherPanels.PANEL_STATE}"))) }
        if (panel.kind == PanelKind.PASARGUARD) return if (installer.run(listOf(ServerOps.backupPanelsStep(),
                Step("panel-remove", "Удалить PasarGuard (панель и ноду) с сервера", PasarGuardPanel.uninstallCommand() + "; test ! -e ${PasarGuardPanel.DIR}", failHint = "Панель удалить не удалось"),
                Step("panel-fw-close", "Закрыть порты подключений PasarGuard, если их открывала Hydra", PasarGuardPanel.closePortsCommand()))))
            s.copy(panel = null) else null
        if (panel.kind == PanelKind.REMNAWAVE) return if (installer.run(listOf(
                Step("panel-backup", "Сохранить копию базы панели (пользователи, настройки) в ${ServerOps.BACKUP_DIR}", RemnawavePanel.backupCommand()),
                Step("panel-remove", "Удалить Remnawave (панель и ноду) с сервера", RemnawavePanel.uninstallCommand() + "; test ! -e ${RemnawavePanel.DIR}", failHint = "Панель удалить не удалось"),
                Step("panel-fw-close", "Закрыть порты подключений Remnawave, если их открывала Hydra", PasarGuardPanel.closePortsCommand(RemnawavePanel.PORTS)))))
            s.copy(panel = null) else null
        val spec = OtherPanels.spec(panel.kind) ?: return null
        val port = runCatching { java.net.URL(panel.baseUrl).port }.getOrDefault(-1)
        val ok = installer.run(listOfNotNull(
            ServerOps.backupPanelsStep(),
            Step("panel-remove", "Удалить ${panel.kind.title} с сервера", OtherPanels.uninstallCommand(spec) + "; test ! -e ${spec.home}", failHint = "Панель удалить не удалось"),
            port.takeIf { it > 0 }?.let { Step("panel-fw-close", "Закрыть порт панели $it/tcp, если его открывала Hydra", ServerOps.closePortCommand("$it/tcp")) },
        ))
        return if (ok) s.copy(panel = null) else null
    }

    fun restartPanel(kind: PanelKind, installer: Installer): Boolean {
        if (kind == PanelKind.PASARGUARD) return installer.run(listOf(Step("restart", "Перезапустить панель и ноду PasarGuard",
            "cd ${PasarGuardPanel.DIR} && docker compose -p pasarguard restart >/dev/null 2>&1 && cd ${PasarGuardPanel.NODE_DIR} && docker compose restart >/dev/null 2>&1 && sleep 5 && " +
                "curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:${PasarGuardPanel.PORT}/api/admin/token",
            accept = { it.out.trim().startsWith("4") || it.out.trim().startsWith("2") }, failHint = "Панель не запустилась (docker compose -p pasarguard logs)")))
        if (kind == PanelKind.REMNAWAVE) return installer.run(listOf(Step("restart", "Перезапустить панель и ноду Remnawave",
            "cd ${RemnawavePanel.DIR} && docker compose restart >/dev/null 2>&1 && cd ${RemnawavePanel.NODE_DIR} && docker compose restart >/dev/null 2>&1; " +
                "for i in \$(seq 1 30); do c=\$(curl -s -o /dev/null -w '%{http_code}' ${RemnawaveApi.HEADERS} http://127.0.0.1:${RemnawavePanel.API_PORT}/api/auth/status); [ \"\$c\" = 200 ] && break; sleep 3; done; echo \$c",
            accept = { it.out.trim().endsWith("200") }, failHint = "Панель не запустилась (cd ${RemnawavePanel.DIR} && docker compose logs)")))
        val svc = OtherPanels.spec(kind)?.service ?: "x-ui"
        return installer.run(listOf(Step("restart", "Перезапустить панель", "systemctl restart $svc && systemctl is-active $svc", failHint = "Панель не запустилась (journalctl -u $svc)")))
    }

    fun restartXui(installer: Installer): Boolean = installer.run(listOf(
        Step("restart", "Перезапустить панель 3x-ui", "systemctl restart x-ui && systemctl is-active x-ui", failHint = "Панель не запустилась (journalctl -u x-ui)"),
    ))

    // ---------------------------------------------------------------------------------------------------- Сервер целиком

    /** Полная очистка сервера от всего, что ставила Hydra; запись остаётся (с ней можно снова что-то поставить). */
    fun wipe(s: MyServer, installer: Installer): MyServer? =
        if (installer.run(ServerOps.wipeSteps())) s.copy(awg = null, ofx = null, panel = null, clients = emptyList()) else null

    /** Перезагрузка сервера: команда уходит в фон, сеанс закрывается сам. */
    fun reboot(ssh: SshSession) { ssh.exec("nohup sh -c 'sleep 2; systemctl reboot' >/dev/null 2>&1 &") }

    private fun dummyBundle(): Map<String, BundleFile> =
        listOf("amneziawg-go", "awg", "awg-quick").associateWith { BundleFile(it, ByteArray(0), "") }
}
