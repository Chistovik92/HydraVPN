package ru.gidravpn.hydra.desktop

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import ru.gidravpn.hydra.servers.*
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** 0.8.1-a: панель 3x-ui — клиент API по Bearer-токену и мастер установки (на поддельных HTTP и SSH). */
class XuiPanelTest {
    /** Поддельная панель: проверяет заголовок, отдаёт `{success,msg,obj}` и запоминает вызовы. */
    private class FakePanel(val token: String) {
        val calls = mutableListOf<String>()
        val bodies = mutableMapOf<String, String>()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/hp") { ex ->
                val path = ex.requestURI.path.removePrefix("/hp")
                calls += "${ex.requestMethod} $path"
                ex.requestBody.readBytes().toString(Charsets.UTF_8).takeIf { it.isNotEmpty() }?.let { bodies[path] = it }
                val ok = ex.requestHeaders.getFirst("Authorization") == "Bearer $token"
                val out = if (!ok) """{"success":false,"msg":"unauthorized"}""" else when (path) {
                    "/panel/api/setting/all" -> """{"success":true,"obj":{"webBasePath":"/hp/","subEnable":true}}"""
                    "/panel/api/server/getNewX25519Cert" -> """{"success":true,"obj":{"privateKey":"PRIV","publicKey":"PUB"}}"""
                    "/panel/api/inbounds/add" -> """{"success":true,"msg":"created","obj":{"id":7}}"""
                    "/panel/api/inbounds/list/slim" -> """{"success":true,"obj":[{"id":7,"remark":"Hydra"}]}"""
                    "/panel/api/clients/add" -> """{"success":true,"obj":null}"""
                    "/panel/api/clients/links/hydra-1" -> """{"success":true,"obj":["vless://u@h:443?security=reality#Hydra-hydra-1"]}"""
                    "/panel/api/clients/list" -> """{"success":true,"obj":[{"email":"hydra-1"},{"email":"b"}]}"""
                    else -> """{"success":false,"msg":"нет такого метода"}"""
                }
                val b = out.toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            start()
        }
        val base get() = "http://127.0.0.1:${server.address.port}/hp"
        fun stop() = server.stop(0)
    }

    @Test fun apiUsesBearerTokenAndParsesAnswers() {
        val p = FakePanel("tok123")
        try {
            val api = XuiApi(DirectXuiTransport(p.base, "tok123"))
            assertTrue(api.settings().optBoolean("subEnable"))
            assertEquals("PRIV" to "PUB", api.newRealityKeys())
            val id = api.addInbound(XuiPanel.defaultInbound(24443, "PRIV", "PUB"))
            assertEquals(7, id)
            api.addClient("hydra-1", "sub123", listOf(id))
            assertEquals(listOf("vless://u@h:443?security=reality#Hydra-hydra-1"), api.links("hydra-1"))
            assertEquals(listOf("hydra-1", "b"), api.clientEmails())
            assertEquals(1, api.inbounds().length())
            // тело создания инбаунда — полный JSON с Reality и портом
            val inb = JSONObject(p.bodies["/panel/api/inbounds/add"]!!)
            assertEquals(24443, inb.getInt("port")); assertEquals("vless", inb.getString("protocol"))
            assertEquals("reality", inb.getJSONObject("streamSettings").getString("security"))
            assertEquals("PUB", inb.getJSONObject("streamSettings").getJSONObject("realitySettings").getJSONObject("settings").getString("publicKey"))
            val cl = JSONObject(p.bodies["/panel/api/clients/add"]!!)
            assertEquals(7, cl.getJSONArray("inboundIds").getInt(0)); assertEquals("hydra-1", cl.getJSONObject("client").getString("email"))
        } finally { p.stop() }
    }

    @Test fun wrongTokenAndPanelErrorsAreReadable() {
        val p = FakePanel("good")
        try {
            val bad = XuiApi(DirectXuiTransport(p.base, "bad"))
            val e = runCatching { bad.settings() }.exceptionOrNull()
            assertTrue(e is XuiApiException && "unauthorized" in e.message.orEmpty(), e.toString())
            val api = XuiApi(DirectXuiTransport(p.base, "good"))
            val e2 = runCatching { api.delInbound(1) }.exceptionOrNull()
            assertTrue(e2 is XuiApiException && "нет такого метода" in e2.message.orEmpty(), e2.toString())
        } finally { p.stop() }
    }

    @Test fun installPlanPinsTagKeepsSecretsOutOfCommands() {
        val params = XuiPanel.InstallParams("v3.9.0", "hyuser", "S3cretPassw0rd", 24680, "pathabc")
        val steps = XuiPanel.installSteps(params, openFirewall = false, firewall = "ufw")
        val cmds = steps.mapNotNull { it.command }.joinToString("\n")
        assertTrue("raw.githubusercontent.com/MHSanaei/3x-ui/v3.9.0/install.sh" in cmds && "bash ${XuiPanel.SCRIPT} v3.9.0" in cmds)
        // пароль и логин — только в файле параметров (0600), не в командах
        assertFalse("S3cretPassw0rd" in cmds || "hyuser" in cmds)
        val env = steps.first { it.id == "xui-env" }.upload!!
        assertEquals(384, env.mode)
        val txt = String(env.data)
        assertTrue("XUI_NONINTERACTIVE=1" in txt && "XUI_PASSWORD=S3cretPassw0rd" in txt && "XUI_PANEL_PORT=24680" in txt && "XUI_SSL_MODE=none" in txt)
        // журнал установщика не читается при успехе и удаляется всегда
        val install = steps.first { it.id == "xui-install" }.command!!
        assertTrue("rm -f ${XuiPanel.LOG}" in install && "if [ \$rc -ne 0 ]" in install)
        assertFalse(steps.any { it.id == "xui-fw" })
        assertTrue(XuiPanel.installSteps(params, true, "ufw").any { it.id == "xui-fw" })
        assertTrue(runCatching { XuiPanel.installSteps(XuiPanel.InstallParams("latest; rm -rf /"), false, "none") }.isFailure)
    }

    @Test fun resultFileParses() {
        val m = XuiPanel.parseResult("XUI_USERNAME=hyadmin\nXUI_PASSWORD=Hy-Test-91kQ\nXUI_PANEL_PORT=24680\nXUI_WEB_BASE_PATH=hpanel\nXUI_API_TOKEN=abcDEF123\nXUI_DB_TYPE=sqlite\n")
        assertEquals("abcDEF123", m["XUI_API_TOKEN"]); assertEquals("24680", m["XUI_PANEL_PORT"]); assertEquals("hpanel", m["XUI_WEB_BASE_PATH"])
    }

    @Test fun redactorHidesTokens() {
        val r = Redactor(listOf("abcDEF123456"))
        assertEquals("API Token: •••", r.clean("API Token: Zz9xxxxxxxxxxxxxxxxxxxx"))
        assertEquals("tok •••", r.clean("tok abcDEF123456"))
    }

    /** SSH-заглушка: разбирает `curl` панели и возвращает ответы по пути, остальное — успех. */
    private class FakeSsh : SshSession {
        val cmds = mutableListOf<String>()
        override fun exec(command: String, onLine: (String) -> Unit): ExecResult {
            cmds += command
            if (command == "systemctl is-active x-ui") return ExecResult(0, "active")
            if (command.startsWith("cat ${XuiPanel.RESULT}")) return ExecResult(0,
                "XUI_USERNAME=u\nXUI_PASSWORD=p\nXUI_PANEL_PORT=24680\nXUI_WEB_BASE_PATH=hp\nXUI_API_TOKEN=TOKEN-from-installer-123\n")
            if (command.startsWith("curl -ksS")) {
                val path = Regex("'http://127.0.0.1:24680/hp([^']*)'").find(command)?.groupValues?.get(1).orEmpty()
                if ("Bearer TOKEN-from-installer-123" !in command) return ExecResult(0, """{"success":false,"msg":"unauthorized"}""")
                return ExecResult(0, when (path) {
                    "/panel/api/setting/all" -> """{"success":true,"obj":{}}"""
                    "/panel/api/server/getNewX25519Cert" -> """{"success":true,"obj":{"privateKey":"PRIV","publicKey":"PUB"}}"""
                    "/panel/api/inbounds/add" -> """{"success":true,"obj":{"id":3}}"""
                    "/panel/api/clients/add" -> """{"success":true,"obj":null}"""
                    "/panel/api/clients/list" -> """{"success":true,"obj":[{"email":"hydra-1","subId":"abcsub"}]}"""
                    "/panel/api/clients/links/hydra-1" -> """{"success":true,"obj":["vless://uuid@203.0.113.5:443?security=reality#Hydra-hydra-1"]}"""
                    else -> """{"success":false,"msg":"?? $path"}"""
                })
            }
            return ExecResult(0, "")
        }
        val bodies = mutableListOf<String>()
        override fun upload(data: ByteArray, remotePath: String, mode: Int) { bodies += String(data) }
        override fun close() {}
    }

    @Test fun subscriptionUrlFollowsPanelSettings() {
        val o = { s: String -> org.json.JSONObject(s) }
        assertEquals("http://1.2.3.4:2096/sub/ID", XuiPanel.subscriptionUrl(o("{}"), "1.2.3.4", "ID"))
        assertEquals("https://1.2.3.4:2096/abc/ID", XuiPanel.subscriptionUrl(o("""{"subPath":"/abc/","subCertFile":"/c","subKeyFile":"/k"}"""), "1.2.3.4", "ID"))
        assertEquals("https://sub.example.com/x/ID", XuiPanel.subscriptionUrl(o("""{"subURI":"https://sub.example.com/x/"}"""), "1.2.3.4", "ID"))
        assertEquals("http://[2001:db8::1]:2096/sub/ID", XuiPanel.subscriptionUrl(o("{}"), "2001:db8::1", "ID"))
        assertEquals(null, XuiPanel.subscriptionUrl(o("""{"subEnable":false}"""), "1.2.3.4", "ID"))
    }

    @Test fun installEnvAsksForIpCertificateAndOpensPort80() {
        val p = XuiPanel.InstallParams("v3.10.2", "u", "p", 20000, "w", sslIp = true)
        val steps = XuiPanel.installSteps(p, true, "ufw")
        val env = String(steps.first { it.id == "xui-env" }.upload!!.data)
        assertTrue("XUI_SSL_MODE=ip\n" in env, env)
        assertTrue(steps.any { it.id == "xui-fw80" } && steps.any { it.id == "xui-fw-sub" })
        val dom = String(XuiPanel.installSteps(XuiPanel.InstallParams("v3.10.2", "u", "p", 1, "w", sslIp = true, domain = "vpn.example.com"), false, "none")
            .first { it.id == "xui-env" }.upload!!.data)
        assertTrue("XUI_SSL_MODE=domain\nXUI_DOMAIN=vpn.example.com\n" in dom, dom)
        val off = String(XuiPanel.installSteps(XuiPanel.InstallParams("v3.10.2", "u", "p", 1, "w"), false, "none").first { it.id == "xui-env" }.upload!!.data)
        assertTrue("XUI_SSL_MODE=none\n" in off, off)
    }

    @Test fun httpsStepsForExistingPanel() {
        val ip = XuiPanel.httpsSteps("203.0.113.5", true, "ufw")
        assertTrue(ip.first().command!!.startsWith("ufw allow 80/tcp"))
        assertTrue(ip.single { it.id == "cert-issue" }.command!!.contains("--certificate-profile shortlived --days 6"))
        assertTrue(ip.single { it.id == "cert-panel" }.command!!.contains("x-ui cert -webCert ${XuiPanel.CERT_DIR}/fullchain.pem"))
        assertFalse(XuiPanel.httpsSteps("vpn.example.com", false, "none").single { it.id == "cert-issue" }.command!!.contains("shortlived"))
        assertEquals("https://h:1/p", XuiPanel.toHttps("http://h:1/p"))
    }

    @Test fun deployFlowCreatesPanelInboundClientAndLink() {
        val ssh = FakeSsh()
        val log = mutableListOf<String>()
        val pre = Preflight("amd64", "Ubuntu", true, true, true, true, "none", emptyList())
        val svc = MyServerService { error("пакет AmneziaWG для панели не нужен") }
        val res = svc.deployXui(ssh, MyServer("i", "Панель", "203.0.113.5", fingerprint = "SHA256:fp"), "v3.9.0", 443, false, pre,
            Installer(ssh, listOf("pw")) { e -> if (e is InstallEvent.Output) log += e.line }) { log += it }
        val ok = res as? DeployResult.Ok ?: fail("не удалось: $res")
        val panel = ok.server.panel!!
        assertEquals("TOKEN-from-installer-123", panel.token)
        assertEquals("http://203.0.113.5:24680/hp", panel.baseUrl)
        assertEquals(3, panel.inboundId); assertEquals("hydra-1", panel.clientEmail)
        // 0.8.1-rc1: в «Серверы» уходит подписка клиента (обновляется сама), а не голая ссылка
        assertTrue(Regex("""^http://203\.0\.113\.5:2096/sub/[a-z0-9]{16}$""").matches(ok.links.single()), ok.links.toString())
        // инбаунд — VLESS + gRPC + Reality под vk.com, клиент без flow (у XHTTP его нет)
        val inbound = ssh.bodies.first { "\"protocol\":\"vless\"" in it }
        assertTrue("\"network\":\"grpc\"" in inbound && "\"target\":\"vk.com:443\"" in inbound, inbound)
        assertTrue(ssh.bodies.any { "\"flow\":\"\"" in it && "hydra-1" in it })
        // токен не попал в журнал, пароль тоже
        assertFalse(log.any { "TOKEN-from-installer-123" in it })
        // сохранение/чтение записи панели
        val back = MyServer.listFromJson(MyServer.listToJson(listOf(ok.server))).single()
        assertEquals(panel, back.panel)
    }

    @Test fun deployFailsReadablyWhenInstallerGivesNoToken() {
        val ssh = object : SshSession by FakeSsh() {
            override fun exec(command: String, onLine: (String) -> Unit) =
                if (command.startsWith("cat ${XuiPanel.RESULT}")) ExecResult(0, "XUI_USERNAME=u\n") else FakeSsh().exec(command, onLine)
        }
        val pre = Preflight("amd64", "Ubuntu", true, true, true, true, "none", emptyList())
        val res = MyServerService { error("не нужен") }.deployXui(ssh, MyServer("i", "n", "h", fingerprint = "fp"), "v3.9.0", 443, false, pre,
            Installer(ssh, listOf("pw")) { }) { }
        assertTrue(res is DeployResult.Failed && "токен" in res.reason, res.toString())
    }
}

