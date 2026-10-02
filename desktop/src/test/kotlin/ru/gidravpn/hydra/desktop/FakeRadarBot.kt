package ru.gidravpn.hydra.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/** Заглушка бота «Радар» по контракту docs/API_APPS.md (ping, link, me, subscriptions, session) и «панели» подписки. */
internal class FakeRadarBot {
    @Volatile var subUrl = ""
    @Volatile var enabled = true
    @Volatile var revoked = false
    @Volatile var lastDevice = ""
    val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base get() = "http://127.0.0.1:${server.address.port}"

    private fun HttpExchange.reply(code: Int, body: String) {
        val b = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(code, b.size.toLong()); responseBody.use { it.write(b) }
    }

    init {
        subUrl = "$base/sub/1"
        server.createContext("/api/v1/app/ping") { it.reply(200, """{"api":1,"service":"radar"}""") }
        server.createContext("/api/v1/app/link") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            lastDevice = Regex("\"device\":\"([^\"]*)\"").find(body)?.groupValues?.get(1).orEmpty()
            if ("12345678" in body) { revoked = false; ex.reply(201, """{"token":"tok-abc","device_id":"d1"}""") }
            else ex.reply(401, """{"error":"код не подошёл или устарел"}""")
        }
        server.createContext("/api/v1/app/me") { ex ->
            if (revoked) ex.reply(401, """{"error":"нет доступа"}""")
            else ex.reply(200, """{"user_id":"7","username":"vasya","blocked":false,"vpn":{"enabled":true,"state":"active","panels":2}}""")
        }
        server.createContext("/api/v1/app/subscriptions") { ex ->
            if (revoked || ex.requestHeaders.getFirst("Authorization") != "Bearer tok-abc") ex.reply(401, """{"error":"нет доступа"}""")
            else ex.reply(200, """{"subscriptions":[
                {"panel":"1","title":"Главный","kind":"3xui","link_kind":"subscription","state":"ok","enabled":$enabled,"expire":1900000000,"traffic_limit":10737418240,"traffic_used":524288000,"url":"$subUrl"},
                {"panel":"2","title":"Outline","kind":"outline","link_kind":"key","state":"ok","enabled":true,"expire":0,"traffic_limit":0,"traffic_used":0,"url":"ss://x"}]}""")
        }
        server.createContext("/api/v1/app/session") { ex -> revoked = true; ex.reply(200, """{"status":"ok"}""") }
        // «Панель»: отдаёт список ссылок открытым текстом, без служебных заголовков.
        server.createContext("/sub/") { ex ->
            ex.reply(200, "vless://11111111-2222-3333-4444-555555555555@node.example:443?security=tls&type=tcp#Node-" + ex.requestURI.path.substringAfterLast('/'))
        }
        server.start()
    }
}

