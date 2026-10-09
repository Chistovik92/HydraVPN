package ru.gidravpn.hydra.desktop

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.subscription.LinkBuilder
import ru.gidravpn.hydra.data.subscription.LinkParser
import ru.gidravpn.hydra.data.subscription.OpenFluxShare
import ru.gidravpn.hydra.data.subscription.OlcRtcConfigBuilder
import ru.gidravpn.hydra.data.subscription.OpenFluxArgs
import ru.gidravpn.hydra.desktop.core.hasValidEndpoint
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * olcRTC и OpenFlux на ПК (0.7.4): разбор ссылок, аргументы клиента и — если бинарь OpenFlux собран в `build/hydra-app-resources`
 * (`gradle :desktop:downloadOpenFlux`) — СКВОЗНАЯ проверка с настоящим exit-узлом OpenFlux (транспорт `direct`, режим l4):
 * клиент → локальный SOCKS5 → туннель → exit → локальный HTTP-сервер.
 */
class OpenFluxLiveTest {
    private fun freePort() = ServerSocket(0).use { it.localPort }

    @Test fun linksAndArguments() {
        val p = LinkParser.parseLine("openflux://direct?url=10.0.0.5%3A9000&key=s3cret#Мой узел")!!
        assertEquals(Protocol.OPENFLUX, p.protocol); assertEquals("direct", p.transport); assertEquals("10.0.0.5:9000", p.address)
        assertTrue(p.hasValidEndpoint)
        val args = OpenFluxArgs.build(p, 10810, "/tmp/k")
        assertEquals(listOf("--role", "client", "--inbound", "socks5", "--socks5", "127.0.0.1:10810", "--transports", "direct:100",
            "--direct-dial", "10.0.0.5:9000", "--encryption-key-file", "/tmp/k"), args)

        val ya = LinkParser.parseLine("openflux://yandex?url=https%3A%2F%2Fdisk.example%2Fdoc")!!
        assertTrue(OpenFluxArgs.build(ya, 1, null).containsAll(listOf("--url", "https://disk.example/doc")))
        val max = LinkParser.parseLine("openflux://oneme?maxToken=T&maxUid=7")!!
        assertTrue(OpenFluxArgs.build(max, 1, null).containsAll(listOf("--maxToken", "T", "--maxUid", "7")))

        val olc = LinkParser.parseLine("olcrtc://telemost?datachannel@room42#key123")!!
        val yaml = OlcRtcConfigBuilder.build(olc, 10809)
        assertTrue("mode: cnc" in yaml && "\"room42\"" in yaml && "port: 10809" in yaml)
        // 0.7.10: комната со слешем — без «\/» (YAML такой последовательности не знает), имя после $ раскодировано.
        val jitsi = LinkParser.parseLine("olcrtc://jitsi?datachannel@meet.jit.si/Room1#${"ab".repeat(32)}\$Мой%20узел")!!
        assertEquals("Мой узел", jitsi.name)
        val jy = OlcRtcConfigBuilder.build(jitsi, 10809)
        assertTrue(jy, "id: \"meet.jit.si/Room1\"" in jy && "\\/" !in jy)
    }


    private fun bundledExe() = listOf("openflux.exe", "openflux").flatMap { n -> File("build/hydra-app-resources").listFiles()?.map { File(it, n) }.orEmpty() }
        .firstOrNull { it.isFile && it.canExecute() }

    private fun core(exe: File, flag: String, arg: String): org.json.JSONObject {
        val p = ProcessBuilder(exe.absolutePath, flag, "-").redirectErrorStream(true).start()
        p.outputStream.use { it.write(arg.toByteArray()) }
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return org.json.JSONObject(out.trim())
    }

