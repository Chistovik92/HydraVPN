package ru.gidravpn.hydra.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.gidravpn.hydra.servers.AwgBundleLoader
import ru.gidravpn.hydra.servers.MyServer
import ru.gidravpn.hydra.servers.MyServersManager
import ru.gidravpn.hydra.servers.XuiPanel
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** 0.8.1-a: «У меня уже есть панель» — подключение 3x-ui по адресу и токену без SSH. */
class XuiConnectTest {
    private fun fake(token: String): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/p") { ex ->
            val path = ex.requestURI.path.removePrefix("/p")
            val ok = ex.requestHeaders.getFirst("Authorization") == "Bearer $token"
            val out = if (!ok) """{"success":false,"msg":"unauthorized"}""" else when (path) {
                "/panel/api/setting/all" -> """{"success":true,"obj":{}}"""
                "/panel/api/inbounds/list/slim" -> """{"success":true,"obj":[{"id":3,"protocol":"trojan"},{"id":9,"protocol":"vless"}]}"""
                else -> """{"success":false,"msg":"?"}"""
            }
            val b = out.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        start()
    }

    private fun manager(saved: () -> List<MyServer>, set: (List<MyServer>) -> Unit) =
        MyServersManager(CoroutineScope(SupervisorJob() + Dispatchers.IO), saved, set, { AwgBundleLoader.parse(it, ByteArray(0)) }) { _, _ -> }

    private fun wait(m: MyServersManager) {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) { if (!m.ui.value.busy && (m.ui.value.error != null || m.ui.value.progress.startsWith("Панель подключена"))) return; Thread.sleep(50) }
        fail("таймаут: ${m.ui.value}")
    }

    @Test fun connectsAndPicksVlessInbound() {
        val srv = fake("tok")
        try {
            var saved = emptyList<MyServer>()
            val m = manager({ saved }, { saved = it })
            m.connectPanel("Моя", "127.0.0.1:${srv.address.port}/p/", "tok")
            wait(m)
            val s = saved.single()
            assertEquals("127.0.0.1", s.host); assertEquals("", s.fingerprint)
            assertEquals("http://127.0.0.1:${srv.address.port}/p", s.panel!!.baseUrl)
            assertEquals(9, s.panel!!.inboundId)
        } finally { srv.stop(0) }
    }

    @Test fun wrongTokenIsReportedAndNothingSaved() {
        val srv = fake("tok")
        try {
            var saved = emptyList<MyServer>()
            val m = manager({ saved }, { saved = it })
            m.connectPanel("x", "http://127.0.0.1:${srv.address.port}/p", "bad")
            wait(m)
            assertTrue(saved.isEmpty()); assertTrue("токен" in m.ui.value.error.orEmpty(), m.ui.value.error)
        } finally { srv.stop(0) }
    }

    @Test fun urlNormalization() {
        assertEquals("http://1.2.3.4:2053/abc", XuiPanel.normalizeUrl(" 1.2.3.4:2053/abc/ "))
        assertEquals("https://p.example.com", XuiPanel.normalizeUrl("https://p.example.com"))
        assertNull(XuiPanel.normalizeUrl("  ")); assertNull(XuiPanel.normalizeUrl("a b"))
        assertFalse(XuiPanel.isHttps("http://x")); assertTrue(XuiPanel.isHttps("HTTPS://x"))
    }
}
