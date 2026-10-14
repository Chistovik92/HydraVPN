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
        val s = MyServer("id1", "n", "198.51.100.7", 2200, "admin", "SHA256:xyz", awg = spec, clients = listOf(c))
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

    @Test fun ofxLinkAndPlanParsing() {
        val spec = OfxNodeSpec("203.0.113.9", "hydra", 24680, OfxNodeSpec.newKey(), vyandex = "https://docs.yandex.ru/edit/d/AbCdEfGhIjKlMnOpQrSt")
        val cfg = spec.config(true)
        assertTrue("channel=hydra" in cfg && "port=24680" in cfg && "vyandex=https://docs.yandex.ru" in cfg && "autoupdate=yes" in cfg)
        assertTrue("port=" !in spec.config(false))
        val c = ru.gidravpn.hydra.data.subscription.OpenFluxShare.decode(spec.shareLink("Мой узел"))
        assertEquals("Мой узел", c.name); assertTrue(c.negotiate); assertEquals(spec.key, c.secret)
        assertEquals(listOf("vyandex", "direct"), c.transports.map { it.type })
        assertEquals("203.0.113.9:24680", c.transports.last().dial)
        assertTrue(OfxNodeSpec.validChannel("my-node1") && !OfxNodeSpec.validChannel("My Node") && !OfxNodeSpec.validChannel("-x"))
        assertEquals(24680, OpenFluxNode.portOfPlan("noise\n{\"ok\":true,\"channel\":\"hydra\",\"port\":24680,\"arch\":\"amd64\"}"))
        assertEquals("input: нет", OpenFluxNode.errorOf("{\"ok\":false,\"step\":\"input\",\"error\":\"нет\"}"))
        // сохранение/чтение вместе с сервером
        val s = MyServer("i", "n", "203.0.113.9", fingerprint = "SHA256:x", ofx = spec)
        assertEquals(spec, MyServer.listFromJson(MyServer.listToJson(listOf(s))).single().ofx)
    }

    /** Узел OpenFlux по SSH на живом Linux (WSL): установка скриптом апстрима, служба, ссылка, удаление. */
    @Test fun openFluxNodeLive() {
        val v = System.getenv("HYDRA_SSH_TEST")
        assumeTrue(v != null && System.getenv("HYDRA_OFX_LIVE") != null)
        val p = v!!.split(":", limit = 4)
        val login = SshTarget(p[0], p[1].toInt(), p[2], password = p[3])
        var saved = emptyList<MyServer>()
        val imported = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val m = MyServersManager(scope, { saved }, { saved = it }, { error("не нужен") }, MemoryVault()) { _, conf -> imported += conf }
        fun waitFor(what: String, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + 240_000
            Thread.sleep(150)
            while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(200) }
            fail("таймаут: $what; ${m.ui.value.error}; ${m.ui.value.log.takeLast(10)}")
        }
        m.begin(ServerForm("node", login))
        waitFor("отпечаток") { m.ui.value.stage == Stage.CONFIRM_FINGERPRINT }
        m.confirmFingerprint()
        waitFor("добавление") { m.ui.value.stage == Stage.DONE }
        val id = saved.single().id
        m.prepareInstall(id, ServiceForm(ServerKind.OPENFLUX, channel = "hydratest"))
        waitFor("предполёт") { !m.ui.value.busy && m.ui.value.install?.stage == InstallStage.READY }
        m.runInstall()
        waitFor("установка") { !m.ui.value.busy && m.ui.value.install?.stage in setOf(InstallStage.DONE, InstallStage.FAILED) }
        assertEquals(InstallStage.DONE, m.ui.value.install?.stage, "${m.ui.value.error} ${m.ui.value.log.takeLast(12)}")
        val node = saved.single().ofx!!
        println("OFX_LINK=" + imported.single())
        java.io.File("build/ofx-link.txt").writeText(imported.single())
        println("OFX_PORT=${node.port}")
        assertTrue(node.port in 1024..65535 && imported.single().startsWith("openflux://v1/"))
        if (System.getenv("HYDRA_OFX_KEEP") == null) {
            m.uninstallService(id, ServerKind.OPENFLUX)
            waitFor("удаление") { !m.ui.value.busy && saved.single().ofx == null }
        }
    }
}

