package ru.gidravpn.hydra.desktop

import org.json.JSONArray
import org.json.JSONObject
import ru.gidravpn.hydra.data.routing.RouteKind
import ru.gidravpn.hydra.data.routing.RoutePlan
import ru.gidravpn.hydra.data.routing.RoutePlanApplier
import ru.gidravpn.hydra.data.routing.RouteRule
import ru.gidravpn.hydra.data.routing.RouteTarget
import ru.gidravpn.hydra.data.tgws.TgWsPreset
import ru.gidravpn.hydra.data.tgws.TgWsProxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TgWsProxyTest {
    private val proxy = TgWsProxy()

    /** Клиентский 64-байтовый init с заданными тегом протокола и номером ЦОД (как делает Telegram). */
    private fun init(dc: Int, proto: Int = 0xEFEFEFEF.toInt()): ByteArray {
        val b = ByteArray(64).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(b.copyOfRange(8, 40), "AES"), IvParameterSpec(b.copyOfRange(40, 56)))
        val ks = c.update(ByteArray(64))
        val plain = byteArrayOf((proto ushr 24).toByte(), (proto ushr 16).toByte(), (proto ushr 8).toByte(), proto.toByte(), dc.toByte(), (dc shr 8).toByte())
        for (i in plain.indices) b[56 + i] = (plain[i].toInt() xor ks[56 + i].toInt()).toByte()
        return b
    }

    @Test fun `dc is read from the obfuscated init`() {
        assertEquals(2 to false, proxy.dcFromInit(init(2)))
        assertEquals(4 to true, proxy.dcFromInit(init(-4)))
        assertEquals(203 to false, proxy.dcFromInit(init(203)))
        assertEquals(5 to false, proxy.dcFromInit(init(5, 0xEEEEEEEE.toInt())))
    }

    @Test fun `init with a wrong protocol tag or dc is rejected`() {
        assertNull(proxy.dcFromInit(init(2, 0x12345678)))
        assertNull(proxy.dcFromInit(init(9)))
    }

    @Test fun `patching the dc round-trips`() {
        val patched = proxy.patchInitDc(init(1), -3)
        assertEquals(3 to true, proxy.dcFromInit(patched))
    }

    @Test fun `telegram ranges`() {
        assertTrue(TgWsProxy.isTelegramIp("149.154.167.51"))
        assertTrue(TgWsProxy.isTelegramIp("91.108.56.100"))
        assertTrue(TgWsProxy.isTelegramIp("185.76.151.7"))
        assertFalse(TgWsProxy.isTelegramIp("8.8.8.8"))
        assertFalse(TgWsProxy.isTelegramIp("example.com"))
    }

    @Test fun `ws domains for media come first with the -1 suffix`() {
        assertEquals(listOf("kws2.web.telegram.org", "kws2-1.web.telegram.org"), TgWsProxy.wsDomains(2, false))
        assertEquals(listOf("kws2-1.web.telegram.org", "kws2.web.telegram.org"), TgWsProxy.wsDomains(203, true))
    }

    @Test fun `splitter cuts glued abridged messages`() {
        val ini = init(2)
        val enc = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(ini.copyOfRange(8, 40), "AES"), IvParameterSpec(ini.copyOfRange(40, 56)))
            update(ByteArray(64))
        }
        // два сообщения: по 8 и 12 байт полезной нагрузки (длина в словах: 2 и 3)
        val plain = byteArrayOf(2) + ByteArray(8) { 1 } + byteArrayOf(3) + ByteArray(12) { 2 }
        val cipher = enc.update(plain)
        val parts = TgWsProxy.MsgSplitter(ini).split(cipher)
        assertEquals(2, parts.size)
        assertEquals(9, parts[0].size)
        assertEquals(13, parts[1].size)
        assertContentEquals(cipher, parts[0] + parts[1])
    }

    @Test fun `non-telegram traffic is passed through the socks server`() {
        val echo = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) { echo.accept().use { s -> val buf = ByteArray(5); s.getInputStream().readNBytes(buf, 0, 5); s.getOutputStream().write(buf) } }
        val p = TgWsProxy()
        val port = p.start(0)
        try {
            Socket("127.0.0.1", port).use { c ->
                c.soTimeout = 5000
                val o = c.getOutputStream(); val i = c.getInputStream()
                o.write(byteArrayOf(5, 1, 0)); o.flush()
                assertContentEquals(byteArrayOf(5, 0), i.readNBytes(2))
                val ep = echo.localPort
                o.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (ep shr 8).toByte(), ep.toByte())); o.flush()
                val r = i.readNBytes(10)
                assertEquals(0, r[1].toInt())
                o.write("hello".toByteArray()); o.flush()
                assertEquals("hello", String(i.readNBytes(5)))
            }
        } finally { p.stop(); echo.close() }
    }

    @Test fun `plan with a tgws rule gets a socks outbound and the preset round-trips`() {
        val root = JSONObject()
            .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")).put(JSONObject().put("type", "vless").put("tag", "proxy")))
            .put("route", JSONObject().put("rules", JSONArray()).put("final", "proxy"))
        val plan = RoutePlan(rules = TgWsPreset.rules())
        assertTrue(plan.needsTgWs)
        RoutePlanApplier.apply(root, plan)
        val tg = (0 until root.getJSONArray("outbounds").length()).map { root.getJSONArray("outbounds").getJSONObject(it) }.firstOrNull { it.optString("tag") == "tgws" }
        assertNotNull(tg)
        assertEquals(RouteTarget.TGWS_PORT, tg.getInt("server_port"))
        val rules = root.getJSONObject("route").getJSONArray("rules")
        assertTrue((0 until rules.length()).any { rules.getJSONObject(it).optString("outbound") == "tgws" })
        assertFalse(plan.withoutTgWs().needsTgWs)
        assertTrue(TgWsPreset.isApplied(TgWsPreset.rules() + RouteRule(RouteKind.DOMAIN, "x.com", RouteTarget.DIRECT)))
        assertFalse(TgWsPreset.isApplied(emptyList()))
    }
}

/** Живая проверка против серверов Telegram: `HYDRA_LIVE_TGWS=1 gradle :desktop:test --tests '*TgWsLiveTest*'`. */
class TgWsLiveTest {
    @Test fun `websocket handshake to a telegram dc succeeds through the proxy`() {
        if (System.getenv("HYDRA_LIVE_TGWS") != "1") return
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val p = TgWsProxy(log = { logs += it })
        val port = p.start(0)
        try {
            val helper = TgWsProxyTest()
            val init = TgWsProxyTest::class.java.getDeclaredMethod("init", Int::class.java, Int::class.java).apply { isAccessible = true }
                .invoke(helper, 2, 0xEFEFEFEF.toInt()) as ByteArray
            Socket("127.0.0.1", port).use { c ->
                c.soTimeout = 15000
                val o = c.getOutputStream(); val i = c.getInputStream()
                o.write(byteArrayOf(5, 1, 0)); o.flush(); i.readNBytes(2)
                val ip = java.net.InetAddress.getByName("149.154.167.51").address
                o.write(byteArrayOf(5, 1, 0, 1) + ip + byteArrayOf(1, 0xBB.toByte())); o.flush(); i.readNBytes(10)
                o.write(init); o.flush()
                Thread.sleep(4000)
            }
            println("TGWS live: ws=${p.wsConnections} tcpFallbacks=${p.tcpFallbacks} logs=$logs")
            assertTrue(p.wsConnections >= 1, "WebSocket не поднялся: $logs")
        } finally { p.stop() }
    }
}
