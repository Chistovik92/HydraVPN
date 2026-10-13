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
            if (command.startsWith("curl -sS")) {
                val path = Regex("'http://127.0.0.1:24680/hp([^']*)'").find(command)?.groupValues?.get(1).orEmpty()
                if ("Bearer TOKEN-from-installer-123" !in command) return ExecResult(0, """{"success":false,"msg":"unauthorized"}""")
                return ExecResult(0, when (path) {
                    "/panel/api/setting/all" -> """{"success":true,"obj":{}}"""
                    "/panel/api/server/getNewX25519Cert" -> """{"success":true,"obj":{"privateKey":"PRIV","publicKey":"PUB"}}"""
                    "/panel/api/inbounds/add" -> """{"success":true,"obj":{"id":3}}"""
                    "/panel/api/clients/add" -> """{"success":true,"obj":null}"""
                    "/panel/api/clients/links/hydra-1" -> """{"success":true,"obj":["vless://uuid@203.0.113.5:443?security=reality#Hydra-hydra-1"]}"""
                    else -> """{"success":false,"msg":"?? $path"}"""
                })
            }
            return ExecResult(0, "")
        }
        override fun upload(data: ByteArray, remotePath: String, mode: Int) {}
        override fun close() {}
    }

    @Test fun deployFlowCreatesPanelInboundClientAndLink() {
        val ssh = FakeSsh()
        val log = mutableListOf<String>()
        val pre = Preflight("amd64", "Ubuntu", true, true, true, true, "none", emptyList())
        val svc = MyServerService { error("пакет AmneziaWG для панели не нужен") }
        val res = svc.deployXui(ssh, SshTarget("203.0.113.5", 22, "root", password = "pw"), "SHA256:fp", "Панель", "v3.9.0", 443, false, pre,
            { s, secrets -> Installer(s, secrets) { e -> if (e is InstallEvent.Output) log += e.line } }) { log += it }
        val ok = res as? DeployResult.Ok ?: fail("не удалось: $res")
        val panel = ok.server.panel!!
        assertEquals("TOKEN-from-installer-123", panel.token)
        assertEquals("http://203.0.113.5:24680/hp", panel.baseUrl)
        assertEquals(3, panel.inboundId); assertEquals("hydra-1", panel.clientEmail)
        assertEquals(listOf("vless://uuid@203.0.113.5:443?security=reality#Hydra-hydra-1"), ok.links)
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
        val res = MyServerService { error("не нужен") }.deployXui(ssh, SshTarget("h", 22, "root", password = "pw"), "fp", "n", "v3.9.0", 443, false, pre,
            { s, secrets -> Installer(s, secrets) { } }) { }
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
        val m = MyServersManager(scope, { saved }, { saved = it }, { error("не нужен") }) { _, conf -> imported += conf }
        fun waitFor(what: String, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + 420_000
            while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(300) }
            fail("таймаут: $what; ${m.ui.value.error}; ${m.ui.value.log.takeLast(12)}")
        }
        m.begin(ServerForm("panel", login, kind = ServerKind.PANEL_3XUI, accessPort = 24443))
        waitFor("отпечаток") { m.ui.value.stage == Stage.CONFIRM_FINGERPRINT }
        m.confirmFingerprint()
        waitFor("план") { m.ui.value.stage == Stage.READY || m.ui.value.stage == Stage.FAILED }
        assertEquals(Stage.READY, m.ui.value.stage, "${m.ui.value.error}")
        assertTrue(m.ui.value.plan.any { it.id == "xui-install" && "v" in it.title })
        m.install()
        waitFor("установка") { m.ui.value.stage == Stage.DONE || m.ui.value.stage == Stage.FAILED }
        assertEquals(Stage.DONE, m.ui.value.stage, "${m.ui.value.error} ${m.ui.value.log.takeLast(14)}")
        val srv = saved.single(); val panel = srv.panel!!
        println("XUI_PANEL=${panel.baseUrl} version=${panel.version}")
        assertTrue(imported.single().startsWith("vless://") && "security=reality" in imported.single(), imported.toString())
        // токен реально работает снаружи, по сети
        val api = XuiApi(DirectXuiTransport(panel.baseUrl, panel.token))
        assertTrue(api.clientEmails().contains("hydra-1"))
        assertEquals(1, api.inbounds().length())
        // ещё один клиент и его ссылки — через мастер
        m.addPanelClient(srv.id, login, "second")
        waitFor("клиент") { !m.ui.value.busy && m.ui.value.shown != null }
        assertTrue(m.ui.value.shown!!.second.startsWith("vless://"), m.ui.value.shown.toString())
        assertTrue(api.clientEmails().contains("second"))
        // токен и пароль не в журнале
        assertFalse(m.ui.value.log.any { panel.token in it || panel.password in it }, "секрет в журнале")
        // удаление
        m.uninstall(srv.id, login)
        waitFor("удаление") { !m.ui.value.busy && saved.isEmpty() }
        SshConnector.connect(login, srv.fingerprint).use { ssh -> assertTrue(ssh.exec("test -e /usr/local/x-ui/x-ui").exit != 0) }
    }
}
