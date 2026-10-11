package ru.gidravpn.hydra.servers

import java.util.UUID

/** Итог операции над сервером. */
sealed interface DeployResult {
    data class Ok(val server: MyServer, val links: List<String> = emptyList()) : DeployResult
    data class Failed(val reason: String) : DeployResult
}

/**
 * Сценарии «Моих серверов» поверх SSH-сеанса: проверка, развёртывание AmneziaWG, клиенты, удаление.
 * Не знает ни об интерфейсе, ни о хранении — вызывающий передаёт сеанс и сохраняет результат.
 */
class MyServerService(private val bundleFor: (arch: String) -> AwgBundle) {

    fun preflight(ssh: SshSession, port: Int): Preflight = PreflightCheck.parse(ssh.exec(PreflightCheck.command(port)).out)

    /** Шаги, которые будут выполнены, — для показа до запуска. */
    fun planPreview(spec: AwgServerSpec, pre: Preflight, openFirewall: Boolean): List<Step> =
        AwgInstallPlan.steps(spec, emptyList(), AwgBundle(pre.arch, emptyMap<String, BundleFile>().withDummy()), openFirewall, pre.firewall)

    fun deployAwg(
        ssh: SshSession, target: SshTarget, fingerprint: String, name: String,
        generation: AwgGeneration, port: Int, openFirewall: Boolean, pre: Preflight,
        installer: (SshSession, Collection<String>) -> Installer,
    ): DeployResult {
        val spec = AwgServerSpec.create(target.host, port, generation)
        val first = AwgServerSpec.newClient("Hydra", spec.nextAddress(emptyList())!!)
        val bundle = try { bundleFor(pre.arch) } catch (e: Exception) { return DeployResult.Failed(e.message ?: "Не удалось получить серверный пакет") }
        val inst = installer(ssh, listOf(spec.privateKey, first.privateKey, first.presharedKey, target.password.orEmpty(), target.privateKey.orEmpty()))
        val ok = inst.run(AwgInstallPlan.steps(spec, listOf(first), bundle, openFirewall, pre.firewall))
        if (!ok) return DeployResult.Failed("Установка остановлена")
        return DeployResult.Ok(MyServer(UUID.randomUUID().toString(), name, target.host, target.port, target.user, fingerprint, awg = spec, clients = listOf(first)))
    }