class PreReleaseVersionTest {
    private fun newer(r: String, c: String) = ru.gidravpn.hydra.update.UpdateFeed.isNewer(r, c)
    @kotlin.test.Test fun releaseIsNewerThanItsRc() {
        kotlin.test.assertTrue(newer("0.8.0", "0.8.0-rc1"))
        kotlin.test.assertTrue(newer("0.8.0-rc2", "0.8.0-rc1"))
        kotlin.test.assertTrue(!newer("0.8.0-rc1", "0.8.0"))
        kotlin.test.assertTrue(!newer("0.8.0-rc1", "0.8.0-rc1"))
        kotlin.test.assertTrue(newer("0.8.0-rc1", "0.7.20"))
        kotlin.test.assertTrue(!newer("0.7.20", "0.8.0-rc1"))
        // альфа < бета < rc < выпуск (схема версий, ROADMAP)
        kotlin.test.assertTrue(newer("0.8.1-b", "0.8.1-a") && newer("0.8.1-rc1", "0.8.1-b2") && newer("0.8.1", "0.8.1-rc3"))
        kotlin.test.assertTrue(!newer("0.8.1-a", "0.8.1-b") && !newer("0.8.1-b", "0.8.1-rc1") && !newer("0.8.1-a", "0.8.1-a"))
        kotlin.test.assertTrue(newer("0.8.1-a2", "0.8.1-a") && newer("0.8.1-a", "0.8.0"))
        kotlin.test.assertTrue(newer("0.8.1", "0.8.1-a-stub") && !newer("0.8.1-a", "0.8.1-a-stub"))
        // старые суффиксы не стали пре-релизами
        kotlin.test.assertTrue(!newer("0.7.20", "0.7.20-stub"))
        kotlin.test.assertTrue(newer("0.7.21", "0.7.20-stub"))
    }
}


/** Ссылка, выданная мастером узла, открывается настоящим клиентом OpenFlux: HYDRA_OFX_LINK (или build/ofx-link.txt) и скачанный openflux.exe. */
class OfxNodeClientLiveTest {
    @kotlin.test.Test fun linkWorksThroughRealClient() {
        val link = System.getenv("HYDRA_OFX_LINK") ?: java.io.File("build/ofx-link.txt").takeIf { it.isFile }?.readText()
        val exe = listOf("openflux.exe", "openflux").flatMap { n -> java.io.File("build/hydra-app-resources").listFiles()?.map { java.io.File(it, n) }.orEmpty() }
            .firstOrNull { it.isFile }
        org.junit.Assume.assumeTrue(System.getenv("HYDRA_OFX_CLIENT") != null && link != null && exe != null)
        val profile = ru.gidravpn.hydra.data.subscription.LinkParser.parseLine(link!!)!!
        val key = java.io.File.createTempFile("ofkey", ".txt").apply { deleteOnExit() }
        key.writeText(profile.uuidOrPassword)
        val socks = java.net.ServerSocket(0).use { it.localPort }
        val args = ru.gidravpn.hydra.data.subscription.OpenFluxArgs.build(profile, socks, key.absolutePath)
        val logf = java.io.File("build/ofx-client.log")
        val p = ProcessBuilder(listOf(exe!!.absolutePath) + args).redirectErrorStream(true).redirectOutput(logf).start()
        var body = ""
        try {
            val end = System.currentTimeMillis() + 40_000
            while (System.currentTimeMillis() < end && runCatching { java.net.Socket("127.0.0.1", socks).close() }.isFailure) Thread.sleep(200)
            java.net.Socket("127.0.0.1", socks).use { s ->
                s.soTimeout = 30_000
                val o = s.getOutputStream(); val i = s.getInputStream()
                o.write(byteArrayOf(5, 1, 0)); o.flush(); i.readNBytes(2)
                val host = "example.com".toByteArray()
                o.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf(0, 80)); o.flush()
                val reply = i.readNBytes(10)
                kotlin.test.assertEquals(0, reply[1].toInt(), "SOCKS5 CONNECT отклонён: ${reply.toList()}")
                o.write("GET / HTTP/1.0\r\nHost: example.com\r\n\r\n".toByteArray()); o.flush()
                // читаем, пока не увидим страницу: EOF через туннель может прийти позже
                val buf = ByteArray(4096); val sb = StringBuilder()
                try { while ("Example Domain" !in sb) { val n = i.read(buf); if (n < 0) break; sb.append(String(buf, 0, n)) } } catch (_: java.net.SocketTimeoutException) { }
                body = sb.toString()
            }
        } finally { p.destroyForcibly() }
        val log = logf.takeIf { it.isFile }?.readLines()?.takeLast(25)?.joinToString("\n").orEmpty()
        kotlin.test.assertTrue("Example Domain" in body, "ответ: ${body.take(200)}\n--- журнал клиента ---\n$log")
        println("OFX_CLIENT_OK")
    }
}
