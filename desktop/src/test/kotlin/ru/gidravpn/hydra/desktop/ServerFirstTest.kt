package ru.gidravpn.hydra.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assume.assumeTrue
import ru.gidravpn.hydra.servers.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** «Мои серверы» 0.8.0-rc4: сначала доступ к серверу, потом службы; сохранённый вход, проверка, очистка. */
class ServerFirstTest {

    @Test fun statusParsingAndReconcile() {
        val spec = AwgServerSpec.create("198.51.100.7", 51820, AwgGeneration.V2_0)
        val a = AwgServerSpec.newClient("a", "10.8.1.2/32")
        val b = AwgServerSpec.newClient("b", "10.8.1.3/32")
        val out = buildString {
            appendLine("OS=Debian GNU/Linux 12"); appendLine("ARCH=x86_64"); appendLine("UPTIME=7200"); appendLine("LOAD=0.1 0.2 0.3")
            appendLine("MEM=200/1000 МБ"); appendLine("DISK=2G/20G"); appendLine("AWG_UNIT=1"); appendLine("AWG_STATE=active"); appendLine("OFX=hydra:active ")
            appendLine("---AWGDUMP"); appendLine("PRIV\tPUB\t51820\toff")
            appendLine("${a.publicKey}\t(none)\t1.2.3.4:5555\t10.8.1.2/32\t1700000000\t100\t200\t25")
            appendLine("---AWGSTATE"); appendLine(ServerOps.awgStateJson(spec, listOf(a, b)))
            appendLine("---AWGCONF"); append(AwgInstallPlan.conf(spec, listOf(a, b)))
            appendLine("---OFXSTATE"); appendLine("---END")
        }
        val st = ServerOps.parseStatus(out)
        assertEquals("amd64", st.arch); assertTrue(st.awgActive); assertEquals(1, st.awgPeers.size)
        assertEquals(1700000000L, st.awgPeers[0].latestHandshake); assertEquals(200L, st.awgPeers[0].tx)
        assertEquals(mapOf("hydra" to "active"), st.ofxChannels)
        val svc = MyServerService { error("не нужен") }
        // запись без службы забирает её с сервера (другое устройство)
        val (r, write) = svc.reconcile(MyServer("i", "n", "198.51.100.7", fingerprint = "SHA256:x"), st)
        assertEquals(spec, r.awg); assertEquals(listOf(a, b), r.clients); assertTrue(!write)
        // копии нет (сервер поставлен rc1–rc3) — восстановление по конфигу сервера, без закрытых ключей клиентов, и запись копии
        val (r2, write2) = svc.reconcile(MyServer("i", "n", "198.51.100.7", fingerprint = "SHA256:x"), st.copy(awgStateJson = ""))
        assertTrue(write2)
        assertEquals(spec.privateKey, r2.awg!!.privateKey); assertEquals(spec.publicKey, r2.awg!!.publicKey)
        assertEquals(spec.params, r2.awg!!.params); assertEquals(spec.port, r2.awg!!.port); assertEquals(spec.generation, r2.awg!!.generation)
        assertEquals(listOf("a", "b"), r2.clients.map { it.name }); assertTrue(r2.clients.all { it.privateKey.isEmpty() })
        // копии нет, но запись есть — запись остаётся, копия пишется
        val (r4, write4) = svc.reconcile(MyServer("i", "n", "198.51.100.7", fingerprint = "x", awg = spec, clients = listOf(a)), st.copy(awgStateJson = ""))
        assertTrue(write4); assertEquals(listOf(a), r4.clients)
        // ключ клиента, известный только этому устройству, не теряется при подхвате копии без него
        val noKey = ServerOps.awgStateJson(spec, listOf(a.copy(privateKey = ""), b))
        val (r5, _) = svc.reconcile(MyServer("i", "n", "198.51.100.7", fingerprint = "x", awg = spec, clients = listOf(a)), st.copy(awgStateJson = noKey))
        assertEquals(a.privateKey, r5.clients.first { it.name == "a" }.privateKey)
        // службы нет — запись её теряет
        val (r3, _) = svc.reconcile(MyServer("i", "n", "h", fingerprint = "x", awg = spec, clients = listOf(a)), st.copy(awgInstalled = false))
        assertNull(r3.awg); assertTrue(r3.clients.isEmpty())
    }

    /** Запись не теряет поля более новой версии при пересохранении (так в rc4 потерялось поле panel из 0.8.1-a). */
    @Test fun unknownFieldsSurviveRoundTrip() {
        val json = org.json.JSONObject().put("id", "1").put("name", "n").put("host", "h").put("fingerprint", "SHA256:x")
            .put("futureThing", org.json.JSONObject().put("a", 1)).put("clients", org.json.JSONArray())
        val s = MyServer.fromJson(json)
        val back = s.copy(name = "renamed").toJson()
        assertEquals(1, back.getJSONObject("futureThing").getInt("a"))
        assertEquals("renamed", back.getString("name"))
        assertTrue(MyServer("2", "n", "h", fingerprint = "").broken)
    }