    fun addClient(ssh: SshSession, s: MyServer, name: String, installer: Installer): Pair<MyServer, AwgClient>? {
        val spec = s.awg ?: return null
        val addr = spec.nextAddress(s.clients.map { it.address }) ?: return null
        val c = AwgServerSpec.newClient(name, addr)
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

    fun uninstall(s: MyServer, installer: Installer): Boolean = s.awg?.let { installer.run(AwgInstallPlan.uninstallSteps(it)) } ?: false


    /** Что будет выполнено при установке узла OpenFlux — для показа до запуска (порт выберет `plan` на сервере). */
    fun planPreviewOfx(spec: OfxNodeSpec): List<Step> = OpenFluxNode.fetchSteps() +
        Step("ofx-plan", "Спросить у скрипта план установки и порт (ничего не меняет)", "sh ${OpenFluxNode.SCRIPT} plan") +
        OpenFluxNode.applySteps(spec)

    fun deployOfx(
        ssh: SshSession, target: SshTarget, fingerprint: String, name: String, channel: String,
        vyandex: String, mailru: String, cupsonline: String, autoupdate: Boolean,
        installer: (SshSession, Collection<String>) -> Installer, onLog: (String) -> Unit,
    ): DeployResult {
        val key = OfxNodeSpec.newKey()
        var spec = OfxNodeSpec(target.host, channel, 0, key, vyandex, mailru, cupsonline, autoupdate)
        val inst = installer(ssh, listOf(key, target.password.orEmpty(), target.privateKey.orEmpty()))
        if (!inst.run(OpenFluxNode.fetchSteps())) return DeployResult.Failed("Установка остановлена")
        // plan: только чтение; порт выбирает скрипт (свободный и не занятый другими каналами)
        val cfg = "/tmp/.hydra-ofx-plan.cfg"
        ssh.upload(spec.config(false).toByteArray(), cfg, 384)
        val plan = ssh.exec(OpenFluxNode.planCommand(cfg))
        val port = OpenFluxNode.portOfPlan(plan.out)
            ?: return DeployResult.Failed(OpenFluxNode.errorOf(plan.out) ?: "Скрипт не предложил порт: ${plan.out.trim().takeLast(200)}")
        onLog("Порт узла: $port/tcp")
        spec = spec.copy(port = port)
        if (!inst.run(OpenFluxNode.applySteps(spec))) return DeployResult.Failed("Установка остановлена")
        return DeployResult.Ok(MyServer(UUID.randomUUID().toString(), name, target.host, target.port, target.user, fingerprint, ofx = spec))
    }

    fun uninstallOfx(s: MyServer, installer: Installer): Boolean =
        s.ofx?.let { installer.run(OpenFluxNode.removeSteps(it.channel)) } ?: false
    // ------------------------------------------------------------- Панель 3x-ui (0.8.1-a)

    /** Что будет выполнено при установке панели — для показа до запуска. */
    fun planPreviewXui(tag: String, openFirewall: Boolean, pre: Preflight): List<Step> =
        XuiPanel.installSteps(XuiPanel.InstallParams(tag, "••••", "••••", 0, "••••"), openFirewall, pre.firewall) + listOf(
            Step("xui-access", "Создать доступ: инбаунд VLESS + Reality и клиент (через API панели, по токену)", "curl … /panel/api/inbounds/add, /panel/api/clients/add"),
            Step("xui-import", "Импортировать ссылку клиента в «Серверы»"),
        )

    fun deployXui(
        ssh: SshSession, target: SshTarget, fingerprint: String, name: String, tag: String, accessPort: Int,
        openFirewall: Boolean, pre: Preflight, installer: (SshSession, Collection<String>) -> Installer, onLog: (String) -> Unit,
    ): DeployResult {
        val params = XuiPanel.InstallParams(tag)
        val inst = installer(ssh, listOf(params.password, params.username, target.password.orEmpty(), target.privateKey.orEmpty()))
        if (!inst.run(XuiPanel.installSteps(params, openFirewall, pre.firewall))) return DeployResult.Failed("Установка остановлена")
        val res = XuiPanel.parseResult(ssh.exec("cat ${XuiPanel.RESULT}").out)
        val token = res["XUI_API_TOKEN"].orEmpty()
        val port = res["XUI_PANEL_PORT"]?.toIntOrNull() ?: params.port
        val path = (res["XUI_WEB_BASE_PATH"] ?: params.webPath).trim('/')
        if (token.isBlank()) return DeployResult.Failed("Установщик не выдал API-токен (${XuiPanel.RESULT})")
        inst.addSecret(token)
        val local = "http://127.0.0.1:$port/$path"
        val api = XuiApi(SshXuiTransport(ssh, local, token, target.host))
        try {
            val version = res["XUI_VERSION"].orEmpty().ifBlank { tag }
            api.settings() // токен принят
            onLog("Токен принят, панель отвечает")
            val (priv, pub) = api.newRealityKeys()
            val inboundId = api.addInbound(XuiPanel.defaultInbound(accessPort, priv, pub, remark = "Hydra"))
            if (openFirewall && pre.firewall == "ufw") ssh.exec("ufw allow $accessPort/tcp")
            if (openFirewall && pre.firewall == "firewalld") ssh.exec("firewall-cmd --permanent --add-port=$accessPort/tcp && firewall-cmd --reload")
            val email = "hydra-1"
            api.addClient(email, XuiPanel.random(14), listOf(inboundId))
            val links = api.links(email)
            if (links.isEmpty()) return DeployResult.Failed("Панель не выдала ссылку клиента")
            onLog("Создан инбаунд VLESS+Reality на порту $accessPort и клиент $email")
            val panel = PanelInstance(PanelKind.XUI3, "http://${if (target.host.contains(':')) "[${target.host}]" else target.host}:$port/$path",
                params.username, params.password, token, version, inboundId, email)
            return DeployResult.Ok(MyServer(UUID.randomUUID().toString(), name, target.host, target.port, target.user, fingerprint, panel = panel), links)
        } catch (e: XuiApiException) {
            return DeployResult.Failed("Панель установлена, но доступ создать не удалось: ${e.message}. Адрес: http://${target.host}:$port/$path")
        }
    }

    /** Ещё один клиент панели: возвращает ссылки. */
    fun addPanelClient(ssh: SshSession, s: MyServer, email: String): List<String>? {
        val p = s.panel ?: return null
        val api = apiOver(ssh, s, p)
        val inbound = p.inboundId.takeIf { it > 0 } ?: return null
        api.addClient(email, XuiPanel.random(14), listOf(inbound))
        return api.links(email)
    }

    fun removePanelClient(ssh: SshSession, s: MyServer, email: String) {
        val p = s.panel ?: return
        apiOver(ssh, s, p).delClient(email)
    }

    fun panelLinks(ssh: SshSession, s: MyServer, email: String): List<String> = s.panel?.let { apiOver(ssh, s, it).links(email) }.orEmpty()

    fun panelClients(ssh: SshSession, s: MyServer): List<String> = s.panel?.let { apiOver(ssh, s, it).clientEmails() }.orEmpty()

    private fun apiOver(ssh: SshSession, s: MyServer, p: PanelInstance): XuiApi {
        val u = java.net.URI(p.baseUrl)
        return XuiApi(SshXuiTransport(ssh, "http://127.0.0.1:${u.port}${u.path}", p.token, s.host))
    }

    /** Удаление панели с сервера (скрипт самой панели, без вопросов). */
    fun uninstallXui(installer: Installer): Boolean = installer.run(listOf(
        Step("xui-remove", "Удалить 3x-ui с сервера", "printf 'y\\n' | /usr/local/x-ui/x-ui uninstall; test ! -e /usr/local/x-ui/x-ui",
            failHint = "3x-ui не удалось удалить"),
    ))

    private fun Map<String, BundleFile>.withDummy(): Map<String, BundleFile> =
        listOf("amneziawg-go", "awg", "awg-quick").associateWith { BundleFile(it, ByteArray(0), "") }
}
