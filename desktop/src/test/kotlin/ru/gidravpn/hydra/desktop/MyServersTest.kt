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

class MyServersTest {
    @Test fun jsonRoundTrip() {
        val spec = AwgServerSpec.create("198.51.100.7", 443, AwgGeneration.V3)
        val c = AwgServerSpec.newClient("a", "10.8.1.2/32")
        val s = MyServer("id1", "n", "198.51.100.7", 2200, "admin", "SHA256:xyz", spec, listOf(c))
        val back = MyServer.listFromJson(MyServer.listToJson(listOf(s))).single()
        assertEquals(s.copy(createdAt = 0), back.copy(createdAt = 0))
        assertEquals(spec.clientConf(c), back.awg!!.clientConf(back.clients.single()))
        assertTrue(MyServer.listFromJson("мусор").isEmpty())
    }

    @Test fun bundleZipParsesAndRejectsIncomplete() {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            for (n in listOf("amneziawg-go", "awg", "awg-quick")) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(n.toByteArray()); z.closeEntry() }
        }
        val b = AwgBundleLoader.parse("amd64", bos.toByteArray())
        assertEquals("awg", String(b.file("awg").data))
        val bad = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bad).use { z -> z.putNextEntry(java.util.zip.ZipEntry("awg")); z.write(1); z.closeEntry() }
        runCatching { AwgBundleLoader.parse("amd64", bad.toByteArray()) }.let { assertTrue(it.isFailure) }
    }

    /** Весь мастер на настоящем Linux: HYDRA_SSH_TEST (root) и HYDRA_AWG_BUNDLE. */
    @Test fun wizardEndToEndLive() {
        val v = System.getenv("HYDRA_SSH_TEST")
        val dir = System.getenv("HYDRA_AWG_BUNDLE")
        assumeTrue(v != null && dir != null)
        val p = v!!.split(":", limit = 4)
        val login = SshTarget(p[0], p[1].toInt(), p[2], password = p[3])
        var saved = emptyList<MyServer>()
        val imported = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val m = MyServersManager(scope, { saved }, { saved = it }, { AwgBundleLoader.fromDir(it, File(dir)) }) { _, conf -> imported += conf }

        fun waitFor(what: String, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(100) }
            fail("таймаут: $what; ${m.ui.value.error}; ${m.ui.value.log.takeLast(8)}")
        }

        m.begin(ServerForm("e2e", login, AwgGeneration.V1_5, 51821, openFirewall = false))
        waitFor("отпечаток") { m.ui.value.stage == Stage.CONFIRM_FINGERPRINT }
        assertTrue(m.ui.value.fingerprint.startsWith("SHA256:"))
        m.confirmFingerprint()
        waitFor("предполёт") { m.ui.value.stage == Stage.READY }
        assertTrue(m.ui.value.plan.isNotEmpty())
        m.install()
        waitFor("установка") { m.ui.value.stage == Stage.DONE || m.ui.value.stage == Stage.FAILED }
        assertEquals(Stage.DONE, m.ui.value.stage, "${m.ui.value.error} ${m.ui.value.log.takeLast(10)}")
        val srv = saved.single()
        assertEquals(1, imported.size)
        assertEquals(1, srv.clients.size)

        m.addClient(srv.id, login, "second")
        waitFor("клиент") { !m.ui.value.busy && saved.single().clients.size == 2 }
        // сервер видит обоих пиров, живая служба не перезапускалась
        SshConnector.connect(login, srv.fingerprint).use { ssh ->
            val peers = ssh.exec("WG_QUICK_USERSPACE_IMPLEMENTATION=amneziawg-go /usr/local/bin/awg show awg0 peers").out.lines().filter { it.isNotBlank() }
            assertEquals(2, peers.size, peers.toString())
        }
        val conf2 = m.clientConf(srv.id, "second")
        assertNotNull(conf2); assertTrue("Endpoint = ${login.host}:51821" in conf2!!)

        m.removeClient(srv.id, login, "second")
        waitFor("удаление клиента") { !m.ui.value.busy && saved.single().clients.size == 1 }

        m.uninstall(srv.id, login)
        waitFor("удаление сервера") { !m.ui.value.busy && saved.isEmpty() }
        SshConnector.connect(login, srv.fingerprint).use { ssh ->
            assertTrue(ssh.exec("test -e /etc/amnezia/amneziawg/awg0.conf").exit != 0)
        }
        // запись о сервере для чужого/неверного отпечатка не проходит
        assertNull(m.clientConf("нет", "x"))
    }
}
