package ru.gidravpn.hydra.desktop

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import ru.gidravpn.hydra.data.model.EngineToggles
import ru.gidravpn.hydra.data.subscription.LinkParser
import ru.gidravpn.hydra.servers.*
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 0.8.2-a2: все типы подключений 3x-ui ([XuiConnection]) — вживую на стенде с уже стоящей панелью (её оставляет
 * `XuiLiveTest` с HYDRA_XUI_KEEP=1). Для каждого типа: инбаунд → пользователь hydra-1 подключён → ссылка от панели →
 * разбор Hydra → трафик тем ядром, что выберет приложение (HYDRA_XRAY / HYDRA_SINGBOX). Сертификат для Hysteria 2 / TUIC на
 * стенде самоподписанный (у стенда нет внешнего IP) — проверка сертификата у клиента здесь выключается; на VPS — Let's Encrypt.
 * HYDRA_SSH_TEST, HYDRA_XUI_CONN_LIVE=1; HYDRA_XUI_TYPES — через запятую (по умолчанию все).
 */
class XuiConnectionsLiveTest {
    /** Тело ссылок vmess:// и vpn:// — base64 (JSON или .conf): порт лежит внутри. */
    private fun unwrap(l: String): String = if (l.startsWith("vmess://") || l.startsWith("vpn://"))
        runCatching { String(java.util.Base64.getDecoder().decode(l.substringAfter("://").substringBefore("#").replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) })) }.getOrDefault(l) else l

    /**
     * 0.8.2-b: без SSH — API панели напрямую (DirectXuiTransport), если пароля стенда нет: HYDRA_XUI_RESULT_FILE — копия
     * /etc/x-ui/install-result.env, HYDRA_XUI_HOST — адрес панели. Сертификат для Hysteria 2 / TUIC здесь не сделать (он ставится
     * по SSH) — эти типы пропускаются; WireGuard и AmneziaWG его не требуют.
     */
    @Test fun everyConnectionTypeCarriesTraffic() {
        assumeTrue(System.getenv("HYDRA_XUI_CONN_LIVE") != null)
        // 0.8.2-rc2: без пароля стенда — HYDRA_WSL_STAND=<distro> (WslSession): 3x-ui уже стоит на основном стенде WSL
        val v = System.getenv("HYDRA_SSH_TEST") ?: if (WslSession.install()) "${System.getenv("HYDRA_WSL_HOST") ?: "172.29.99.246"}:22:root:-" else null
        val direct = System.getenv("HYDRA_XUI_RESULT_FILE")?.let { java.io.File(it) }
        assumeTrue(v != null || (direct != null && System.getenv("HYDRA_XUI_HOST") != null))
        if (v == null) {
            val host = System.getenv("HYDRA_XUI_HOST")!!
            val res = XuiPanel.parseResult(direct!!.readText())
            val path = res["XUI_WEB_BASE_PATH"].orEmpty().trim('/')
            run(XuiApi(DirectXuiTransport("http://$host:${res.getValue("XUI_PANEL_PORT")}/$path", res.getValue("XUI_API_TOKEN"))), host, null)
            return
        }
        val p = v.split(":", limit = 4)
        val login = SshTarget(p[0], p[1].toInt(), p[2], password = p[3])
        SshConnector.connect(login, SshConnector.fingerprint(login)).use { ssh ->
            val res = XuiPanel.parseResult(ssh.exec("cat ${XuiPanel.RESULT}").out)
            val port = res.getValue("XUI_PANEL_PORT"); val path = res["XUI_WEB_BASE_PATH"].orEmpty().trim('/')
            val api = XuiApi(SshXuiTransport(ssh, "http://127.0.0.1:$port/$path", res.getValue("XUI_API_TOKEN"), login.host))
            // самоподписанный сертификат стенда (SAN = IP) — только для проверки Hysteria 2 / TUIC
            val dir = "/root/cert/hydra-test"
            ssh.exec("mkdir -p $dir && openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 2 -subj /CN=${login.host} " +
                "-addext subjectAltName=IP:${login.host} -keyout $dir/privkey.pem -out $dir/fullchain.pem >/dev/null 2>&1")
            run(api, login.host, XuiConnection.Companion.Cert("$dir/fullchain.pem", "$dir/privkey.pem", login.host))
        }
    }

    private fun run(api: XuiApi, host: String, cert: XuiConnection.Companion.Cert?) {
        val xray = System.getenv("HYDRA_XRAY"); val sb = System.getenv("HYDRA_SINGBOX")
        val only = System.getenv("HYDRA_XUI_TYPES")?.split(",")?.map { it.trim() }?.toSet()
        val wslDistro = System.getenv("HYDRA_WSL_DISTRO")
        val results = mutableListOf<Pair<String, String>>()
        run {
            // старые тестовые инбаунды — прочь
            api.inbounds().let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("remark").startsWith("t-") }.forEach { api.delInbound(it.getInt("id")) } }
            var n = 25000
            for (type in XuiConnection.entries) {
                if (only != null && type.name !in only) continue
                if (type.needsCert && cert == null) { results += type.name to "пропущено (нужен сертификат, режим без SSH)"; continue }
                val portN = n++
                val res1 = runCatching {
                    val keys = if (type.reality) api.newRealityKeys() else ("" to "")
                    val id = api.addInbound(XuiConnection.inbound(type, portN, "t-${type.name.lowercase()}", priv = keys.first, pub = keys.second, cert = cert))
                    api.attachClient("hydra-1", listOf(id))
                    Thread.sleep(5000)   // панель перезапускает инбаунд Xray после подключения пользователя
                    val link = api.links("hydra-1").firstOrNull { l -> ":$portN" in l.substringBefore("#") || ":$portN" in unwrap(l) || "\"$portN\"" in unwrap(l) || "\"port\": $portN" in unwrap(l) }
                        ?: error("панель не выдала ссылку на порт $portN: ${api.links("hydra-1").map { it.take(40) }}")
                    println("LINK ${type.name}: " + link.replace(Regex("(pbk|password|auth|PrivateKey|privatekey)=[^&\\s]+"), "$1=…").take(260))
                    // 0.8.2-a4: полные ссылки — в файл для проверки на эмуляторе Android (AmneziaWG); в журнал не попадают
                    System.getenv("HYDRA_XUI_LINKS_OUT")?.let { java.io.File(it).appendText("${type.name} $link\n") }
                    var prof = LinkParser.parseLine(link) ?: LinkParser.parseSubscription(link).firstOrNull() ?: error("Hydra не разобрала ссылку")
                    if (type.needsCert) prof = prof.copy(extra = JSONObject(prof.extra).put("insecure", true).toString())
                    val engine = EngineToggles().engineFor(prof, true)
                    fun once() = when {
                        type == XuiConnection.AMNEZIAWG -> "ссылка разобрана (${prof.protocolId}); трафик AmneziaWG — на Android"
                        // UDP с Windows до WSL ловит TUN рабочей Hydra — UDP-типы проверяем sing-box на самом стенде (без SSH — через wsl).
                        type.udp && engine != EngineToggles.Kind.XRAY && wslDistro != null -> "sing-box: " + XuiTraffic.throughOnWsl(wslDistro, prof)
                        engine == EngineToggles.Kind.XRAY && xray != null -> "xray: " + XuiTraffic.throughXray(xray, prof)
                        sb != null -> "sing-box: " + XuiTraffic.through(sb, prof)
                        else -> "ядро не задано"
                    }
                    once().let { r -> if (r.contains(": ok") || r.startsWith("ссылка")) r else { Thread.sleep(4000); once() } }
                }.getOrElse { "ОШИБКА ${it.message?.take(300)}" }
                println("RESULT ${type.name}: $res1")
                results += type.name to res1
            }
        }
        val bad = results.filter { !it.second.startsWith("xray: ok") && !it.second.startsWith("sing-box: ok") && !it.second.startsWith("ссылка разобрана") && !it.second.startsWith("пропущено") }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }
}
