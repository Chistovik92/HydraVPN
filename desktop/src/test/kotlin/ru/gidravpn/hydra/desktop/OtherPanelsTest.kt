package ru.gidravpn.hydra.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assume.assumeTrue
import ru.gidravpn.hydra.servers.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Панели s-ui и x-ui (alireza0), 0.8.1. */
class OtherPanelsTest {

    @Test fun plansAreSafe() {
        for (spec in listOf(OtherPanels.SUI, OtherPanels.XUI)) {
            val p = OtherPanels.Params("v1.2.3", "user1", "S3cretPassw0rd", 31000, "abcpath")
            val steps = OtherPanels.installSteps(spec, p, openFirewall = true, firewall = "ufw")
            // пароль не в командах (только в загружаемом файле 0600), установщик — закреплённый тег
            assertTrue(steps.none { it.command.orEmpty().contains(p.password) }, spec.kind.title)
            val cfg = steps.single { it.id == "panel-config" }.upload!!
            assertEquals(384, cfg.mode); assertTrue(String(cfg.data).contains(p.password))
            assertTrue(steps.single { it.id == "panel-fetch" }.command!!.contains("/v1.2.3/install.sh"))
            assertTrue(steps.single { it.id == "panel-fw" }.command!!.contains(ServerOps.FW_LIST))
            assertTrue(steps.last().command!!.contains(spec.mark))
        }
        assertTrue(OtherPanels.configScript(OtherPanels.SUI, OtherPanels.Params("v1.0.0", "u", "p", 1, "x")).contains("sui admin -username"))
        assertTrue(OtherPanels.configScript(OtherPanels.XUI, OtherPanels.Params("v1.0.0", "u", "p", 1, "x")).contains("x-ui setting -username"))
        val wipe = ServerOps.wipeSteps().joinToString("\n") { it.command.orEmpty() }
        assertTrue(OtherPanels.SUI.mark in wipe && OtherPanels.XUI.mark in wipe && OtherPanels.PANEL_STATE in wipe)
    }

    @Test fun panelStateAdoptedByAnotherDevice() {
        val panel = PanelInstance(PanelKind.SUI, "http://203.0.113.5:31000/abc/", "u", "p", "", "v1.6.4")
        val st = ServerStatus(suiInstalled = true, suiByHydra = true, panelStateJson = panel.toJson().toString())
        val (r, _) = MyServerService { error("") }.reconcile(MyServer("i", "n", "203.0.113.5", fingerprint = "x"), st)
        assertEquals(panel, r.panel)
        // панели на сервере больше нет — запись теряет её
        val (r2, _) = MyServerService { error("") }.reconcile(MyServer("i", "n", "h", fingerprint = "x", panel = panel), ServerStatus())
        assertNull(r2.panel)
        // подключённую по токену (без SSH) сверка не трогает
        val only = MyServer("i", "n", "h", fingerprint = "", panel = panel.copy(kind = PanelKind.XUI3, token = "t"))
        assertEquals(only.panel, MyServerService { error("") }.reconcile(only, ServerStatus()).first.panel)
    }

