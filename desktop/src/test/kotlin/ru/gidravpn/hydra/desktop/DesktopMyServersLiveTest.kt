package ru.gidravpn.hydra.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assume.assumeTrue
import ru.gidravpn.hydra.servers.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 0.8.2-a, A0: «на ПК нет возможности поставить и управлять сервером» (владелец 15.10.2026). Путь ПК целиком через
 * настоящий [AppController] (хранилище входа DPAPI, серверный пакет AmneziaWG — загрузкой из выпуска [HYDRA_AWG_VERSION]),
 * как его проходит кнопка на вкладке «Мои серверы»: добавить сервер с запоминанием входа → установить AmneziaWG → устройство →
 * подключение в «Серверах» → перезапуск → удаление → очистка. HYDRA_SSH_TEST=хост:порт:root:пароль, HYDRA_DESKTOP_LIVE=1.
 */
class DesktopMyServersLiveTest {
    @Test fun desktopInstallsAndManagesAwg() {
        val v = System.getenv("HYDRA_SSH_TEST")
        assumeTrue(v != null && System.getenv("HYDRA_DESKTOP_LIVE") != null)
        // версия, чей серверный пакет качается с GitHub (как у установленного приложения)
        System.setProperty("hydra.version", System.getenv("HYDRA_AWG_VERSION") ?: "0.8.1")
        val p = v!!.split(":", limit = 4)
        val login = SshTarget(p[0], p[1].toInt(), p[2], password = p[3])
        val dir = kotlin.io.path.createTempDirectory("hydra-desktop-live").toFile()
        val c = AppController(CoroutineScope(SupervisorJob() + Dispatchers.IO), Store(File(dir, "hydra.json")), background = false)
        val m = c.myServers
        fun waitFor(what: String, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + 420_000
            Thread.sleep(200)
            while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(300) }
            fail("таймаут: $what; ${m.ui.value.error}; ${m.ui.value.log.takeLast(12)}")
        }
        fun servers() = c.ui.value.data.myServers
        SshConnector.connect(login, SshConnector.fingerprint(login)).use { Installer(it) {}.run(ServerOps.wipeSteps()) }

        m.begin(ServerForm("ПК-тест", login, remember = true))
        waitFor("отпечаток") { m.ui.value.stage == Stage.CONFIRM_FINGERPRINT || m.ui.value.stage == Stage.FAILED }
        assertEquals(Stage.CONFIRM_FINGERPRINT, m.ui.value.stage, m.ui.value.error)
        m.confirmFingerprint()
        waitFor("добавление") { m.ui.value.stage == Stage.DONE || m.ui.value.stage == Stage.FAILED }
        assertEquals(Stage.DONE, m.ui.value.stage, m.ui.value.error)
        val id = servers().single().id
        assertTrue(m.hasSavedLogin(id), "вход не сохранён (хранилище ПК)")
        println("VAULT ${File(dir, "my_servers_login.dat").exists() || dir.listFiles()!!.map { it.name }.toString().contains("login")}: ${dir.listFiles()!!.map { it.name }}")

        m.prepareInstall(id, ServiceForm(ServerKind.AMNEZIAWG, AwgGeneration.V2_0, 51823))
        waitFor("план") { !m.ui.value.busy && (m.ui.value.install?.stage == InstallStage.READY || m.ui.value.error != null) }
        assertEquals(InstallStage.READY, m.ui.value.install?.stage, m.ui.value.error)
        m.runInstall()
        waitFor("установка") { !m.ui.value.busy && m.ui.value.install?.stage in setOf(InstallStage.DONE, InstallStage.FAILED) }
        assertEquals(InstallStage.DONE, m.ui.value.install?.stage, "${m.ui.value.error} ${m.ui.value.log.takeLast(14)}")
        m.closeInstall()
        assertTrue(servers().single().awg != null)
        // подключение добавлено в «Серверы» ПК
        assertTrue(c.ui.value.data.servers.any { it.protocolId.contains("awg", true) || it.protocolId.contains("amnezia", true) }, c.ui.value.data.servers.map { it.protocolId }.toString())
        println("PC SERVERS ${c.ui.value.data.servers.map { "${it.name}/${it.protocolId}" }}")

        m.addClient(id, "pc2")
        waitFor("устройство") { !m.ui.value.busy && m.ui.value.shown != null }
        assertTrue(m.ui.value.shown!!.second.contains("[Interface]"))
        m.hideShown()
        m.restartService(id, ServerKind.AMNEZIAWG)
        waitFor("перезапуск") { !m.ui.value.busy }
        assertNull(m.ui.value.error)
        m.refresh(id)
        waitFor("проверка") { !m.ui.value.busy && m.ui.value.status[id] != null }
        assertTrue(m.ui.value.status[id]!!.awgActive, m.ui.value.status[id].toString())
        m.uninstallService(id, ServerKind.AMNEZIAWG)
        waitFor("удаление") { !m.ui.value.busy && servers().single().awg == null }
        m.wipe(id)
        waitFor("очистка") { !m.ui.value.busy }
        assertNull(m.ui.value.error, m.ui.value.log.takeLast(10).toString())
        println("DESKTOP AWG PATH OK")
        dir.deleteRecursively()
    }
}
