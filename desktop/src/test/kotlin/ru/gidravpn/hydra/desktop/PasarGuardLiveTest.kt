package ru.gidravpn.hydra.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assume.assumeTrue
import ru.gidravpn.hydra.data.model.EngineToggles
import ru.gidravpn.hydra.data.subscription.LinkParser
import ru.gidravpn.hydra.servers.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 0.8.2-a3: PasarGuard — установка мастером (Docker, панель, нода, владелец), затем каждый тип «как кнопкой»
 * ([MyServersManager.createPanelConnection]) и трафик по выданной ссылке ядром, которое выберет Hydra; в конце — удаление панели.
 * HYDRA_SSH_TEST, HYDRA_PG_LIVE=1, HYDRA_XRAY, HYDRA_SINGBOX; HYDRA_XUI_TYPES — только эти типы; HYDRA_XUI_KEEP — не удалять; HYDRA_PANEL=RW — то же для Remnawave.
 */
class PasarGuardLiveTest {
    @Test fun pasarGuardInstallConnectionsTraffic() {
        val v = System.getenv("HYDRA_SSH_TEST") ?: if (WslSession.install()) "${System.getenv("HYDRA_WSL_TARGET") ?: "172.29.99.250"}:22:root:-" else null
        assumeTrue(v != null && System.getenv("HYDRA_PG_LIVE") != null)
        val p = v!!.split(":", limit = 4)
        val login = SshTarget(p[0], p[1].toInt(), p[2], password = p[3])
        val xray = System.getenv("HYDRA_XRAY"); val sb = System.getenv("HYDRA_SINGBOX")
        val rw = System.getenv("HYDRA_PANEL") == "RW"
        val kind = if (rw) ServerKind.PANEL_REMNAWAVE else ServerKind.PANEL_PASARGUARD
        var saved = emptyList<MyServer>()
        val imported = mutableListOf<String>()
        val m = MyServersManager(CoroutineScope(SupervisorJob() + Dispatchers.IO), { saved }, { saved = it }, { error("") }, MemoryVault()) { _, c -> imported += c }
        fun waitFor(what: String, ms: Long = 900_000, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + ms
            Thread.sleep(200)
            while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(300) }
            fail("таймаут: $what; ${m.ui.value.error}; ${m.ui.value.log.takeLast(12)}")
        }
        // стенд: убрать прежнюю PasarGuard (в том числе поставленную вручную)
        SshConnector.connect(login, SshConnector.fingerprint(login)).use { ssh -> ssh.exec(if (rw) RemnawavePanel.uninstallCommand() else PasarGuardPanel.uninstallCommand()) }
        m.begin(ServerForm("pg", login)); waitFor("отпечаток") { m.ui.value.stage == Stage.CONFIRM_FINGERPRINT }
        m.confirmFingerprint(); waitFor("добавление") { m.ui.value.stage == Stage.DONE }
        val id = saved.single().id
        m.prepareInstall(id, ServiceForm(kind))
        waitFor("план") { !m.ui.value.busy && m.ui.value.install?.stage == InstallStage.READY }
        m.runInstall()
        waitFor("установка") { !m.ui.value.busy && m.ui.value.install?.stage in setOf(InstallStage.DONE, InstallStage.FAILED) }
        println("PGLOG " + m.ui.value.log.takeLast(30).joinToString("\nPGLOG "))
        assertEquals(InstallStage.DONE, m.ui.value.install?.stage, "${m.ui.value.error}")
        m.closeInstall()
        val panel = saved.single().panel!!
        assertEquals(kind.panel, panel.kind); assertTrue(panel.local)
        // проверка состояния видит панель и не теряет её
        m.refresh(id); waitFor("проверка") { !m.ui.value.busy && m.ui.value.status[id] != null }
        val st = m.ui.value.status[id]!!
        assertTrue(if (rw) st.rwInstalled && st.rwByHydra else st.pgInstalled && st.pgByHydra)
        assertEquals(kind.panel, saved.single().panel?.kind)

        val results = mutableListOf<Pair<String, String>>()
        var port = 27000
        for (type in OtherPanelConnections.typesFor(kind.panel!!).filter { t -> System.getenv("HYDRA_XUI_TYPES")?.split(",")?.contains(t.name) ?: true }) {
            m.hideShown()
            m.createPanelConnection(id, type, port++)
            waitFor("подключение $type") { !m.ui.value.busy && (m.ui.value.shown != null || m.ui.value.error != null) }
            val r = runCatching {
                val link = m.ui.value.shown?.second ?: error("${m.ui.value.error}")
                println("PGLINK $type: " + link.lines().first().replace(Regex("(pbk|password)=[^&\\s]+"), "$1=…").take(220))
                val prof = LinkParser.parseLine(link.lines().first().trim()) ?: error("Hydra не разобрала ссылку")
                fun once() = if (EngineToggles().engineFor(prof, true) == EngineToggles.Kind.XRAY && xray != null) "xray: " + XuiTraffic.throughXray(xray, prof)
                    else if (sb != null) "sing-box: " + XuiTraffic.through(sb, prof) else "ядро не задано"
                once().let { x -> if (": ok" in x) x else { Thread.sleep(5000); once() } }
            }.getOrElse { "ОШИБКА ${it.message?.take(300)}" }
            println("PGRESULT $type: $r")
            results += type.name to r
        }
        println("PGIMPORTED ${imported.size}")
        if (System.getenv("HYDRA_XUI_KEEP") == null) {
            m.uninstallService(id, kind); waitFor("удаление") { !m.ui.value.busy }
            assertEquals(null, saved.single().panel, "${m.ui.value.error}")
            SshConnector.connect(login, saved.single().fingerprint).use { ssh ->
                assertEquals("", ssh.exec("ls -d ${if (rw) RemnawavePanel.DIR + " " + RemnawavePanel.NODE_DIR else PasarGuardPanel.DIR + " " + PasarGuardPanel.NODE_DIR} 2>/dev/null").out.trim())
                assertTrue(ssh.exec("ls /root/hydra-backups/ 2>/dev/null").out.contains(if (rw) "remnawave-" else "pasarguard-"), "нет копии базы")
                // 0.8.2-rc2: нода PasarGuard v0.1.10 — нативная служба; после удаления она не должна работать и занимать порт 3000
                if (!rw) assertEquals("", ssh.exec("ls /usr/local/bin/pg-node-service /etc/systemd/system/pg-node-service.service 2>/dev/null").out.trim(), "служба ноды осталась")
            }
        }
        val bad = results.filter { ": ok" !in it.second }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }
}