    @Test fun vaultEncryptsAndSurvivesReload() {
        val dir = kotlin.io.path.createTempDirectory("vault").toFile()
        val v = FileVault(File(dir, "v.dat"), KeyFileCipher(File(dir, "v.key")))
        v.save("a", SavedLogin(password = "сверхсекрет-123"))
        assertTrue("сверхсекрет" !in File(dir, "v.dat").readText())
        val v2 = FileVault(File(dir, "v.dat"), KeyFileCipher(File(dir, "v.key")))
        assertEquals("сверхсекрет-123", v2.load("a")?.password)
        assertEquals(setOf("a"), v2.ids())
        v2.remove("a"); assertNull(v.load("a"))
        // ключ сменился — запись просто «не найдена», без падения
        v.save("b", SavedLogin(privateKey = "KEY"))
        File(dir, "v.key").writeBytes(ByteArray(32))
        assertNull(FileVault(File(dir, "v.dat"), KeyFileCipher(File(dir, "v.key"))).load("b"))
    }

    @Test fun desktopVaultRoundTrip() {
        val dir = kotlin.io.path.createTempDirectory("dvault").toFile()
        val v = ru.gidravpn.hydra.desktop.core.DesktopVault.create(dir)
        v.save("x", SavedLogin("p@ss", null, null))
        assertEquals("p@ss", ru.gidravpn.hydra.desktop.core.DesktopVault.create(dir).load("x")?.password)
        assertTrue("p@ss" !in File(dir, "servers-login.dat").readText())
    }

    @Test fun hostValidationWithoutNetwork() {
        for (ok in listOf("203.0.113.5", "vps.example.com", "my-host", "2001:db8::1", "[2001:db8::1]")) assertTrue(MyServersManager.validHost(ok), ok)
        for (bad in listOf("", "1.2.3.400", "a b", "http://x", "-bad.com", "1.2.3")) assertTrue(!MyServersManager.validHost(bad), bad)
    }

    @Test fun plansCarryStateAndCleanupIsComplete() {
        val spec = AwgServerSpec.create("h", 51820, AwgGeneration.V2_0)
        val bundle = AwgBundle("amd64", listOf("amneziawg-go", "awg", "awg-quick").associateWith { BundleFile(it, ByteArray(1), "") })
        val steps = AwgInstallPlan.steps(spec, emptyList(), bundle, openFirewall = true, firewall = "ufw")
        assertTrue(steps.any { it.upload?.path == ServerOps.AWG_STATE && it.upload!!.mode == 384 })
        assertTrue(steps.first { it.id == "fw" }.command!!.contains(ServerOps.FW_LIST))
        assertTrue(AwgInstallPlan.applyClientsSteps(spec, emptyList()).any { it.upload?.path == ServerOps.AWG_STATE })
        assertTrue(AwgInstallPlan.uninstallSteps(spec).any { it.id == "fw-close" })
        val wipe = ServerOps.wipeSteps().joinToString("\n") { it.command.orEmpty() }
        for (x in listOf("hydra-awg", "/usr/local/bin/amneziawg-go", "node-install.sh uninstall", ServerOps.FW_LIST, "rm -rf ${ServerOps.STATE_DIR}", "99-hydra-awg.conf"))
            assertTrue(x in wipe, x)
    }

    /**
     * Весь путь на настоящем Linux: HYDRA_SSH_TEST=host:port:root:password и HYDRA_AWG_BUNDLE.
     * Добавить сервер (вход запоминается) → поставить AmneziaWG → устройства → проверка → «другое устройство» забирает сервер
     * под управление по копии на сервере → удалить службу → поставить снова → полная очистка.
     */
    @Test fun serverFirstEndToEndLive() {
        val v = System.getenv("HYDRA_SSH_TEST")
        val dir = System.getenv("HYDRA_AWG_BUNDLE")
        assumeTrue(v != null && dir != null)
        val p = v!!.split(":", limit = 4)
        val login = SshTarget(p[0], p[1].toInt(), p[2], password = p[3])
        var saved = emptyList<MyServer>()
        val imported = mutableListOf<String>()
        val vault = MemoryVault()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val m = MyServersManager(scope, { saved }, { saved = it }, { AwgBundleLoader.fromDir(it, File(dir)) }, vault) { _, conf -> imported += conf }

        fun waitFor(what: String, mgr: MyServersManager = m, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + 180_000
            Thread.sleep(150)
            while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(100) }
            fail("таймаут: $what; ${mgr.ui.value.error}; busy=${mgr.ui.value.busy} install=${mgr.ui.value.install?.stage} login=${mgr.ui.value.login}; ${mgr.ui.value.log.takeLast(8)}")
        }

