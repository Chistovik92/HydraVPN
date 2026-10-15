package ru.gidravpn.hydra.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import ru.gidravpn.hydra.data.model.EngineToggles
import ru.gidravpn.hydra.data.subscription.LinkParser
import ru.gidravpn.hydra.servers.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 0.8.2-a2: подключения в x-ui (alireza0) — установка мастером, затем каждый тип «как кнопкой» ([MyServersManager.createPanelConnection])
 * и трафик по выданной ссылке ядром, которое выберет Hydra. Hysteria 2 — с сертификатом, выписанным на стенде (самоподписанный,
 * проверка у клиента выключается). HYDRA_SSH_TEST, HYDRA_XUIA_LIVE=1, HYDRA_XRAY, HYDRA_SINGBOX.
 */
class XuiAlirezaConnectionsLiveTest {
    @Test fun everyXuiTypeCarriesTraffic() {
        val v = System.getenv("HYDRA_SSH_TEST")
        assumeTrue(v != null && System.getenv("HYDRA_XUIA_LIVE") != null)
        val p = v!!.split(":", limit = 4)
        val login = SshTarget(p[0], p[1].toInt(), p[2], password = p[3])
        val xray = System.getenv("HYDRA_XRAY"); val sb = System.getenv("HYDRA_SINGBOX")
        var saved = emptyList<MyServer>()
        val m = MyServersManager(CoroutineScope(SupervisorJob() + Dispatchers.IO), { saved }, { saved = it }, { error("") }, MemoryVault()) { _, _ -> }
        fun waitFor(what: String, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + 420_000
            Thread.sleep(200)
            while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(300) }
            fail("таймаут: $what; ${m.ui.value.error}; ${m.ui.value.log.takeLast(12)}")
        }
        SshConnector.connect(login, SshConnector.fingerprint(login)).use { ssh ->
            Installer(ssh) {}.run(ServerOps.wipeSteps())
            ssh.exec("systemctl disable --now x-ui >/dev/null 2>&1; rm -rf /usr/local/x-ui /etc/x-ui /etc/systemd/system/x-ui.service /usr/bin/x-ui; systemctl daemon-reload")
        }
        m.begin(ServerForm("xui", login)); waitFor("отпечаток") { m.ui.value.stage == Stage.CONFIRM_FINGERPRINT }
        m.confirmFingerprint(); waitFor("добавление") { m.ui.value.stage == Stage.DONE }
        val id = saved.single().id
        m.prepareInstall(id, ServiceForm(ServerKind.PANEL_XUI, sslIp = false))
        waitFor("план") { !m.ui.value.busy && m.ui.value.install?.stage == InstallStage.READY }
        m.runInstall()
        waitFor("установка") { !m.ui.value.busy && m.ui.value.install?.stage in setOf(InstallStage.DONE, InstallStage.FAILED) }
        assertEquals(InstallStage.DONE, m.ui.value.install?.stage, "${m.ui.value.error}")
        m.closeInstall()
        // сертификат стенда — чтобы проверить и Hysteria 2 (x-ui берёт его из своих настроек webCertFile)
        SshConnector.connect(login, saved.single().fingerprint).use { ssh ->
            val d = XuiPanel.CERT_DIR
            ssh.exec("mkdir -p $d && openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 2 -subj /CN=${login.host} " +
                "-addext subjectAltName=IP:${login.host} -keyout $d/privkey.pem -out $d/fullchain.pem >/dev/null 2>&1")
            val u = java.net.URL(saved.single().panel!!.baseUrl)
            assertTrue(Installer(ssh) {}.run(XuiPanel.certApplySteps(PanelKind.XUI, u.port, u.path)))
        }
        saved = saved.map { s -> val pn = s.panel!!; s.copy(panel = pn.copy(baseUrl = XuiPanel.toHttps(pn.baseUrl))) }
        val results = mutableListOf<Pair<String, String>>()
        var port = 26000
        for (type in OtherPanelConnections.XUI) {
            m.hideShown()
            m.createPanelConnection(id, type, port++)
            waitFor("подключение $type") { !m.ui.value.busy && (m.ui.value.shown != null || m.ui.value.error != null) }
            val r = runCatching {
                val link = m.ui.value.shown?.second ?: error("${m.ui.value.error}")
                println("XLINK $type: " + link.lines().first().replace(Regex("(pbk|password|PrivateKey)( = |=)[^&\\s]+"), "$1$2…").take(220))
                var prof = LinkParser.parseLine(link.trim()) ?: error("Hydra не разобрала ссылку")
                if (type.needsCert) prof = prof.copy(extra = JSONObject(prof.extra).put("insecure", true).toString())
                fun once() = if (EngineToggles().engineFor(prof, true) == EngineToggles.Kind.XRAY && xray != null) "xray: " + XuiTraffic.throughXray(xray, prof)
                    else if (sb != null) "sing-box: " + XuiTraffic.through(sb, prof) else "ядро не задано"
                once().let { x -> if (": ok" in x) x else { Thread.sleep(4000); once() } }
            }.getOrElse { "ОШИБКА ${it.message?.take(300)}" }
            println("XRESULT $type: $r")
            results += type.name to r
        }
        m.wipe(id); waitFor("очистка") { !m.ui.value.busy }
        val bad = results.filter { ": ok" !in it.second }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }
}