    /** Ссылки читает и делает ядро: наш разбор и сборка обязаны с ним совпадать (замороженные ссылки из share/compat_test.go). */
    @Test fun officialLinksAgreeWithCore() {
        val exe = bundledExe()
        assumeTrue("нет бинаря OpenFlux", exe != null)
        val frozen = listOf(
            "openflux://v1/TMpBCsIwEAXQu_y1aTCJm4B4EHEhNehAnAmTaUVK7y6ii771W2B65d5ErSOfF9i7FWSMU-vClbhgh0krMh5mrWfvia3oTOU1fNPwW77SXNwoN-K7P6nI87j_cyGE4GKM0aWUkjtsYL2snwAAAP__",
            "openflux://v1/qlbKS8xNVbJS8k3MzNErKlVIK8pMzUtR0lEqKUrMKy7ILyopVrKKrlYqqSwAKctNzMwpKlXSUSotylGyUnJMck5xTXPPMNT3zPLO8c3zLzBSqo2tBQQAAP__",
            "openflux://v1/rI_BaiwhEEV_pSnonWPb7TweCCFkM-QfQha2FhlJj0pZTkaG_vdgAoHssyw4dc-9d4j2gmDgOV0QBER8SxwsIximigIKOkIGAy4RoePhnKjgsFpmpDYUtnnrjy5FxlsHz8y5mGnyyRXZbPR4k1S_zuka8OOx0vbQ7MGH8n7Idd2CG_XTuJzG5WRXBwKYbCw5ERcwL3fglnvD63cWCKi0_YknU0gUuIGZldrFj8mHvvQX8E8J8MF276K0VHKetfxvjketYX_dPwMAAP__",
            "openflux://v1/JMk7DsIwDAbgu_xz1PB--CqIIdgGhqqJbFdqVPXuDMzfCq6iDMKon8IdCa5sGiDs9ofj6Xy53u7lxaJvJISVyVu1cNBjRfSmIPQyiS5ImG0E4RvRnHKWyj78bbA5L9ie2y8AAP__",
            "openflux://v1/PY0xCwIxDEb_S2a94loQNydHN3Eod1EDvaSkaUWO---2HJox7328BTjMCB4exYoi7MA0cE6ilsHfFrBP6ngsKQtH4q4Uje31MkvZO0dsqJXwPXRp2CwXqeJ-lIn46U4qMh9DuzbeQtdf5UwYJ_CH9f5Hki5YsSUWCJ2sXw"
        )
        for (l in frozen) {
            val r = core(exe!!, "--parse-link", l)
            val cfg = r.getJSONObject("config")
            val mine = OpenFluxShare.decode(l)
            assertEquals(l, cfg.optString("name"), mine.name)
            assertEquals(l, cfg.optString("secret"), mine.secret)
            assertEquals(l, cfg.optString("codec"), mine.codec)
            assertEquals(l, cfg.optBoolean("negotiate"), mine.negotiate)
            val ts = cfg.getJSONArray("transports")
            assertEquals(l, ts.length(), mine.transports.size)
            for (i in 0 until ts.length()) {
                val t = ts.getJSONObject(i)
                assertEquals(t.getString("type"), mine.transports[i].type)
                assertEquals(t.optString("url"), mine.transports[i].url)
                assertEquals(t.optString("dial"), mine.transports[i].dial)
                assertEquals(t.optInt("priority"), mine.transports[i].priority)
            }
            // Наша ссылка — ядро читает так же, как исходную.
            val back = core(exe, "--parse-link", OpenFluxShare.encode(mine))
            assertEquals(l, cfg.toString().length, back.getJSONObject("config").toString().length)
            assertEquals(r.optString("context"), back.optString("context"))
        }
        // Ошибки — теми же кодами.
        assertEquals("damaged", core(exe!!, "--parse-link", frozen[0].dropLast(30)).getString("code"))
        assertEquals("not_link", core(exe, "--parse-link", "https://example.com").getString("code"))
        assertEquals("case_changed", core(exe, "--parse-link", "OPENFLUX://V1/abc").getString("code"))
    }

    @Test fun exportedProfileLinkIsReadByCore() {
        val exe = bundledExe()
        assumeTrue("нет бинаря OpenFlux", exe != null)
        val p = LinkParser.parseLine("openflux://direct?url=10.0.0.5%3A9000&key=0123456789abcdef#Мой узел")!!
        val link = LinkBuilder.toLink(p)!!
        assertTrue(link, link.startsWith("openflux://v1/"))
        val cfg = core(exe!!, "--parse-link", link).getJSONObject("config")
        assertEquals("Мой узел", cfg.getString("name"))
        assertEquals("10.0.0.5:9000", cfg.getJSONArray("transports").getJSONObject(0).getString("dial"))
    }

