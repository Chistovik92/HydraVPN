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
 * 0.8.2-a4: ноды панелей вживую на двух серверах стенда. Главный — HYDRA_SSH_TEST (для 3x-ui панель уже стоит: её оставляет
 * `XuiLiveTest` с HYDRA_XUI_KEEP=1; PasarGuard и Remnawave тест ставит сам), нода — HYDRA_NODE_SSH (свободный сервер).
 * HYDRA_NODE_PANEL = XUI3 | PG | RW. Путь «как кнопками»: оба сервера в «Мои серверы» → «Добавить ноду» → ссылка подключения
 * на ноде → трафик через ноду ядром Hydra (HYDRA_XRAY / HYDRA_SINGBOX) → «Убрать ноду» → на сервере ноды ничего не осталось.
 */
class PanelNodesLiveTest {
    private fun target(v: String) = v.split(":", limit = 4).let { SshTarget(it[0], it[1].toInt(), it[2], password = it[3]) }

    @Test fun nodeAddTrafficRemove() {
        val a = System.getenv("HYDRA_SSH_TEST"); val b = System.getenv("HYDRA_NODE_SSH"); val which = System.getenv("HYDRA_NODE_PANEL")
        assumeTrue(a != null && b != null && which != null)
        val kind = when (which) { "PG" -> PanelKind.PASARGUARD; "RW" -> PanelKind.REMNAWAVE; else -> PanelKind.XUI3 }
        val la = target(a!!); val lb = target(b!!)
        val xray = System.getenv("HYDRA_XRAY"); val sb = System.getenv("HYDRA_SINGBOX")
        var saved = emptyList<MyServer>()
        val imported = mutableListOf<String>()
        val m = MyServersManager(CoroutineScope(SupervisorJob() + Dispatchers.IO), { saved }, { saved = it }, { error("") }, MemoryVault()) { _, c -> imported += c }
        fun waitFor(what: String, ms: Long = 1_200_000, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + ms
            Thread.sleep(200)
            while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(300) }
            fail("таймаут: $what; ${m.ui.value.error}; ${m.ui.value.log.takeLast(15)}")
        }
        fun add(name: String, t: SshTarget): String {
            m.begin(ServerForm(name, t)); waitFor("отпечаток $name") { m.ui.value.stage == Stage.CONFIRM_FINGERPRINT }
            m.confirmFingerprint(); waitFor("добавление $name") { m.ui.value.stage == Stage.DONE }
            return m.ui.value.addedId!!
        }
        if (kind != PanelKind.XUI3) SshConnector.connect(la, SshConnector.fingerprint(la)).use { ssh ->
            ssh.exec(if (kind == PanelKind.REMNAWAVE) RemnawavePanel.uninstallCommand() else PasarGuardPanel.uninstallCommand())
        }
        // сервер ноды — чистый
        SshConnector.connect(lb, SshConnector.fingerprint(lb)).use { ssh ->
            ssh.exec("systemctl disable --now x-ui >/dev/null 2>&1; rm -rf /usr/local/x-ui /etc/x-ui /usr/bin/x-ui /etc/systemd/system/x-ui.service; [ -x /usr/bin/x-ui ] && printf 'y\\n' | /usr/bin/x-ui uninstall >/dev/null 2>&1; ${PanelNodes.pgNodeUninstallCommand()}; ${PanelNodes.rwNodeUninstallCommand()}; rm -rf ${ServerOps.STATE_DIR}")
        }
        val idA = add("main", la)
        if (kind != PanelKind.XUI3) {
            m.prepareInstall(idA, ServiceForm(ServerKind.ofPanel(kind)))
            waitFor("план") { !m.ui.value.busy && m.ui.value.install?.stage == InstallStage.READY }
            m.runInstall()
            waitFor("установка") { !m.ui.value.busy && m.ui.value.install?.stage in setOf(InstallStage.DONE, InstallStage.FAILED) }
            assertEquals(InstallStage.DONE, m.ui.value.install?.stage, "${m.ui.value.error}")
            m.closeInstall()
            m.createPanelConnection(idA, XuiConnection.VLESS_GRPC_REALITY, 27100)
            waitFor("подключение на главном") { !m.ui.value.busy && (m.ui.value.shown != null || m.ui.value.error != null) }
            assertEquals(null, m.ui.value.error)
            m.hideShown()
        }
        assertEquals(kind, saved.first { it.id == idA }.panel?.kind, "на главном сервере нет панели ${kind.title}")
        val idB = add("node", lb)
        assertTrue(m.nodeCandidates(idA).any { it.id == idB })

        m.addPanelNode(idA, idB)
        waitFor("нода") { !m.ui.value.busy }
        println("NODELOG " + m.ui.value.log.takeLast(40).joinToString("\nNODELOG "))
        assertEquals(null, m.ui.value.error)
        val node = saved.first { it.id == idA }.panel!!.nodes.single()
        assertEquals(idB, node.serverId)
        assertEquals(idA, saved.first { it.id == idB }.nodeOf?.mainId)
        val links = m.ui.value.shown?.second?.lines()?.filter { it.isNotBlank() }.orEmpty()
        println("NODELINKS ${links.size}: " + links.joinToString(" | ") { it.replace(Regex("(pbk|password)=[^&\\s]+"), "$1=…").take(160) })
        assertTrue(links.isNotEmpty(), "нет ссылок на ноду")
        assertTrue(links.all { "@${lb.host}:" in it }, "ссылка не на адрес ноды")
        val prof = LinkParser.parseLine(links.first().trim()) ?: error("Hydra не разобрала ссылку")
        fun once() = if (EngineToggles().engineFor(prof, true) == EngineToggles.Kind.XRAY && xray != null) "xray: " + XuiTraffic.throughXray(xray, prof)
            else if (sb != null) "sing-box: " + XuiTraffic.through(sb, prof) else "ядро не задано"
        val r = runCatching { once().let { x -> if (": ok" in x) x else { Thread.sleep(8000); once() } } }.getOrElse { "ОШИБКА ${it.message?.take(300)}" }
        println("NODETRAFFIC $r")

        if (System.getenv("HYDRA_NODE_KEEP") == null) {
            m.removePanelNode(idA, idB)
            waitFor("удаление ноды") { !m.ui.value.busy }
            println("NODELOG2 " + m.ui.value.log.takeLast(12).joinToString("\nNODELOG2 "))
            assertEquals(null, m.ui.value.error)
            assertTrue(saved.first { it.id == idA }.panel!!.nodes.isEmpty())
            assertEquals(null, saved.first { it.id == idB }.nodeOf)
            SshConnector.connect(lb, saved.first { it.id == idB }.fingerprint).use { ssh ->
                assertEquals("", ssh.exec("ls -d /usr/local/x-ui ${PasarGuardPanel.NODE_DIR} ${RemnawavePanel.NODE_DIR} 2>/dev/null").out.trim())
            }
            if (kind != PanelKind.XUI3) { m.uninstallService(idA, ServerKind.ofPanel(kind)); waitFor("удаление панели") { !m.ui.value.busy } }
        }
        assertTrue(": ok" in r, r)
    }
}