        // чистый сервер
        SshConnector.connect(login, SshConnector.fingerprint(login)).use { Installer(it) {}.run(ServerOps.wipeSteps()) }

        m.begin(ServerForm("e2e", login, remember = true))
        waitFor("отпечаток") { m.ui.value.stage == Stage.CONFIRM_FINGERPRINT }
        m.confirmFingerprint()
        waitFor("добавление") { m.ui.value.stage == Stage.DONE || m.ui.value.stage == Stage.FAILED }
        assertEquals(Stage.DONE, m.ui.value.stage, "${m.ui.value.error}")
        val id = saved.single().id
        assertNull(saved.single().awg)
        assertNotNull(vault.load(id), "вход запомнен")

        m.prepareInstall(id, ServiceForm(ServerKind.AMNEZIAWG, AwgGeneration.V2_0, 51821))
        waitFor("план") { !m.ui.value.busy && m.ui.value.install?.stage == InstallStage.READY || (!m.ui.value.busy && m.ui.value.error != null) }
        assertEquals(InstallStage.READY, m.ui.value.install?.stage, "${m.ui.value.error}")
        m.runInstall()
        waitFor("установка") { !m.ui.value.busy && m.ui.value.install?.stage in setOf(InstallStage.DONE, InstallStage.FAILED) }
        assertEquals(InstallStage.DONE, m.ui.value.install?.stage, "${m.ui.value.error} ${m.ui.value.log.takeLast(10)}")
        m.closeInstall()
        assertEquals(1, imported.size)
        assertEquals(1, saved.single().clients.size)

        m.addClient(id, "second")
        waitFor("клиент") { !m.ui.value.busy && saved.single().clients.size == 2 }
        val conf2 = m.clientConf(id, "second")
        assertNotNull(conf2); assertTrue("Endpoint = ${login.host}:51821" in conf2!!)

        m.refresh(id)
        waitFor("проверка") { !m.ui.value.busy && m.ui.value.status[id] != null }
        val st = m.ui.value.status[id]!!
        assertTrue(st.awgActive && st.awgPeers.size == 2, st.toString())

        // «другое устройство»: пустой список, тот же сервер — службы и клиенты подхватываются с сервера
        var saved2 = emptyList<MyServer>()
        val m2 = MyServersManager(scope, { saved2 }, { saved2 = it }, { error("не нужен") }, MemoryVault()) { _, _ -> }
        m2.begin(ServerForm("second device", login, remember = false))
        waitFor("отпечаток 2", m2) { m2.ui.value.stage == Stage.CONFIRM_FINGERPRINT }
        m2.confirmFingerprint()
        waitFor("добавление 2", m2) { m2.ui.value.stage == Stage.DONE || m2.ui.value.stage == Stage.FAILED }
        assertEquals(saved.single().awg, saved2.single().awg)
        assertEquals(saved.single().clients, saved2.single().clients)

        m.removeClient(id, "second")
        waitFor("удаление клиента") { !m.ui.value.busy && saved.single().clients.size == 1 }

        m.uninstallService(id, ServerKind.AMNEZIAWG)
        waitFor("удаление службы") { !m.ui.value.busy && saved.single().awg == null }
        SshConnector.connect(login, saved.single().fingerprint).use { ssh ->
            assertTrue(ssh.exec("test -e /etc/amnezia/amneziawg/awg0.conf").exit != 0)
        }

        // снова поставить и очистить сервер целиком
        m.prepareInstall(id, ServiceForm(ServerKind.AMNEZIAWG, AwgGeneration.V1_0, 51821))
        waitFor("план 2") { !m.ui.value.busy && m.ui.value.install?.stage == InstallStage.READY }
        m.runInstall()
        waitFor("установка 2") { !m.ui.value.busy && m.ui.value.install?.stage in setOf(InstallStage.DONE, InstallStage.FAILED) }
        assertEquals(InstallStage.DONE, m.ui.value.install?.stage, "${m.ui.value.error}")
        m.closeInstall()
        m.wipe(id)
        waitFor("очистка") { !m.ui.value.busy && saved.single().awg == null }
        assertNull(m.ui.value.error, m.ui.value.log.takeLast(10).toString())
        SshConnector.connect(login, saved.single().fingerprint).use { ssh ->
            assertTrue(ssh.exec("test -e /etc/hydra || test -e /usr/local/bin/amneziawg-go || test -e /etc/systemd/system/hydra-awg.service").exit != 0)
        }
        m.forget(id)
        assertTrue(saved.isEmpty() && vault.load(id) == null)
    }
}
