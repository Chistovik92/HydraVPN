package ru.gidravpn.hydra.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assume.assumeTrue
import ru.gidravpn.hydra.data.subscription.LinkParser
import ru.gidravpn.hydra.servers.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 0.8.2-rc2 (A1, A2): пользователи s-ui и x-ui «как кнопками» — установка панели мастером, подключение, затем
 * «Пользователи» → «Добавить пользователя» (подписка в один клик) → трафик по ссылке из подписки этого пользователя ядром Xray → «Удалить».
 * Стенд без пароля: HYDRA_WSL_STAND=<distro>, HYDRA_XUIA_LIVE=1, HYDRA_PANEL=SUI|XUI (по умолчанию x-ui), HYDRA_XRAY.
 */
class OtherPanelUsersLiveTest {
    @Test fun userLifecycle() {
        val wsl = WslSession.install()
        val v = System.getenv("HYDRA_SSH_TEST") ?: if (wsl) "${System.getenv("HYDRA_WSL_TARGET") ?: "172.29.99.250"}:22:root:-" else null
        assumeTrue(v != null && System.getenv("HYDRA_XUIA_LIVE") != null && System.getenv("HYDRA_XRAY") != null)
        val p = v!!.split(":", limit = 4)
        val login = SshTarget(p[0], p[1].toInt(), p[2], password = p[3])
        val xray = System.getenv("HYDRA_XRAY")!!
        val kind = if (System.getenv("HYDRA_PANEL") == "SUI") ServerKind.PANEL_SUI else ServerKind.PANEL_XUI
        var saved = emptyList<MyServer>()
        val m = MyServersManager(CoroutineScope(SupervisorJob() + Dispatchers.IO), { saved }, { saved = it }, { error("") }, MemoryVault()) { _, _ -> }
        fun waitFor(what: String, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + 420_000
            Thread.sleep(200)
            while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(300) }
            fail("таймаут: $what; ${m.ui.value.error}; ${m.ui.value.log.takeLast(12)}")
        }
        fun idle() = waitFor("конец операции") { !m.ui.value.busy }
        SshConnector.connect(login, SshConnector.fingerprint(login)).use { ssh ->
            Installer(ssh) {}.run(ServerOps.wipeSteps())
            ssh.exec("for s in x-ui s-ui; do systemctl disable --now \$s >/dev/null 2>&1; rm -f /etc/systemd/system/\$s.service /usr/bin/\$s; done; rm -rf /usr/local/x-ui /etc/x-ui /usr/local/s-ui; systemctl daemon-reload")
        }
        m.begin(ServerForm("users", login)); waitFor("отпечаток") { m.ui.value.stage == Stage.CONFIRM_FINGERPRINT }
        m.confirmFingerprint(); waitFor("добавление") { m.ui.value.stage == Stage.DONE }
        val id = saved.single().id
        m.prepareInstall(id, ServiceForm(kind, sslIp = false))
        waitFor("план") { !m.ui.value.busy && m.ui.value.install?.stage == InstallStage.READY }
        m.runInstall()
        waitFor("установка") { !m.ui.value.busy && m.ui.value.install?.stage in setOf(InstallStage.DONE, InstallStage.FAILED) }
        assertEquals(InstallStage.DONE, m.ui.value.install?.stage, "${m.ui.value.error}")
        m.closeInstall()
        assertTrue(OtherPanelUsers.supported(saved.single()), "интерфейс должен показывать пользователей для ${kind.name}")
        try {
            // подключение для пользователей
            m.createPanelConnection(id, XuiConnection.VLESS_TCP_REALITY, 26100)
            waitFor("подключение") { !m.ui.value.busy && (m.ui.value.shown != null || m.ui.value.error != null) }
            assertTrue(m.ui.value.shown != null, "${m.ui.value.error}")
            m.hideShown()

            m.loadPanelClients(id); idle()
            val before = m.ui.value.panelClients[id].orEmpty()
            println("USERS before: $before")

            m.addPanelClient(id, "alice"); idle()
            val access = m.ui.value.shown?.second ?: fail("пользователь не добавлен: ${m.ui.value.error}")
            println("USER access: " + access.lines().first().take(120))
            assertTrue("alice" in m.ui.value.panelClients[id].orEmpty(), "alice должна быть в списке: ${m.ui.value.panelClients[id]}")

            // подписка в один клик: адрес отдаёт ссылки, трафик идёт по ссылке именно этого пользователя
            val link = if (access.startsWith("http")) {
                val body = Thread.sleep(1500).let { java.net.URL(access).readText().trim() }
                val text = runCatching { String(java.util.Base64.getMimeDecoder().decode(body)) }.getOrDefault(body)
                text.lines().map { it.trim() }.firstOrNull { "://" in it } ?: fail("подписка пуста: ${body.take(100)}")
            } else access.lines().first { "://" in it }
            val prof = LinkParser.parseLine(link) ?: fail("Hydra не разобрала ссылку: ${link.take(60)}")
            println("USER link: " + link.replace(Regex("(pbk|sid)=[^&#]+"), "$1=…").take(200))
            // клиента панель применяет к ядру не мгновенно: до трёх попыток с паузами, чтобы отличить задержку от настоящей ошибки
            var traffic = ""
            for (pause in listOf(0L, 8000L, 20000L)) {
                Thread.sleep(pause)
                traffic = XuiTraffic.throughXray(xray, prof)
                println("USER traffic (после паузы ${pause / 1000} с): ${traffic.take(60)}")
                if (traffic.startsWith("ok")) break
            }
            assertTrue(traffic.startsWith("ok"), traffic)

            // повторный показ ссылки
            m.hideShown(); m.showPanelLinks(id, "alice"); idle()
            assertTrue(m.ui.value.shown != null, "ссылка пользователя не показана: ${m.ui.value.error}")

            // A3: копия базы → ещё один пользователь → восстановление из копии: bob пропадает, alice на месте
            SshConnector.connect(login, saved.single().fingerprint).use { ssh -> assertTrue(Installer(ssh) {}.run(listOf(ServerOps.backupPanelsStep()))) }
            m.addPanelClient(id, "bob"); idle()
            assertTrue("bob" in m.ui.value.panelClients[id].orEmpty(), "bob должен быть в списке: ${m.ui.value.error}")
            m.hideShown()
            m.loadBackups(id); idle()
            val backup = m.ui.value.backups[id].orEmpty().firstOrNull() ?: fail("копия не найдена: ${m.ui.value.error}")
            println("BACKUP: ${backup.file} (${backup.title})")
            m.restoreBackup(id, backup.file); idle()
            assertTrue(m.ui.value.error == null, "восстановление: ${m.ui.value.error}")
            m.loadPanelClients(id); idle()
            val restored = m.ui.value.panelClients[id].orEmpty()
            println("USERS after restore: $restored")
            assertTrue("alice" in restored && "bob" !in restored, "после восстановления: $restored")

            // удаление
            m.removePanelClient(id, "alice"); idle()
            assertTrue("alice" !in m.ui.value.panelClients[id].orEmpty(), "alice должна исчезнуть: ${m.ui.value.error}")
            assertEquals(before, m.ui.value.panelClients[id].orEmpty(), "прежние пользователи не должны пострадать")
        } finally {
            if (System.getenv("HYDRA_XUI_KEEP") == null) { m.wipe(id); idle() }
        }
    }
}
