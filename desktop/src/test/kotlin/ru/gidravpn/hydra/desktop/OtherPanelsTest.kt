package ru.gidravpn.hydra.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assume.assumeTrue
import ru.gidravpn.hydra.servers.*
import kotlin.test.Test
import kotlin.test.assertEquals
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
            m.prepareInstall(id, ServiceForm(kind, accessPort = 24443))
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
        // копии баз всех трёх удалённых панелей остались на сервере и пережили полную очистку
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
