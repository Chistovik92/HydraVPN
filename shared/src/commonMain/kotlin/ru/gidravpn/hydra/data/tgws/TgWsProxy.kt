package ru.gidravpn.hydra.data.tgws

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import ru.gidravpn.hydra.data.routing.RouteKind
import ru.gidravpn.hydra.data.routing.RouteRule
import ru.gidravpn.hydra.data.routing.RouteTarget
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * «Telegram через WebSocket»: локальный SOCKS5, который вместо обычного TCP до серверов Telegram (их режет и душит DPI)
 * поднимает WebSocket поверх TLS до `kws<N>.web.telegram.org/apiws` и гонит по нему тот же MTProto-поток.
 * Номер ЦОД читается из 64-байтового «obfuscated init» клиента Telegram. Не-Telegram адреса проходят напрямую.
 *
 * Собственная реализация на JVM (Android и ПК) без внешних зависимостей. Если WebSocket недоступен (302/ошибка) — откат на прямой TCP.
 */
class TgWsProxy(
    private val dcIps: Map<Int, String> = DEFAULT_DC_IPS,
    private val log: (String) -> Unit = {},
) {
    private var server: ServerSocket? = null
    private var mtServer: ServerSocket? = null
    private val stopped = AtomicBoolean(true)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "tgws").apply { isDaemon = true } }
    /** ЦОД, где WebSocket отвечает редиректом: ходим по TCP, не тратя время на заведомо мёртвый путь. */
    private val wsBlacklist = ConcurrentHashMap.newKeySet<Pair<Int, Boolean>>()
    private val failUntil = ConcurrentHashMap<Pair<Int, Boolean>, Long>()
    /** Живые клиентские соединения: [stop] закрывает их, иначе после «Отключить» Telegram продолжал бы идти мимо туннеля. */
    private val active = ConcurrentHashMap.newKeySet<Socket>()
    /** Потолок одновременных соединений: локальная программа не должна исчерпать потоки процесса Hydra. */
    private val slots = java.util.concurrent.Semaphore(MAX_CONNECTIONS)

    val running get() = !stopped.get()
    @Volatile var wsConnections = 0L; private set
    @Volatile var tcpFallbacks = 0L; private set

    /** Поднимает слушатель на 127.0.0.1; [port] 0 — любой свободный. Возвращает занятый порт. */
    @Synchronized
    fun start(port: Int = 0): Int {
        if (running) return server!!.localPort
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        server = s
        stopped.set(false)
        pool.execute {
            while (!stopped.get()) {
                val c = try { s.accept() } catch (_: IOException) { break }
                if (!slots.tryAcquire()) { runCatching { c.close() }; continue }
                active += c
                pool.execute { try { handle(c) } finally { active -= c; slots.release() } }
            }
        }
        log("TG WS: слушаю 127.0.0.1:${s.localPort}")
        return s.localPort
    }

    /** Дополнительно к SOCKS5: MTProto-прокси на [mtPort] (0.7.20). Ошибка порта - только запись в журнал, SOCKS5 остаётся. */
    @Synchronized
    fun startMtProto(mtPort: Int): Int {
        mtServer?.let { return it.localPort }
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), mtPort))
        mtServer = s
        pool.execute {
            while (!stopped.get()) {
                val c = try { s.accept() } catch (_: IOException) { break }
                if (!slots.tryAcquire()) { runCatching { c.close() }; continue }
                active += c
                pool.execute { try { handleMt(c) } finally { active -= c; slots.release() } }
            }
        }
        log("TG WS: MTProto-прокси 127.0.0.1:${s.localPort}")
        return s.localPort
    }

    @Synchronized
    fun stop() {
        stopped.set(true)
        runCatching { server?.close() }
        server = null
        runCatching { mtServer?.close() }
        mtServer = null
        active.toList().forEach { runCatching { it.close() } }
        active.clear()
    }

    // ---- SOCKS5 -----------------------------------------------------------------------------------------------

    private fun handle(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = 15_000
            val inp = BufferedInputStream(client.getInputStream(), 65536)
            val out = client.getOutputStream()
            if (inp.read() != 5) return
            val n = inp.read(); if (n < 0) return
            inp.skipFully(n)
            out.write(byteArrayOf(5, 0)); out.flush()

            val req = inp.readFully(4)
            if (req[1].toInt() != 1) { out.write(reply(7)); return }
            var v6: ByteArray? = null
            val dst = when (req[3].toInt()) {
                1 -> InetAddress.getByAddress(inp.readFully(4)).hostAddress
                3 -> String(inp.readFully(inp.read()), Charsets.UTF_8)
                // 0.7.20: Telegram на устройстве с IPv6 проверяет и использует адреса ЦОД по IPv6 - раньше отвечали «адрес не поддерживается».
                4 -> { val a = inp.readFully(16); v6 = a; InetAddress.getByAddress(a).hostAddress }
                else -> { out.write(reply(8)); return }
            }
            val port = ((inp.read() shl 8) or inp.read())

            val telegram = if (v6 != null) v6Dc(v6) != null else isTelegramIp(dst)
            if (!telegram) { passthrough(client, inp, out, dst, port, null); return }

            out.write(reply(0)); out.flush()
            // Первый байт отличает обфусцированный init (случайный: не 0xEF и не EEEEEEEE/DDDDDDDD) от «чистого» транспорта Telegram
            // для Android (0.7.20): он без секрета прокси начинает поток с 0xEF (abridged) или 0xEEEEEEEE/0xDDDDDDDD (intermediate).
            val b0 = inp.read(); if (b0 < 0) return
            var plainTag = 0
            var prefix = ByteArray(0)
            var init: ByteArray? = null
            if (b0 == 0xEF) { plainTag = TAG_ABRIDGED; prefix = byteArrayOf(0xEF.toByte()) }
            else {
                val h = byteArrayOf(b0.toByte()) + inp.readFully(3)
                if (h.all { it == 0xEE.toByte() }) { plainTag = TAG_INTERMEDIATE; prefix = h }
                else if (h.all { it == 0xDD.toByte() }) { plainTag = TAG_PADDED; prefix = h }
                else init = h + inp.readFully(60)
            }

            if (init == null) {
                val byIp = if (v6 != null) v6Dc(v6) else IP_TO_DC[dst]
                val dc = byIp?.first
                val media = byIp?.second ?: false
                if (dc == null || dc !in dcIps) { tcpFallback(client, inp, out, dst, port, prefix, "неизвестный ЦОД"); return }
                client.soTimeout = 0
                val ws = openWs(dc, media)
                if (ws == null) { tcpFallback(client, inp, out, dst, port, prefix, "WebSocket недоступен"); return }
                wsConnections++
                // Свою обфускацию строим сами: ретранслятор Telegram ждёт obfuscated2, а клиент говорит открытым текстом.
                val obf = ObfClient(plainTag, if (media) -dc else dc)
                ws.send(obf.init)
                bridgePlain(client, inp, out, ws, obf, PlainFramer(plainTag))
                return
            }

            // HTTP-транспорт Telegram не поддерживается - клиент сам вернётся к MTProto.
            if (init.startsWithAscii("POST ") || init.startsWithAscii("GET ") || init.startsWithAscii("HEAD ")) return

            var dc: Int?
            var media: Boolean
            val parsed = dcFromInit(init)
            var patched = false
            if (parsed != null) { dc = parsed.first; media = parsed.second }
            else {
                // Мобильные клиенты без секрета оставляют случайные байты ЦОД - берём по адресу и правим init.
                val byIp = if (v6 != null) v6Dc(v6) else IP_TO_DC[dst]
                dc = byIp?.first; media = byIp?.second ?: false
                if (dc != null && dc in dcIps) { init = patchInitDc(init, if (media) -dc else dc); patched = true }
            }
            if (dc == null || dc !in dcIps) { tcpFallback(client, inp, out, dst, port, init, "неизвестный ЦОД"); return }

            client.soTimeout = 0
            val ws = openWs(dc, media)
            if (ws == null) { tcpFallback(client, inp, out, dst, port, init, "WebSocket недоступен"); return }

            wsConnections++
            val splitter = if (patched) runCatching { MsgSplitter(init) }.getOrNull() else null
            ws.send(init)
            bridge(client, inp, out, ws, splitter)
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
        }
    }

    // ---- MTProto-прокси (0.7.20) --------------------------------------------------------------------------------

    /**
     * Telegram для Android при SOCKS5 шлёт не обфусцированный поток, а MTProto-прокси с секретом он принимает везде (так сделано и в
     * Telegram Desktop). Протокол: клиент шлёт 64 байта init, ключ которых - SHA-256(init[8..40] + секрет); дальше поток идёт тем же шифром.
     * Мы расшифровываем init, берём ЦОД и тег транспорта, открываем WebSocket до ЦОД со своим obfuscated2 ([ObfClient]) и перешифровываем поток.
     */
    private fun handleMt(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = 15_000
            val inp = BufferedInputStream(client.getInputStream(), 65536)
            val out = client.getOutputStream()
            val hs = inp.readFully(64)
            val cltDec = ctr(sha256(hs.copyOfRange(8, 40) + MT_SECRET), hs.copyOfRange(40, 56))
            val plain = cltDec.update(hs)
            val tag = ((plain[56].toInt() and 255) shl 24) or ((plain[57].toInt() and 255) shl 16) or ((plain[58].toInt() and 255) shl 8) or (plain[59].toInt() and 255)
            if (tag !in VALID_PROTOS) { log("TG WS: MTProto-прокси: неверный секрет или протокол"); return }
            val dcRaw = ((plain[60].toInt() and 255) or (plain[61].toInt() shl 8)).toShort().toInt()
            val dc = kotlin.math.abs(dcRaw)
            val media = dcRaw < 0
            if (dc !in dcIps) { log("TG WS: MTProto-прокси: неизвестный ЦОД $dc"); return }
            val rev = hs.copyOfRange(8, 56).reversedArray()
            val cltEnc = ctr(sha256(rev.copyOfRange(0, 32) + MT_SECRET), rev.copyOfRange(32, 48))
            client.soTimeout = 0

            val obf = ObfClient(tag, dcRaw)
            val ws = openWs(dc, media)
            if (ws != null) {
                wsConnections++
                ws.send(obf.init)
                bridgePlain(client, inp, out, ws, obf, PlainFramer(tag), cltDec, cltEnc)
                return
            }
            // WebSocket недоступен - прямой TCP до ЦОД с тем же перешифрованием потока.
            log("TG WS: MTProto-прокси: WebSocket недоступен → прямой TCP до ЦОД$dc")
            tcpFallbacks++
            val remote = Socket()
            try {
                remote.connect(InetSocketAddress(dcIps.getValue(dc), 443), 10_000)
                remote.tcpNoDelay = true
                remote.getOutputStream().apply { write(obf.init); flush() }
                val up = Thread {
                    try {
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            remote.getOutputStream().write(obf.encrypt(cltDec.update(buf, 0, n))); remote.getOutputStream().flush()
                        }
                    } catch (_: Exception) {
                    } finally { runCatching { remote.close() }; runCatching { client.close() } }
                }.apply { isDaemon = true; start() }
                val buf = ByteArray(65536)
                val rin = remote.getInputStream()
                while (true) { val n = rin.read(buf); if (n < 0) break; out.write(cltEnc.update(obf.decrypt(buf.copyOf(n)))); out.flush() }
                up.interrupt()
            } finally { runCatching { remote.close() } }
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
        }
    }

    private fun ctr(key: ByteArray, iv: ByteArray) = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    private fun sha256(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b)


    /** WebSocket до ЦОД: сначала домены `kws*`, у каждого - адреса из DNS и запасной. Помнит редиректы и недавние сбои. */
    private fun openWs(dc: Int, media: Boolean): WebSocket? {
        val key = dc to media
        if (key in wsBlacklist) return null
        val now = System.currentTimeMillis()
        val timeout = if (now < (failUntil[key] ?: 0L)) 2_000 else 10_000
        var allRedirects = true
        for (domain in wsDomains(dc, media)) {
            for (ip in candidateIps(domain, dcIps.getValue(dc))) {
                try { return WebSocket.connect(ip, domain, timeout).also { allRedirects = false } }
                catch (e: WebSocket.Redirect) { log("TG WS: ЦОД$dc $domain ($ip) → редирект ${e.code}") }
                catch (e: Exception) { allRedirects = false; log("TG WS: ЦОД$dc $domain ($ip): ${e.message}") }
            }
        }
        if (allRedirects) wsBlacklist += key else failUntil[key] = now + 30_000
        return null
    }

    /** Клиент говорит открытым MTProto: режем его по границам сообщений, шифруем своим потоком и в обратную сторону расшифровываем. */
    private fun bridgePlain(client: Socket, inp: InputStream, out: OutputStream, ws: WebSocket, obf: ObfClient, framer: PlainFramer,
                            cltDec: Cipher? = null, cltEnc: Cipher? = null) {
        val up = Thread {
            try {
                val buf = ByteArray(65536)
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    val chunk = if (cltDec != null) cltDec.update(buf, 0, n) else buf.copyOf(n)
                    for (m in framer.feed(chunk)) ws.send(obf.encrypt(m))
                }
            } catch (_: Exception) {
            } finally { ws.close(); runCatching { client.close() } }
        }.apply { isDaemon = true; start() }
        try {
            while (true) { val d = ws.recv() ?: break; val p = obf.decrypt(d); out.write(if (cltEnc != null) cltEnc.update(p) else p); out.flush() }
        } catch (_: Exception) {
        } finally { ws.close(); runCatching { client.close() }; up.interrupt() }
    }

    /**
     * Адреса для WebSocket: что отдаёт DNS для домена `kws*` (у части провайдеров заблокированы отдельные адреса ЦОД, а адреса фронтов
     * живы) и запасной адрес из таблицы. Не больше трёх, без повторов.
     */
    private val resolved = ConcurrentHashMap<String, List<String>>()
    private fun candidateIps(domain: String, fallback: String): List<String> {
        val fromDns = resolved.getOrPut(domain) {
            runCatching { InetAddress.getAllByName(domain).filterIsInstance<java.net.Inet4Address>().mapNotNull { it.hostAddress } }.getOrDefault(emptyList())
        }
        return (fromDns + fallback).distinct().take(3)
    }

    private fun bridge(client: Socket, inp: InputStream, out: OutputStream, ws: WebSocket, splitter: MsgSplitter?) {
        val up = Thread {
            try {
                val buf = ByteArray(65536)
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    val chunk = buf.copyOf(n)
                    val parts = splitter?.split(chunk) ?: listOf(chunk)
                    parts.forEach { ws.send(it) }
                }
            } catch (_: Exception) {
            } finally { ws.close(); runCatching { client.close() } }
        }.apply { isDaemon = true; start() }
        try {
            while (true) { val d = ws.recv() ?: break; out.write(d); out.flush() }
        } catch (_: Exception) {
        } finally { ws.close(); runCatching { client.close() }; up.interrupt() }
    }

    private fun tcpFallback(client: Socket, inp: InputStream, out: OutputStream, dst: String, port: Int, init: ByteArray, why: String) {
        log("TG WS: $why → прямой TCP до $dst:$port")
        tcpFallbacks++
        passthrough(client, inp, out, dst, port, init, reply = false)
    }

    private fun passthrough(client: Socket, inp: InputStream, out: OutputStream, dst: String, port: Int, first: ByteArray?, reply: Boolean = true) {
        val remote = Socket()
        try {
            remote.connect(InetSocketAddress(dst, port), 10_000)
        } catch (_: Exception) {
            if (reply) out.write(reply(5))
            return
        }
        try {
            remote.tcpNoDelay = true
            client.soTimeout = 0
            if (reply) { out.write(reply(0)); out.flush() }
            first?.let { remote.getOutputStream().write(it) }
            val t = Thread { runCatching { inp.copyTo(remote.getOutputStream()) }; runCatching { remote.shutdownOutput() } }.apply { isDaemon = true; start() }
            runCatching { remote.getInputStream().copyTo(out) }
            runCatching { client.close() }
            t.interrupt()
        } finally { runCatching { remote.close() } }
    }

    private fun reply(status: Int) = byteArrayOf(5, status.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)

    // ---- MTProto: ЦОД из init ---------------------------------------------------------------------------------

    /** Клиентский init: AES-256-CTR, ключ `init[8..40)`, IV `init[40..56)`; открытые байты 56..64 = тег протокола + номер ЦОД. */
    fun dcFromInit(init: ByteArray): Pair<Int, Boolean>? = try {
        val ks = keystream(init)
        val plain = ByteArray(8) { (init[56 + it].toInt() xor ks[56 + it].toInt()).toByte() }
        val proto = ((plain[0].toInt() and 255) shl 24) or ((plain[1].toInt() and 255) shl 16) or ((plain[2].toInt() and 255) shl 8) or (plain[3].toInt() and 255)
        val dcRaw = ((plain[4].toInt() and 255) or (plain[5].toInt() shl 8)).toShort().toInt()
        val dc = kotlin.math.abs(dcRaw)
        if (proto in VALID_PROTOS && (dc in 1..5 || dc == 203)) dc to (dcRaw < 0) else null
    } catch (_: Exception) { null }

    fun patchInitDc(init: ByteArray, dc: Int): ByteArray {
        val ks = keystream(init)
        val p = init.copyOf()
        p[60] = (ks[60].toInt() xor (dc and 255)).toByte()
        p[61] = (ks[61].toInt() xor ((dc shr 8) and 255)).toByte()
        return p
    }

    private fun keystream(init: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(init.copyOfRange(8, 40), "AES"), IvParameterSpec(init.copyOfRange(40, 56)))
        return c.update(ByteArray(64))
    }

    /**
     * Реле Telegram разбирает по одному MTProto-сообщению на кадр WebSocket, а клиент может склеить несколько в одну запись
     * (ack + req_DH_params) — режем поток по границам сообщений (abridged), расшифровав его тем же потоком AES-CTR.
     */
    class MsgSplitter(init: ByteArray) {
        private val dec = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(init.copyOfRange(8, 40), "AES"), IvParameterSpec(init.copyOfRange(40, 56)))
            update(ByteArray(64))
        }

        fun split(chunk: ByteArray): List<ByteArray> {
            val plain = dec.update(chunk)
            val ends = ArrayList<Int>()
            var pos = 0
            while (pos < plain.size) {
                val first = plain[pos].toInt() and 255
                val len: Int
                if (first == 0x7f) {
                    if (pos + 4 > plain.size) break
                    len = ((plain[pos + 1].toInt() and 255) or ((plain[pos + 2].toInt() and 255) shl 8) or ((plain[pos + 3].toInt() and 255) shl 16)) * 4
                    pos += 4
                } else { len = first * 4; pos += 1 }
                if (len == 0 || pos + len > plain.size) break
                pos += len
                ends += pos
            }
            if (ends.size <= 1) return listOf(chunk)
            val parts = ArrayList<ByteArray>()
            var prev = 0
            for (e in ends) { parts += chunk.copyOfRange(prev, e); prev = e }
            if (prev < chunk.size) parts += chunk.copyOfRange(prev, chunk.size)
            return parts
        }
    }


    /**
     * Сеанс obfuscated2 со стороны клиента (0.7.20), когда настоящий клиент Telegram обфускацию не использует: свой init со случайными
     * ключом и IV, тегом протокола и номером ЦОД; исходящий поток шифруется ключом из init, входящий - тем же, но развёрнутым (как в MTProxy).
     */
    class ObfClient(tag: Int, dc: Int) {
        val init: ByteArray
        private val enc: Cipher
        private val dec: Cipher

        init {
            val rnd = SecureRandom()
            val r = ByteArray(64)
            while (true) {
                rnd.nextBytes(r)
                if ((r[0].toInt() and 255) == 0xEF) continue
                val first = ((r[0].toInt() and 255) shl 24) or ((r[1].toInt() and 255) shl 16) or ((r[2].toInt() and 255) shl 8) or (r[3].toInt() and 255)
                // Не похоже на HTTP и на открытые теги транспорта; байты 4..8 не нулевые.
                if (first in BAD_FIRST || (r[4].toInt() == 0 && r[5].toInt() == 0 && r[6].toInt() == 0 && r[7].toInt() == 0)) continue
                break
            }
            r[56] = (tag ushr 24).toByte(); r[57] = (tag ushr 16).toByte(); r[58] = (tag ushr 8).toByte(); r[59] = tag.toByte()
            r[60] = (dc and 255).toByte(); r[61] = ((dc shr 8) and 255).toByte()
            enc = ctr(r.copyOfRange(8, 40), r.copyOfRange(40, 56))
            val ks = enc.update(ByteArray(64))
            init = r.copyOf()
            for (i in 56 until 64) init[i] = (r[i].toInt() xor ks[i].toInt()).toByte()
            val rev = r.copyOfRange(8, 56).reversedArray()
            dec = ctr(rev.copyOfRange(0, 32), rev.copyOfRange(32, 48))
        }

        fun encrypt(data: ByteArray): ByteArray = enc.update(data)
        fun decrypt(data: ByteArray): ByteArray = dec.update(data)

        private fun ctr(key: ByteArray, iv: ByteArray) = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }

        private companion object {
            val BAD_FIRST = setOf(0x44414548, 0x54534F50, 0x20544547, 0x4954504F, 0xDDDDDDDD.toInt(), 0xEEEEEEEE.toInt(), 0x02010316)
        }
    }

    /** Границы сообщений открытого транспорта Telegram (abridged / intermediate / padded), с накоплением неполного хвоста между чтениями. */
    class PlainFramer(private val tag: Int) {
        private var buf = ByteArray(0)

        fun feed(chunk: ByteArray): List<ByteArray> {
            buf += chunk
            val out = ArrayList<ByteArray>()
            var pos = 0
            while (true) {
                val avail = buf.size - pos
                val total: Int
                if (tag == TAG_ABRIDGED) {
                    if (avail < 1) break
                    val f = buf[pos].toInt() and 0x7F          // старший бит - запрос быстрого подтверждения
                    if (f == 0x7F) {
                        if (avail < 4) break
                        total = 4 + ((buf[pos + 1].toInt() and 255) or ((buf[pos + 2].toInt() and 255) shl 8) or ((buf[pos + 3].toInt() and 255) shl 16)) * 4
                    } else total = 1 + f * 4
                } else {
                    if (avail < 4) break
                    val len = ((buf[pos].toInt() and 255) or ((buf[pos + 1].toInt() and 255) shl 8) or ((buf[pos + 2].toInt() and 255) shl 16) or ((buf[pos + 3].toInt() and 127) shl 24))
                    total = 4 + len
                }
                if (total <= 0 || total > MAX_MESSAGE) throw IOException("недопустимая длина сообщения MTProto: $total")
                if (avail < total) break
                out += buf.copyOfRange(pos, pos + total)
                pos += total
            }
            buf = buf.copyOfRange(pos, buf.size)
            return out
        }

        private companion object { const val MAX_MESSAGE = 16 * 1024 * 1024 }
    }

    // ---- WebSocket --------------------------------------------------------------------------------------------

    internal class WebSocket private constructor(private val sock: Socket, private val inp: InputStream, private val out: OutputStream) {
        class Redirect(val code: Int) : IOException("HTTP $code")
        @Volatile private var closed = false
        private val rnd = SecureRandom()

        @Synchronized
        fun send(data: ByteArray) {
            if (closed) throw IOException("closed")
            out.write(frame(2, data)); out.flush()
        }

        /** Следующее бинарное сообщение; `null` — закрыто. Ping отвечаем pong. */
        fun recv(): ByteArray? {
            var frag: java.io.ByteArrayOutputStream? = null
            while (!closed) {
                val b0 = inp.read(); if (b0 < 0) return null
                val b1 = inp.read(); if (b1 < 0) return null
                val fin = b0 and 0x80 != 0
                val op = b0 and 0x0f
                var len = (b1 and 0x7f).toLong()
                if (len == 126L) len = ((inp.read() shl 8) or inp.read()).toLong()
                else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or inp.read().toLong() } }
                if (len > 16 * 1024 * 1024) throw IOException("кадр слишком большой")
                val mask = if (b1 and 0x80 != 0) inp.readFully(4) else null
                val payload = inp.readFully(len.toInt())
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                when (op) {
                    8 -> { close(); return null }
                    9 -> synchronized(this) { out.write(frame(10, payload)); out.flush() }
                    10 -> {}
                    else -> {
                        if (fin && frag == null) return payload
                        if (frag == null) frag = java.io.ByteArrayOutputStream()
                        frag.write(payload)
                        if (fin) return frag.toByteArray()
                    }
                }
            }
            return null
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { synchronized(this) { out.write(frame(8, ByteArray(0))); out.flush() } }
            runCatching { sock.close() }
        }

        private fun frame(op: Int, data: ByteArray): ByteArray {
            val mask = ByteArray(4).also { rnd.nextBytes(it) }
            val n = data.size
            val hdr = java.io.ByteArrayOutputStream(14 + n)
            hdr.write(0x80 or op)
            when {
                n < 126 -> hdr.write(0x80 or n)
                n < 65536 -> { hdr.write(0x80 or 126); hdr.write(n shr 8); hdr.write(n and 255) }
                else -> { hdr.write(0x80 or 127); for (s in 56 downTo 0 step 8) hdr.write(((n.toLong() shr s) and 255).toInt()) }
            }
            hdr.write(mask)
            for (i in 0 until n) hdr.write(data[i].toInt() xor mask[i and 3].toInt())
            return hdr.toByteArray()
        }

        companion object {
            /** TLS до [ip]:443 с SNI/Host [domain], затем апгрейд `GET /apiws` (подпротокол `binary`). */
            fun connect(ip: String, domain: String, timeoutMs: Int): WebSocket {
                val raw = Socket()
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(ip, 443), timeoutMs)
                raw.soTimeout = timeoutMs
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, domain, 443, true) as SSLSocket
                try {
                    ssl.sslParameters = ssl.sslParameters.apply {
                        serverNames = listOf(SNIHostName(domain))
                        endpointIdentificationAlgorithm = "HTTPS"
                        // Конечная точка Telegram отвечает только по TLS 1.2 (на 1.3 — alert protocol_version).
                        protocols = arrayOf("TLSv1.2")
                    }
                    ssl.startHandshake()
                    val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
                    val out = ssl.outputStream
                    out.write(("GET /apiws HTTP/1.1\r\nHost: $domain\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: binary\r\n\r\n").toByteArray())
                    out.flush()
                    val inp = BufferedInputStream(ssl.inputStream, 65536)
                    val status = readLine(inp)
                    val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                    while (readLine(inp).isNotEmpty()) { /* заголовки ответа не нужны */ }
                    if (code == 101) { ssl.soTimeout = 0; return WebSocket(ssl, inp, out) }
                    if (code in setOf(301, 302, 303, 307, 308)) throw Redirect(code)
                    throw IOException("HTTP $code")
                } catch (e: Throwable) { runCatching { ssl.close() }; throw e }
            }

            private fun readLine(i: InputStream): String {
                val sb = StringBuilder()
                while (true) {
                    val c = i.read()
                    if (c < 0) throw EOFException()
                    if (c == '\n'.code) break
                    if (c != '\r'.code) sb.append(c.toChar())
                }
                return sb.toString()
            }
        }
    }

    companion object {
        private const val MAX_CONNECTIONS = 256

        /** Запасные адреса ЦОД для WebSocket (проверено: `.220` и `.99` отвечают 101, `.51` у части провайдеров глухо блокируется); `203` — служебный, ходит как ЦОД 2. */
        val DEFAULT_DC_IPS: Map<Int, String> = mapOf(
            1 to "149.154.175.205", 2 to "149.154.167.220", 3 to "149.154.175.100",
            4 to "149.154.167.220", 5 to "149.154.171.5", 203 to "149.154.167.220",
        )

        /** Подсети Telegram — их же использует готовое правило «Telegram → TG WS». */
        val TELEGRAM_CIDRS: List<String> = listOf(
            "185.76.151.0/24", "149.154.160.0/20", "91.105.192.0/23", "91.108.0.0/16", "95.161.64.0/20",
        )

        private val RANGES: List<Pair<Long, Long>> = TELEGRAM_CIDRS.map { c ->
            val (a, bits) = c.split('/')
            val base = ipToLong(a)
            val size = 1L shl (32 - bits.toInt())
            base to (base + size - 1)
        }

        private val VALID_PROTOS = setOf(0xEFEFEFEF.toInt(), 0xEEEEEEEE.toInt(), 0xDDDDDDDD.toInt())

        private val IP_TO_DC: Map<String, Pair<Int, Boolean>> = buildMap {
            fun add(dc: Int, media: Boolean, vararg ips: String) = ips.forEach { put(it, dc to media) }
            add(1, false, "149.154.175.50", "149.154.175.51", "149.154.175.53", "149.154.175.54"); add(1, true, "149.154.175.52")
            add(2, false, "149.154.167.41", "149.154.167.50", "149.154.167.51", "149.154.167.220", "95.161.76.100")
            add(2, true, "149.154.167.151", "149.154.167.222", "149.154.167.223", "149.154.162.123")
            add(3, false, "149.154.175.100", "149.154.175.101"); add(3, true, "149.154.175.102")
            add(4, false, "149.154.167.91", "149.154.167.92")
            add(4, true, "149.154.164.250", "149.154.166.120", "149.154.166.121", "149.154.167.118", "149.154.165.111")
            add(5, false, "91.108.56.100", "91.108.56.101", "91.108.56.116", "91.108.56.126", "149.154.171.5")
            add(5, true, "91.108.56.102", "91.108.56.128", "91.108.56.151")
            add(203, false, "91.105.192.100")
        }

        fun wsDomains(dc: Int, media: Boolean): List<String> {
            val d = if (dc == 203) 2 else dc
            return if (media) listOf("kws$d-1.web.telegram.org", "kws$d.web.telegram.org")
            else listOf("kws$d.web.telegram.org", "kws$d-1.web.telegram.org")
        }


        /**
         * Адреса ЦОД Telegram по IPv6: `2001:b28:f23d:f00N::a` (N - номер ЦОД; для 2 и 4 - `2001:67c:4e8:f00N::a`, для 5 - `2001:b28:f23f:f005::a`);
         * `::b` - media. null - не Telegram.
         */
        fun v6Dc(a: ByteArray): Pair<Int, Boolean>? {
            if (a.size != 16) return null
            fun g(i: Int) = ((a[2 * i].toInt() and 255) shl 8) or (a[2 * i + 1].toInt() and 255)
            val known = g(0) == 0x2001 && ((g(1) == 0x0B28 && (g(2) == 0xF23D || g(2) == 0xF23F)) || (g(1) == 0x067C && g(2) == 0x04E8))
            if (!known) return null
            val dc = g(3) - 0xF000
            if (dc !in 1..5) return null
            for (i in 8 until 15) if (a[i].toInt() != 0) return null
            return dc to ((a[15].toInt() and 255) == 0x0B)
        }

        /** Секрет MTProto-прокси: постоянный (слушатель только на 127.0.0.1; секрет нужен Telegram для ключа обфускации, а не для защиты). */
        private val MT_SECRET: ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest("HydraVPN TG WS".toByteArray()).copyOf(16)
        val MT_SECRET_HEX: String get() = MT_SECRET.joinToString("") { "%02x".format(it) }

        /** Ссылка для Telegram: `tg://proxy` с секретом `dd` (padded intermediate). */
        fun mtProxyLink(port: Int): String = "tg://proxy?server=127.0.0.1&port=$port&secret=dd$MT_SECRET_HEX"

        /** Ссылка для Telegram: SOCKS5 (ByeDPI и прочее). */
        fun socksLink(port: Int): String = "tg://socks?server=127.0.0.1&port=$port"

        private const val TAG_ABRIDGED = 0xEFEFEFEF.toInt()
        private const val TAG_INTERMEDIATE = 0xEEEEEEEE.toInt()
        private const val TAG_PADDED = 0xDDDDDDDD.toInt()

        fun isTelegramIp(ip: String): Boolean {
            if (ip.count { it == '.' } != 3) return false
            val n = runCatching { ipToLong(ip) }.getOrNull() ?: return false
            return RANGES.any { n in it.first..it.second }
        }

        private fun ipToLong(ip: String): Long = ip.split('.').fold(0L) { acc, p -> (acc shl 8) or p.toInt().toLong() }
    }
}

private fun InputStream.readFully(n: Int): ByteArray {
    val b = ByteArray(n)
    var o = 0
    while (o < n) { val r = read(b, o, n - o); if (r < 0) throw EOFException(); o += r }
    return b
}

private fun InputStream.skipFully(n: Int) { readFully(n) }

private fun ByteArray.startsWithAscii(s: String) = size >= s.length && s.indices.all { this[it].toInt() == s[it].code }

/** Готовое правило «Telegram → TG WS»: подсети серверов Telegram на выход [RouteTarget.TGWS]. */
object TgWsPreset {
    fun rules(): List<RouteRule> = TgWsProxy.TELEGRAM_CIDRS.map { RouteRule(RouteKind.CIDR, it, RouteTarget.TGWS) }
    fun isApplied(rules: List<RouteRule>) = rules().all { it in rules }
}