    @Test fun realExitNodeCarriesTraffic() {
        val exe = listOf("openflux.exe", "openflux").flatMap { n -> File("build/hydra-app-resources").listFiles()?.map { File(it, n) }.orEmpty() }
            .firstOrNull { it.isFile && it.canExecute() }
        assumeTrue("нет бинаря OpenFlux", exe != null)
        exe!!
        // Узел выхода не ходит на loopback — цель берём на адресе одного из сетевых адаптеров этой машины.
        val lan = java.net.NetworkInterface.getNetworkInterfaces().asSequence().flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
        assumeTrue("нет сетевого адреса, отличного от loopback", lan != null)
        val target = lan!!.address
        val web = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0)
        web.createContext("/") { ex -> val b = "hello-through-openflux".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        web.start()
        val tunnel = freePort(); val socks = freePort()
        val keyFile = File.createTempFile("ofkey", ".txt").apply { writeText("test-key-0123456789abcdef"); deleteOnExit() }
        val exit = ProcessBuilder(exe.absolutePath, "--role", "exit", "--transports", "direct:100", "--direct-listen", "127.0.0.1:$tunnel", "--mode", "l4", "--encryption-key-file", keyFile.absolutePath)
            .redirectErrorStream(true).start()
        // Клиент — только после того, как exit-узел слушает: иначе на медленном раннере (linux-arm64, релиз 0.7.10)
        // клиент уходил в классический режим, первый запрос висел, и тест падал по таймауту чтения.
        val tEnd = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < tEnd && runCatching { Socket("127.0.0.1", tunnel).close() }.isFailure) Thread.sleep(200)
        val profile = ServerProfile(name = "t", protocolId = Protocol.OPENFLUX.id, address = "127.0.0.1:$tunnel", port = 0, transport = "direct", uuidOrPassword = "test-key-0123456789abcdef")
        val client = ProcessBuilder(listOf(exe.absolutePath) + OpenFluxArgs.build(profile, socks, keyFile.absolutePath)).redirectErrorStream(true).start()
        try {
            val end = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < end && runCatching { Socket("127.0.0.1", socks).close() }.isFailure) Thread.sleep(200)
            val port = web.address.port
            fun fetch(): String = Socket("127.0.0.1", socks).use { s ->
                s.soTimeout = 15_000
                val out = s.getOutputStream(); val inp = s.getInputStream()
                out.write(byteArrayOf(5, 1, 0)); out.flush()
                assertEquals(listOf<Byte>(5, 0), List(2) { inp.read().toByte() })
                out.write(byteArrayOf(5, 1, 0, 1, target[0], target[1], target[2], target[3], (port shr 8).toByte(), port.toByte())); out.flush()
                val reply = ByteArray(10); var n = 0
                while (n < 10) { val r = inp.read(reply, n, 10 - n); if (r < 0) break; n += r }
                assertEquals("SOCKS5 CONNECT отклонён: ${reply.joinToString()}", 0, reply[1].toInt())
                out.write("GET / HTTP/1.0\r\nHost: x\r\n\r\n".toByteArray()); out.flush()
                inp.readBytes().toString(Charsets.UTF_8)
            }
            // Сессия с узлом согласуется не мгновенно — до трёх попыток, каждая на свежем соединении.
            var last: Throwable? = null
            var body = ""
            for (attempt in 1..3) {
                val r = runCatching { fetch() }
                if (r.isSuccess && "hello-through-openflux" in r.getOrThrow()) { body = r.getOrThrow(); break }
                last = r.exceptionOrNull(); body = r.getOrNull().orEmpty()
                Thread.sleep(2_000)
            }
            assertTrue("ответ не дошёл через туннель: $body ${last ?: ""}", "hello-through-openflux" in body)
        } finally {
            client.destroyForcibly(); exit.destroyForcibly(); web.stop(0)
        }
    }
}