    /** Вход на сайт панели тем же запросом, что делает WebView ([PanelWeb.script]): cookie сессии, затем главная страница. */
    private fun webLogin(p: PanelInstance, base: String): Pair<String, Int> {
        val l = PanelWeb.login(p, base)!!
        val c = java.net.http.HttpClient.newBuilder().cookieHandler(java.net.CookieManager()).followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(8)).build()
        val origin = java.net.URL(base).let { "${it.protocol}://${it.authority}" }
        // как WebView: сначала страница входа (cookie и CSRF-токен 3x-ui v3), затем запрос входа
        val page = c.send(java.net.http.HttpRequest.newBuilder(java.net.URI(base.trimEnd('/') + "/")).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString())
        val req = java.net.http.HttpRequest.newBuilder(java.net.URI(l.url)).header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("X-Requested-With", "XMLHttpRequest").header("Origin", origin)
        PanelWeb.csrfToken(page.body())?.let { req.header(PanelWeb.CSRF_HEADER, it) }
        val r = c.send(req.POST(java.net.http.HttpRequest.BodyPublishers.ofString(l.body)).build(), java.net.http.HttpResponse.BodyHandlers.ofString())
        val home = c.send(java.net.http.HttpRequest.newBuilder(java.net.URI(l.then)).GET().build(), java.net.http.HttpResponse.BodyHandlers.discarding())
        return r.body() to home.statusCode()
    }

    private fun reachable(url: String): Boolean = runCatching {
        val u = java.net.URL(url); java.net.Socket().use { it.connect(java.net.InetSocketAddress(u.host, u.port), 4000) }; true
    }.getOrDefault(false)

    /**
     * 0.8.1-rc2 вживую для каждой панели: вход на сайт за пользователя, «закрыть порт извне» (снаружи не открывается, через
     * туннель — открывается и пускает), API 3x-ui по SSH у закрытой панели, открыть снова; HTTPS — сертификатом, выписанным на
     * сервере (Let's Encrypt на стенде без внешнего IP не выдаст), и откат на HTTP.
     */
    private fun rc2Live(m: MyServersManager, id: String, login: SshTarget, kind: ServerKind, servers: () -> List<MyServer>,
                        waitFor: (String, MyServersManager, () -> Boolean) -> Unit) {
        val panel = servers().single().panel!!
        val idle = { !m.ui.value.busy }
        if (kind == ServerKind.PANEL_SUI) {
            assertTrue(panel.baseUrl.startsWith("http://"), panel.baseUrl)
            assertTrue(m.ui.value.log.any { "Сертификат выдать не удалось" in it }, m.ui.value.log.takeLast(8).toString())
        }
        // 1. вход на сайт за пользователя
        val (ans, home) = webLogin(panel, panel.baseUrl)
        assertTrue("\"success\":true" in ans, "$kind вход: ${ans.take(200)}")
        assertEquals(200, home, "$kind главная после входа")
        println("WEBLOGIN $kind ok")

        // 2. закрыть порт панели извне
        assertTrue(reachable(panel.baseUrl), "$kind: до закрытия панель должна открываться снаружи")
        m.setPanelLocal(id, true)
        waitFor("закрыть $kind", m) { idle() && servers().single().panel?.local == true || m.ui.value.error != null }
        assertNull(m.ui.value.error, "$kind закрыть: ${m.ui.value.log.takeLast(10)}")
        assertFalse(reachable(panel.baseUrl), "$kind: после закрытия панель открывается снаружи")
        // туннель
        m.openPanelTunnel(id)
        waitFor("туннель $kind", m) { m.ui.value.tunnels[id] != null || m.ui.value.error != null }
        val tun = m.ui.value.tunnels[id] ?: fail("туннель: ${m.ui.value.error}")
        assertTrue(tun.startsWith("http://127.0.0.1:"), tun)
        val (ans2, home2) = webLogin(panel, tun)
        assertTrue("\"success\":true" in ans2 && home2 == 200, "$kind вход через туннель: ${ans2.take(200)} $home2")
        println("TUNNEL $kind $tun ok")
        // API 3x-ui у закрытой панели — по SSH
        if (kind == ServerKind.PANEL_3XUI) {
            m.loadPanelClients(id)
            waitFor("пользователи по SSH", m) { idle() && (m.ui.value.panelClients[id] != null || m.ui.value.error != null) }
            assertTrue(m.ui.value.panelClients[id].orEmpty().contains("hydra-1"), "${m.ui.value.error} ${m.ui.value.panelClients}")
        }
        m.closePanelTunnel(id)
        assertNull(m.ui.value.tunnels[id])
        m.setPanelLocal(id, false)
        waitFor("открыть $kind", m) { idle() && servers().single().panel?.local == false || m.ui.value.error != null }
        assertNull(m.ui.value.error, "$kind открыть: ${m.ui.value.log.takeLast(10)}")
        assertTrue(reachable(panel.baseUrl), "$kind: после открытия панель снова снаружи")

        // 3. HTTPS: сертификат выписан на сервере (как от acme.sh, только самоподписанный), панель его принимает, откат — на HTTP
        val u = java.net.URL(panel.baseUrl)
        SshConnector.connect(login, servers().single().fingerprint).use { ssh ->
            val d = XuiPanel.CERT_DIR
            assertEquals(0, ssh.exec("mkdir -p $d && openssl req -x509 -newkey rsa:2048 -nodes -subj /CN=hydra-test -days 1 -keyout $d/privkey.pem -out $d/fullchain.pem >/dev/null 2>&1").exit)
            val inst = Installer(ssh) { e -> if (e is InstallEvent.StepFailed) println("STEP FAILED ${e.step.id}: ${e.reason}") }
            assertTrue(inst.run(XuiPanel.certApplySteps(panel.kind, u.port, u.path)), "$kind: панель не поднялась с сертификатом")
            println("HTTPS $kind ok (cert-check: curl по https на сервере)")
            assertTrue(inst.run(listOf(MyServerService { error("") }.certRevertStep(panel.kind), OtherPanels.answersStep("http-back", "HTTP снова", "http", u.port, u.path))),
                "$kind: откат на HTTP")
            ssh.exec("rm -rf $d")
        }
    }

    /** Живой прогон: HYDRA_SSH_TEST и HYDRA_PANELS_LIVE=1. Ставит s-ui и x-ui из GitHub, проверяет вход и удаляет. */
    @Test fun installLoginAndWipeLive() {
        val v = System.getenv("HYDRA_SSH_TEST")
        assumeTrue(v != null && System.getenv("HYDRA_PANELS_LIVE") != null)
        val p = v!!.split(":", limit = 4)
        val login = SshTarget(p[0], p[1].toInt(), p[2], password = p[3])
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var saved = emptyList<MyServer>()
        val bundle = System.getenv("HYDRA_AWG_BUNDLE")
        val m = MyServersManager(scope, { saved }, { saved = it }, { AwgBundleLoader.fromDir(it, java.io.File(bundle!!)) }, MemoryVault()) { _, _ -> }
        fun waitFor(what: String, mgr: MyServersManager = m, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + 420_000
            Thread.sleep(150)
            while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(300) }
            fail("таймаут: $what; ${mgr.ui.value.error}; ${mgr.ui.value.log.takeLast(12)}")
        }
        SshConnector.connect(login, SshConnector.fingerprint(login)).use { Installer(it) {}.run(ServerOps.wipeSteps()); it.exec("rm -rf ${ServerOps.BACKUP_DIR}") }
        m.begin(ServerForm("panels", login))
        waitFor("отпечаток") { m.ui.value.stage == Stage.CONFIRM_FINGERPRINT }
        m.confirmFingerprint()
        waitFor("добавление") { m.ui.value.stage == Stage.DONE }
        val id = saved.single().id

        // смена панелей: 3x-ui → удалить → s-ui → удалить → x-ui → полная очистка → снова AmneziaWG
        for (kind in listOf(ServerKind.PANEL_3XUI, ServerKind.PANEL_SUI, ServerKind.PANEL_XUI)) {
            // s-ui — с галочкой HTTPS: на стенде без внешнего IP сертификат не выдаётся, панель должна остаться на HTTP (0.8.1-rc2)
            m.prepareInstall(id, ServiceForm(kind, accessPort = 24443, sslIp = kind != ServerKind.PANEL_XUI))
            waitFor("план $kind") { !m.ui.value.busy && (m.ui.value.install?.stage == InstallStage.READY || m.ui.value.error != null) }
            assertEquals(InstallStage.READY, m.ui.value.install?.stage, "$kind: ${m.ui.value.error}")
            m.runInstall()
            waitFor("установка $kind") { !m.ui.value.busy && m.ui.value.install?.stage in setOf(InstallStage.DONE, InstallStage.FAILED) }
            assertEquals(InstallStage.DONE, m.ui.value.install?.stage, "$kind: ${m.ui.value.error} ${m.ui.value.log.takeLast(14)}")
            m.closeInstall()
            val panel = saved.single().panel!!
            println("PANEL $kind ${panel.baseUrl} ${panel.version}")
            assertEquals(kind.panel, panel.kind)
            assertTrue(m.ui.value.log.none { panel.password in it }, "пароль в журнале")
            // панель отвечает по своему адресу (на сервере) и принимает наш логин (у 3x-ui — проверено токеном в XuiLiveTest)
            if (kind != ServerKind.PANEL_3XUI) SshConnector.connect(login, saved.single().fingerprint).use { ssh ->
                val url = java.net.URL(panel.baseUrl)
                val base = "http://127.0.0.1:${url.port}"
                // s-ui: {путь}api/login, поля user/pass и обязательный Origin; x-ui: {путь}login, username/password
                val r = if (kind == ServerKind.PANEL_SUI)
                    ssh.exec("curl -s --max-time 10 -X POST -H 'Origin: $base' -d 'user=${panel.username}&pass=${panel.password}' $base${url.path}api/login")
                else ssh.exec("curl -s --max-time 10 -X POST -d 'username=${panel.username}&password=${panel.password}' $base${url.path}login")
                assertTrue(r.out.contains("\"success\":true"), "$kind вход: ${r.out.take(300)}")
            }
            // второе устройство видит панель
            var saved2 = emptyList<MyServer>()
            val m2 = MyServersManager(scope, { saved2 }, { saved2 = it }, { error("") }, MemoryVault()) { _, _ -> }
            m2.begin(ServerForm("dev2", login, remember = false))
            waitFor("отпечаток 2", m2) { m2.ui.value.stage == Stage.CONFIRM_FINGERPRINT }
            m2.confirmFingerprint()
            waitFor("добавление 2", m2) { m2.ui.value.stage == Stage.DONE || m2.ui.value.stage == Stage.FAILED }
            assertEquals(panel, saved2.single().panel)

            rc2Live(m, id, login, kind, { saved }, ::waitFor)

            if (kind != ServerKind.PANEL_XUI) {
                m.uninstallService(id, kind)
                waitFor("удаление $kind") { !m.ui.value.busy && saved.single().panel == null }
                assertNull(m.ui.value.error)
            } else {
                m.wipe(id)
                waitFor("очистка") { !m.ui.value.busy && saved.single().panel == null }
                assertNull(m.ui.value.error, m.ui.value.log.takeLast(10).toString())
            }
            SshConnector.connect(login, saved.single().fingerprint).use { ssh ->
                assertTrue(ssh.exec("test -e /usr/local/s-ui || test -e /usr/local/x-ui || test -e ${OtherPanels.PANEL_STATE}").exit != 0, "$kind: остались файлы")
            }
        }
        // копии баз всех трёх удалённых панелей остались на сервере и пережили полную очистку (копий x-ui — две: 3x-ui и x-ui)
        SshConnector.connect(login, saved.single().fingerprint).use { ssh ->
            val list = ssh.exec("ls ${ServerOps.BACKUP_DIR}").out.lines().filter { it.endsWith(".db") }
            println("BACKUPS $list")
            assertEquals(2, list.count { it.startsWith("x-ui-") }, list.toString())
            assertEquals(1, list.count { it.startsWith("s-ui-") }, list.toString())
        }
        // возврат к AmneziaWG на том же сервере
        if (bundle != null) {
            m.prepareInstall(id, ServiceForm(ServerKind.AMNEZIAWG, AwgGeneration.V2_0, 51822))
            waitFor("план AWG") { !m.ui.value.busy && m.ui.value.install?.stage == InstallStage.READY }
            m.runInstall()
            waitFor("установка AWG") { !m.ui.value.busy && m.ui.value.install?.stage in setOf(InstallStage.DONE, InstallStage.FAILED) }
            assertEquals(InstallStage.DONE, m.ui.value.install?.stage, "${m.ui.value.error}")
            m.closeInstall()
            m.wipe(id)
            waitFor("очистка 2") { !m.ui.value.busy && saved.single().awg == null }
        }
    }
}
