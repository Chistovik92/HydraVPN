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
        if (s.panel != null && !s.panelOnly && !st.xuiInstalled) r = r.copy(panel = null)
        // панель поставлена Hydra с другого устройства — берём под управление по её install-result.env
        if (s.panel == null && st.xuiInstalled && st.xuiByHydra && st.xuiResult.isNotBlank()) {
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
    fun planPreviewXui(tag: String, openFirewall: Boolean, pre: Preflight): List<Step> =
        XuiPanel.installSteps(XuiPanel.InstallParams(tag, "••••", "••••", 0, "••••"), openFirewall, pre.firewall) + listOf(
            Step("xui-access", "Создать доступ: инбаунд VLESS + Reality и клиент (через API панели, по токену)", "curl … /panel/api/inbounds/add, /panel/api/clients/add"),
            Step("xui-import", "Импортировать ссылку клиента в «Серверы»"),
        )

    fun deployXui(
        ssh: SshSession, srv: MyServer, tag: String, accessPort: Int, openFirewall: Boolean, pre: Preflight,
        installer: Installer, onLog: (String) -> Unit,
    ): DeployResult {
        val params = XuiPanel.InstallParams(tag)
        installer.addSecret(params.password); installer.addSecret(params.username)
        if (!installer.run(XuiPanel.installSteps(params, openFirewall, pre.firewall))) return DeployResult.Failed("Установка остановлена")
        val res = XuiPanel.parseResult(ssh.exec("cat ${XuiPanel.RESULT}").out)
        val token = res["XUI_API_TOKEN"].orEmpty()
        val port = res["XUI_PANEL_PORT"]?.toIntOrNull() ?: params.port
        val path = (res["XUI_WEB_BASE_PATH"] ?: params.webPath).trim('/')
        if (token.isBlank()) return DeployResult.Failed("Установщик не выдал API-токен (${XuiPanel.RESULT})")
        installer.addSecret(token)
        val api = XuiApi(SshXuiTransport(ssh, "http://127.0.0.1:$port/$path", token, srv.host))
        try {
            val version = res["XUI_VERSION"].orEmpty().ifBlank { tag }
            waitPanelReady(api, onLog) // служба запущена, но порт панель открывает не сразу; заодно проверяется токен
            onLog("Токен принят, панель отвечает")
            val (priv, pub) = api.newRealityKeys()
            val inboundId = api.addInbound(XuiPanel.defaultInbound(accessPort, priv, pub, remark = "Hydra"))
            if (openFirewall) ServerOps.openPortStep(pre.firewall, "$accessPort/tcp")?.let { installer.run(listOf(it)) }
            val email = "hydra-1"
            api.addClient(email, XuiPanel.random(14), listOf(inboundId))
            val links = api.links(email)
            if (links.isEmpty()) return DeployResult.Failed("Панель не выдала ссылку клиента")
            onLog("Создан инбаунд VLESS+Reality на порту $accessPort и клиент $email")
            val panel = PanelInstance(PanelKind.XUI3, "http://${if (srv.host.contains(':')) "[${srv.host}]" else srv.host}:$port/$path",
                params.username, params.password, token, version, inboundId, email)
            return DeployResult.Ok(srv.copy(panel = panel), links)
        } catch (e: XuiApiException) {
            return DeployResult.Failed("Панель установлена, но доступ создать не удалось: ${e.message}. Адрес: http://${srv.host}:$port/$path")
        }
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
            Step("xui-remove", "Удалить 3x-ui с сервера", "printf 'y\\n' | /usr/bin/x-ui uninstall; test ! -e /usr/local/x-ui/x-ui && rm -f ${ServerOps.XUI_MARK}",
                failHint = "3x-ui не удалось удалить"),
            port.takeIf { it > 0 }?.let { Step("xui-fw-close", "Закрыть порт панели $it/tcp, если его открывала Hydra", ServerOps.closePortCommand("$it/tcp")) },
        ))
        return if (ok) s.copy(panel = null) else null
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
