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


    // ---- 0.7.20: клиент Telegram для Android без обфускации и адреса по IPv6 ----

    private fun ctr(key: ByteArray, iv: ByteArray) = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    @Test fun `own init is valid and a relay can read our stream`() {
        for (dc in listOf(2, -4, 203)) {
            val obf = TgWsProxy.ObfClient(0xEFEFEFEF.toInt(), dc)
            assertEquals(Math.abs(dc) to (dc < 0), proxy.dcFromInit(obf.init), "ЦОД $dc читается ретранслятором из init")
            // Ретранслятор: ключ и IV из init, поток после 64 байт init.
            val rx = ctr(obf.init.copyOfRange(8, 40), obf.init.copyOfRange(40, 56)).apply { update(ByteArray(64)) }
            val msg = ByteArray(40) { it.toByte() }
            assertContentEquals(msg, rx.update(obf.encrypt(msg)))
            // Обратный поток: ключ - развёрнутые байты 8..56.
            val rev = obf.init.let { i ->
                val r = ctr(i.copyOfRange(8, 40), i.copyOfRange(40, 56))
                val ks = r.update(ByteArray(64))
                val plainInit = i.copyOf().also { for (k in 56 until 64) it[k] = (i[k].toInt() xor ks[k].toInt()).toByte() }
                plainInit.copyOfRange(8, 56).reversedArray()
            }
            val tx = ctr(rev.copyOfRange(0, 32), rev.copyOfRange(32, 48))
            val reply = ByteArray(24) { (it * 3).toByte() }
            assertContentEquals(reply, obf.decrypt(tx.update(reply)))
        }
    }

    @Test fun `own init never looks like http or a plain transport tag`() {
        repeat(300) {
            val i = TgWsProxy.ObfClient(0xEFEFEFEF.toInt(), 2).init
            assertTrue((i[0].toInt() and 255) != 0xEF)
            assertFalse(i.startsWithAscii("POST ") || i.startsWithAscii("GET ") || i.startsWithAscii("HEAD "))
        }
    }

    private fun ByteArray.startsWithAscii(p: String) = size >= p.length && p.indices.all { this[it].toInt() == p[it].code }

    @Test fun `framer cuts abridged messages and keeps a partial tail`() {
        val fr = TgWsProxy.PlainFramer(0xEFEFEFEF.toInt())
        fun m(words: Int) = byteArrayOf(words.toByte()) + ByteArray(words * 4) { 7 }
        val a = m(2); val b = m(3)
        val all = a + b
        assertEquals(listOf(9), fr.feed(all.copyOfRange(0, 11)).map { it.size })        // вторая - ещё неполная
        assertEquals(listOf(13), fr.feed(all.copyOfRange(11, all.size)).map { it.size })  // хвост достроен
        // Длинное сообщение: 0x7F + 3 байта длины в словах.
        val big = byteArrayOf(0x7F, 0x40, 0x00, 0x00) + ByteArray(0x40 * 4)
        assertEquals(listOf(4 + 256), TgWsProxy.PlainFramer(0xEFEFEFEF.toInt()).feed(big).map { it.size })
    }

    @Test fun `framer cuts intermediate messages`() {
        val fr = TgWsProxy.PlainFramer(0xEEEEEEEE.toInt())
        fun m(len: Int) = byteArrayOf(len.toByte(), 0, 0, 0) + ByteArray(len)
        assertEquals(listOf(12, 24), fr.feed(m(8) + m(20)).map { it.size })
    }

    @Test fun `ipv6 datacenter addresses are recognised`() {
        fun v6(s: String) = InetAddress.getByName(s).address
        assertEquals(2 to false, TgWsProxy.v6Dc(v6("2001:67c:4e8:f002::a")))
        assertEquals(2 to true, TgWsProxy.v6Dc(v6("2001:67c:4e8:f002::b")))
        assertEquals(1 to false, TgWsProxy.v6Dc(v6("2001:b28:f23d:f001::a")))
        assertEquals(5 to false, TgWsProxy.v6Dc(v6("2001:b28:f23f:f005::a")))
        assertNull(TgWsProxy.v6Dc(v6("2001:db8::1")))
        assertNull(TgWsProxy.v6Dc(v6("2001:67c:4e8:f009::a")))
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

    /** То, что делает Telegram, проверяя прокси: init, затем незашифрованное сообщение req_pq_multi - сервер обязан ответить. */
    @Test fun `datacenter answers a req_pq through the proxy`() {
        if (System.getenv("HYDRA_LIVE_TGWS") != "1") return
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val p = TgWsProxy(log = { logs += it })
        val port = p.start(0)
        try {
            val helper = TgWsProxyTest()
            val init = TgWsProxyTest::class.java.getDeclaredMethod("init", Int::class.java, Int::class.java).apply { isAccessible = true }
                .invoke(helper, 2, 0xEFEFEFEF.toInt()) as ByteArray
            val c = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(init.copyOfRange(8, 40), "AES"), javax.crypto.spec.IvParameterSpec(init.copyOfRange(40, 56)))
                update(ByteArray(64))
            }
            val body = ByteArray(40).also { b ->
                // auth_key_id = 0, message_id (чётный, «время»), длина 20, req_pq_multi#be7e8ef1 + nonce
                val id = System.currentTimeMillis() / 1000 shl 32
                for (k in 0 until 8) b[8 + k] = (id ushr (8 * k)).toByte()
                b[16] = 20
                b[20] = 0xF1.toByte(); b[21] = 0x8E.toByte(); b[22] = 0x7E.toByte(); b[23] = 0xBE.toByte()
                for (k in 24 until 40) b[k] = (k * 7).toByte()
            }
            val packet = c.update(byteArrayOf(10) + body)
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 20000
                val o = s.getOutputStream(); val i = s.getInputStream()
                o.write(byteArrayOf(5, 1, 0)); o.flush(); i.readNBytes(2)
                val ip = java.net.InetAddress.getByName("149.154.167.51").address
                o.write(byteArrayOf(5, 1, 0, 1) + ip + byteArrayOf(1, 0xBB.toByte())); o.flush(); i.readNBytes(10)
                o.write(init); o.write(packet); o.flush()
                val first = i.read()
                println("TGWS live req_pq: first byte=$first ws=${p.wsConnections} tcp=${p.tcpFallbacks} logs=$logs")
                assertTrue(first >= 0, "Сервер не ответил на req_pq: $logs")
            }
        } finally { p.stop() }
    }

    /** Клиент Telegram для Android: без секрета прокси он шлёт открытый транспорт (0xEF + abridged), а не obfuscated2. */
    @Test fun `plain abridged client gets an answer through the proxy`() {
        if (System.getenv("HYDRA_LIVE_TGWS") != "1") return
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val p = TgWsProxy(log = { logs += it })
        val port = p.start(0)
        try {
            val msg = ByteArray(40).also { b ->
                val id = System.currentTimeMillis() / 1000 shl 32
                for (k in 0 until 8) b[8 + k] = (id ushr (8 * k)).toByte()
                b[16] = 20
                b[20] = 0xF1.toByte(); b[21] = 0x8E.toByte(); b[22] = 0x7E.toByte(); b[23] = 0xBE.toByte()
                for (k in 24 until 40) b[k] = (k * 5).toByte()
            }
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 20000
                val o = s.getOutputStream(); val i = s.getInputStream()
                o.write(byteArrayOf(5, 2, 0, 2)); o.flush(); i.readNBytes(2)
                val ip = java.net.InetAddress.getByName("149.154.167.51").address
                o.write(byteArrayOf(5, 1, 0, 1) + ip + byteArrayOf(1, 0xBB.toByte())); o.flush(); i.readNBytes(10)
                o.write(byteArrayOf(0xEF.toByte(), 10) + msg); o.flush()
                val head = i.read()
                println("TGWS live plain: first byte=$head ws=${p.wsConnections} tcp=${p.tcpFallbacks} logs=$logs")
                // Ответ resPQ в abridged: длина в словах (0x7F + 3 байта, если больше 126).
                assertTrue(head > 0, "Сервер не ответил открытым ответом на req_pq: $logs")
            }
        } finally { p.stop() }
    }

    /** MTProto-прокси (`tg://proxy`, секрет `dd`): так подключается Telegram для Android; ответ DC расшифровывается ключом из секрета. */
    @Test fun `mtproto proxy mode answers a req_pq`() {
        if (System.getenv("HYDRA_LIVE_TGWS") != "1") return
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val p = TgWsProxy(log = { logs += it })
        p.start(0)
        val mtPort = p.startMtProto(0)
        try {
            val secret = TgWsProxy.MT_SECRET_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b)
            fun ctr(k: ByteArray, iv: ByteArray) = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(iv)) }
            val r = ByteArray(64)
            do { SecureRandom().nextBytes(r) } while ((r[0].toInt() and 255) == 0xEF || (r[4].toInt() == 0 && r[5].toInt() == 0 && r[6].toInt() == 0 && r[7].toInt() == 0))
            val enc = ctr(sha(r.copyOfRange(8, 40) + secret), r.copyOfRange(40, 56))
            val ks = enc.update(ByteArray(64))
            val tail = byteArrayOf(0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 2, 0, 1, 2)   // padded intermediate, ЦОД 2
            val hs = r.copyOf().also { for (i in 0 until 8) it[56 + i] = (tail[i].toInt() xor ks[56 + i].toInt()).toByte() }
            val rev = r.copyOfRange(8, 56).reversedArray()
            val dec = ctr(sha(rev.copyOfRange(0, 32) + secret), rev.copyOfRange(32, 48))
            val body = ByteArray(40).also { b ->
                val id = System.currentTimeMillis() / 1000 shl 32
                for (k in 0 until 8) b[8 + k] = (id ushr (8 * k)).toByte()
                b[16] = 20
                b[20] = 0xF1.toByte(); b[21] = 0x8E.toByte(); b[22] = 0x7E.toByte(); b[23] = 0xBE.toByte()
                for (k in 24 until 40) b[k] = (k * 9).toByte()
            }
            val packet = enc.update(byteArrayOf(40, 0, 0, 0) + body)             // intermediate: длина 4 байта LE + сообщение
            Socket("127.0.0.1", mtPort).use { s ->
                s.soTimeout = 20000
                val o = s.getOutputStream(); val i = s.getInputStream()
                o.write(hs); o.write(packet); o.flush()
                val first = i.readNBytes(4)
                val len = dec.update(first)
                val n = (len[0].toInt() and 255) or ((len[1].toInt() and 255) shl 8)
                println("TGWS live mtproto: reply length=$n ws=${p.wsConnections} tcp=${p.tcpFallbacks} logs=$logs")
                assertTrue(n in 20..2000, "Ответ DC не расшифровался (длина $n): $logs")
            }
        } finally { p.stop() }
    }
}
