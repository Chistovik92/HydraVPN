package ru.gidravpn.hydra.desktop

import org.junit.Assume.assumeTrue
import ru.gidravpn.hydra.servers.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Настоящее развёртывание по SSH на Linux-машину (root, systemd, /dev/net/tun) и рукопожатие клиентом на ней же.
 * HYDRA_SSH_TEST=host:port:root:password, HYDRA_AWG_BUNDLE=каталог с amneziawg-go, awg, awg-quick (dist/awg-server/<arch>).
 * Клиент поднимается на петле с AllowedIPs только на адрес сервера в туннеле, чтобы не трогать маршруты хоста.
 */
class AwgServerLiveTest {
    @Test fun allGenerationsHandshake() {
        val v = System.getenv("HYDRA_SSH_TEST")
        val dir = System.getenv("HYDRA_AWG_BUNDLE")
        assumeTrue(v != null && dir != null)
        val p = v!!.split(":", limit = 4)
        val t = SshTarget(p[0], p[1].toInt(), p[2], password = p[3])
        val bundle = AwgBundle("amd64", listOf("amneziawg-go", "awg", "awg-quick").associateWith {
            val d = File(dir, it).readBytes(); BundleFile(it, d, Installer.sha256(d))
        })
        SshConnector.connect(t, SshConnector.fingerprint(t)).use { ssh ->
            val pre = PreflightCheck.parse(ssh.exec(PreflightCheck.command(51820)).out)
            println("preflight: $pre")
            // порт мог быть занят нашим же прошлым запуском — это не помеха
            assertTrue(pre.root && pre.systemd, pre.toString())
            for (gen in AwgGeneration.values()) {
                val spec = AwgServerSpec.create(t.host, 51820, gen)
                val client = AwgServerSpec.newClient("e2e", "10.8.1.2/32")
                val log = mutableListOf<String>()
                val ok = Installer(ssh, listOf(spec.privateKey, client.privateKey, client.presharedKey)) { e ->
                    when (e) {
                        is InstallEvent.StepFailed -> log += "FAIL ${e.step.id}: ${e.reason}"
                        is InstallEvent.Output -> log += e.line
                        else -> {}
                    }
                }.run(AwgInstallPlan.steps(spec, listOf(client), bundle, openFirewall = false, firewall = pre.firewall))
                if (!ok) fail("$gen: " + log.takeLast(15).joinToString("\n"))

                val conf = spec.clientConf(client)
                    .replace("AllowedIPs = 0.0.0.0/0, ::/0", "AllowedIPs = 10.8.1.1/32")
                    .replace("Endpoint = ${t.host}:51820", "Endpoint = 127.0.0.1:51820")
                    .replace(Regex("DNS = .*\n"), "")
                ssh.upload(conf.toByteArray(), "/etc/amnezia/amneziawg/awgc.conf", 384)
                val env = "WG_QUICK_USERSPACE_IMPLEMENTATION=amneziawg-go PATH=/usr/local/bin:\$PATH"
                val up = ssh.exec("$env awg-quick up awgc 2>&1")
                try {
                    assertTrue(up.ok, "$gen client up: ${up.out}")
                    val ping = ssh.exec("ping -c 3 -W 2 10.8.1.1 2>&1")
                    val hs = ssh.exec("$env awg show awg0 latest-handshakes").out.trim()
                    println("$gen: ping exit=${ping.exit}, handshakes=[$hs]")
                    assertTrue(ping.ok, "$gen ping: ${ping.out}\n${ssh.exec("journalctl -u hydra-awg -n 20 --no-pager").out}")
                    assertTrue(hs.lines().any { (it.substringAfter('\t').trim().toLongOrNull() ?: 0) > 0 }, "$gen нет handshake: $hs")
                } finally {
                    ssh.exec("$env awg-quick down awgc 2>&1")
                    ssh.exec("rm -f /etc/amnezia/amneziawg/awgc.conf")
                }
            }
            Installer(ssh) { }.run(AwgInstallPlan.uninstallSteps(AwgServerSpec.create(t.host, 51820, AwgGeneration.V1_0)))
        }
    }
}