/** Живой прогон на настоящем Linux (WSL): HYDRA_SSH_TEST=host:port:root:пароль и HYDRA_XUI_LIVE=1. Ставит 3x-ui из GitHub и удаляет. */
class XuiLiveTest {
    @Test fun wizardInstallsPanelCreatesAccessAndRemovesIt() {
        val v = System.getenv("HYDRA_SSH_TEST")
        org.junit.Assume.assumeTrue(v != null && System.getenv("HYDRA_XUI_LIVE") != null)
        val p = v!!.split(":", limit = 4)
        val login = SshTarget(p[0], p[1].toInt(), p[2], password = p[3])
        var saved = emptyList<MyServer>()
        val imported = mutableListOf<String>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val m = MyServersManager(scope, { saved }, { saved = it }, { error("не нужен") }, MemoryVault()) { _, conf -> imported += conf }
        fun waitFor(what: String, mgr: MyServersManager = m, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + 420_000
            Thread.sleep(150)
            while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(300) }
            fail("таймаут: $what; ${mgr.ui.value.error}; ${mgr.ui.value.log.takeLast(12)}")
        }
        m.begin(ServerForm("panel", login))
        waitFor("отпечаток") { m.ui.value.stage == Stage.CONFIRM_FINGERPRINT }
        m.confirmFingerprint()
        waitFor("добавление") { m.ui.value.stage == Stage.DONE || m.ui.value.stage == Stage.FAILED }
        val id = saved.single().id
        // сертификат Let's Encrypt на стенде не выдать (нет внешнего IP) — HTTPS проверяется только на VPS
        m.prepareInstall(id, ServiceForm(ServerKind.PANEL_3XUI, accessPort = 24443, sslIp = false))
        waitFor("план") { !m.ui.value.busy && (m.ui.value.install?.stage == InstallStage.READY || m.ui.value.error != null) }
        assertEquals(InstallStage.READY, m.ui.value.install?.stage, "${m.ui.value.error}")
        assertTrue(m.ui.value.install!!.plan.any { it.id == "xui-install" && "v" in it.title })
        m.runInstall()
        waitFor("установка") { !m.ui.value.busy && m.ui.value.install?.stage in setOf(InstallStage.DONE, InstallStage.FAILED) }
        assertEquals(InstallStage.DONE, m.ui.value.install?.stage, "${m.ui.value.error} ${m.ui.value.log.takeLast(14)}")
        m.closeInstall()
        val srv = saved.single(); val panel = srv.panel!!
        println("XUI_PANEL=${panel.baseUrl} version=${panel.version}")
        // 0.8.1-rc1: в «Серверы» уходит подписка; сама подписка отдаёт ссылку VLESS + gRPC + Reality
        val sub = imported.single()
        assertTrue(sub.startsWith("http://${login.host}:2096/"), imported.toString())
        // как клиент Hydra: Accept */* (с text/html 3x-ui отдаёт страницу для браузера)
        val body = (java.net.URL(sub).openConnection().apply { setRequestProperty("Accept", "*/*") }).getInputStream().use { it.readBytes().toString(Charsets.UTF_8) }.trim()
        val links = runCatching { String(java.util.Base64.getDecoder().decode(body)) }.getOrDefault(body)
        println("SUB_LINKS=" + links.replace(Regex("pbk=[^&]+"), "pbk=…"))
        assertTrue(links.lines().any { it.startsWith("vless://") && "type=grpc" in it && "security=reality" in it && "sni=vk.com" in it }, links)
        // клиент Hydra разбирает ссылку из подписки
        val prof = ru.gidravpn.hydra.data.subscription.LinkParser.parseSubscription(body)
        assertTrue(prof.any { it.port == 24443 && it.address.isNotBlank() }, "ссылка подписки не разобралась: $prof")
        // ядро Xray панели действительно слушает порт инбаунда
        SshConnector.connect(login, saved.single().fingerprint).use { ssh -> assertTrue(ssh.exec("ss -ltn | grep -q ':24443 '").exit == 0, "Xray не слушает 24443") }
        // токен реально работает снаружи, по сети
        val api = XuiApi(DirectXuiTransport(panel.baseUrl, panel.token))
        assertTrue(api.clientEmails().contains("hydra-1"))
        assertEquals(1, api.inbounds().length())
        // ещё один клиент и его ссылки — через мастер
        m.addPanelClient(srv.id, "second")
        waitFor("клиент") { !m.ui.value.busy && m.ui.value.shown != null }
        assertTrue(m.ui.value.shown!!.second.startsWith("http://"), m.ui.value.shown.toString())
        assertTrue(api.clientEmails().contains("second"))
        // токен и пароль не в журнале
        assertFalse(m.ui.value.log.any { panel.token in it || panel.password in it }, "секрет в журнале")
        // второе устройство подхватывает панель, поставленную Hydra
        var saved2 = emptyList<MyServer>()
        val m2 = MyServersManager(scope, { saved2 }, { saved2 = it }, { error("не нужен") }, MemoryVault()) { _, _ -> }
        m2.begin(ServerForm("second device", login, remember = false))
        waitFor("отпечаток 2", m2) { m2.ui.value.stage == Stage.CONFIRM_FINGERPRINT }
        m2.confirmFingerprint()
        waitFor("добавление 2", m2) { m2.ui.value.stage == Stage.DONE || m2.ui.value.stage == Stage.FAILED }
        assertEquals(panel.token, saved2.single().panel?.token)
        // 0.8.1-rc2: настоящий трафик через инбаунд (в rc1 проверялось только, что панель его приняла) — HYDRA_SINGBOX=путь к sing-box
        System.getenv("HYDRA_SINGBOX")?.let { sb ->
            val cases = mutableListOf("подписка (gRPC, vk.com)" to prof.first { it.port == 24443 })
            SshConnector.connect(login, srv.fingerprint).use { ssh ->
                val u = java.net.URL(panel.baseUrl)
                val sapi = XuiApi(SshXuiTransport(ssh, "http://127.0.0.1:${u.port}${u.path}", panel.token, login.host))
                var port = 24444
                for ((tr, mask) in listOf(XuiPanel.Transport.GRPC to XuiPanel.RealityMask.GITHUB, XuiPanel.Transport.XHTTP to XuiPanel.RealityMask.VK,
                        XuiPanel.Transport.GRPC to XuiPanel.RealityMask.YANDEX)) {
                    val (priv, pub) = sapi.newRealityKeys()
                    val inb = sapi.addInbound(XuiPanel.defaultInbound(port, priv, pub, mask, remark = "t$port", transport = tr))
                    val email = "t$port"; sapi.addClient(email, XuiPanel.random(16).lowercase(), listOf(inb), flow = "")
                    val link = sapi.links(email).single()
                    println("LINK $tr ${mask.sni}: " + link.replace(Regex("pbk=[^&]+"), "pbk=…").replace(Regex("vless://[^@]+@"), "vless://…@"))
                    cases += "${tr.title}, ${mask.sni}" to ru.gidravpn.hydra.data.subscription.LinkParser.parseLine(link)!!
                    port++
                }
            }
            // Xray-core ≥ 26.9.8 (в 3x-ui v3.10) не пускает sing-box в REALITY — поэтому Hydra отдаёт REALITY ядру Xray
            // (EngineToggles.engineFor(profile)); трафик обязан идти через Xray, sing-box — только для сведения
            System.getenv("HYDRA_XRAY")?.let { xr ->
                val results = cases.map { (name, pr) -> name to XuiTraffic.throughXray(xr, pr) }
                results.forEach { (n, r) -> println("TRAFFIC xray $n: $r") }
                assertTrue(results.all { it.second.startsWith("ok") }, results.toString())
                assertTrue(cases.all { ru.gidravpn.hydra.data.model.EngineToggles().engineFor(it.second, true) == ru.gidravpn.hydra.data.model.EngineToggles.Kind.XRAY })
            }
            cases.forEach { (n, pr) -> println("TRAFFIC sing-box $n: ${XuiTraffic.through(sb, pr).take(160)}") }
        }
        // HYDRA_XUI_KEEP — оставить панель на стенде (проверка на эмуляторе): ссылка клиента hydra-1 в журнал теста
        if (System.getenv("HYDRA_XUI_KEEP") != null) { println("KEEP_LINK=" + api.links("hydra-1").first()); println("KEEP_SUB=$sub"); return }
        // полная очистка снимает и панель
        m.wipe(id)
        waitFor("очистка") { !m.ui.value.busy && saved.single().panel == null }
        assertEquals(null, m.ui.value.error, m.ui.value.log.takeLast(10).toString())
        SshConnector.connect(login, srv.fingerprint).use { ssh -> assertTrue(ssh.exec("test -e /usr/local/x-ui/x-ui").exit != 0) }
    }
}
