package ru.gidravpn.hydra.desktop

import ru.gidravpn.hydra.servers.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallerTest {
    private class FakeSsh(val failOn: String? = null, val corrupt: Boolean = false) : SshSession {
        val cmds = mutableListOf<String>()
        val files = mutableMapOf<String, ByteArray>()
        override fun exec(command: String, onLine: (String) -> Unit): ExecResult {
            cmds += command
            Regex("^mv -f '(.*)' '(.*)'$").find(command)?.let { m ->
                files[m.groupValues[2]] = files.remove(m.groupValues[1])!!
                return ExecResult(0, "")
            }
            if (failOn != null && command.contains(failOn)) { onLine("boom"); return ExecResult(1, "boom") }
            if (command.startsWith("sha256sum ")) {
                val p = command.removePrefix("sha256sum ").trim('\'')
                val d = files[p] ?: return ExecResult(1, "")
                val h = Installer.sha256(if (corrupt) d + 1 else d)
                return ExecResult(0, "$h  $p")
            }
            if (command.contains("awg show")) return ExecResult(0, "interface: awg0\n  listening port: 51820")
            return ExecResult(0, "")
        }
        override fun upload(data: ByteArray, remotePath: String, mode: Int) { files[remotePath] = data }
        override fun close() {}
    }

    private fun bundle() = AwgBundle("amd64", listOf("amneziawg-go", "awg", "awg-quick").associateWith {
        val d = it.toByteArray(); BundleFile(it, d, Installer.sha256(d))
    })

    private val spec = AwgServerSpec.create("203.0.113.5", 51820, AwgGeneration.V3)
    private val client = AwgServerSpec.newClient("c1", "10.8.1.2/32")

    @Test fun preflightParsesAndFlagsProblems() {
        val ok = PreflightCheck.parse("ARCH=aarch64\nOS=Debian GNU/Linux 12\nUID=0\nSYSTEMD=1\nPORTBUSY=0\nNET=1\nFW=ufw")
        assertTrue(ok.ok); assertEquals("arm64", ok.arch); assertEquals("ufw", ok.firewall)
        val bad = PreflightCheck.parse("ARCH=mips\nUID=1000\nSYSTEMD=0\nPORTBUSY=2\nNET=0\nFW=none")
        assertEquals(5, bad.issues.size)
    }

    @Test fun fullPlanRunsAndVerifies() {
        val ssh = FakeSsh()
        val events = mutableListOf<InstallEvent>()
        val plan = AwgInstallPlan.steps(spec, listOf(client), bundle(), openFirewall = true, firewall = "ufw")
        assertTrue(Installer(ssh, listOf(spec.privateKey)) { events += it }.run(plan))
        assertTrue(events.last() is InstallEvent.Done)
        val conf = String(ssh.files["/etc/amnezia/amneziawg/awg0.conf"]!!)
        assertTrue("PostUp = iptables" in conf && "PublicKey = ${client.publicKey}" in conf)
        assertTrue(ssh.cmds.any { it.startsWith("ufw allow 51820/udp && ") && ServerOps.FW_LIST in it })
        assertTrue(ssh.files.containsKey(ServerOps.AWG_STATE))
        assertTrue(ssh.files.containsKey("/etc/systemd/system/hydra-awg.service"))
    }

    @Test fun firewallUntouchedWithoutConsent() {
        val plan = AwgInstallPlan.steps(spec, emptyList(), bundle(), openFirewall = false, firewall = "ufw")
        assertFalse(plan.any { it.id == "fw" })
    }

    @Test fun stopsOnFailureAndRedactsSecrets() {
        val ssh = FakeSsh(failOn = "systemctl daemon-reload")
        val events = mutableListOf<InstallEvent>()
        val ok = Installer(ssh, listOf("boom-secret")) { events += it }
            .run(AwgInstallPlan.steps(spec, emptyList(), bundle(), false, "none"))
        assertFalse(ok)
        val failed = events.filterIsInstance<InstallEvent.StepFailed>().single()
        assertEquals("start", failed.step.id)
        assertFalse(events.any { it is InstallEvent.StepStart && it.step.id == "verify" })
    }

    @Test fun checksumMismatchIsCaught() {
        val events = mutableListOf<InstallEvent>()
        val ok = Installer(FakeSsh(corrupt = true)) { events += it }
            .run(AwgInstallPlan.steps(spec, emptyList(), bundle(), false, "none"))
        assertFalse(ok)
        // После ошибки контрольной суммы происходит откат, поэтому ищем StepFailed среди событий
        assertTrue(events.filterIsInstance<InstallEvent.StepFailed>().any { it.reason.contains("Контрольная сумма") })
    }

    @Test fun redactorHidesKeys() {
        val r = Redactor(listOf("hunter2hunter2"))
        assertEquals("pw •••", r.clean("pw hunter2hunter2"))
        assertEquals("PrivateKey = •••", r.clean("PrivateKey = AAAA="))
    }

    @Test fun cancelStops() {
        val inst = Installer(FakeSsh()) { }
        inst.cancelled = true
        assertFalse(inst.run(AwgInstallPlan.steps(spec, emptyList(), bundle(), false, "none")))
    }

    @Test fun rollbackOnFailure() {
        val ssh = FakeSsh(failOn = "systemctl daemon-reload")
        val events = mutableListOf<InstallEvent>()
        val ok = Installer(ssh) { events += it }
            .run(AwgInstallPlan.steps(spec, emptyList(), bundle(), false, "none"))
        assertFalse(ok)
        // После ошибки на шаге "start" (systemctl daemon-reload) должны быть выполнены откаты предыдущих шагов
        assertTrue(events.any { it is InstallEvent.Output && it.line.contains("Откат выполненных шагов") })
        // Откат службы: disable --now
        assertTrue(ssh.cmds.any { it.contains("systemctl disable --now hydra-awg") })
    }

    @Test fun rollbackOnCancel() {
        val ssh = FakeSsh()
        val events = mutableListOf<InstallEvent>()
        val inst = Installer(ssh) { events += it }
        // Отменяем после первого шага
        inst.cancelled = true
        val plan = AwgInstallPlan.steps(spec, emptyList(), bundle(), false, "none")
        val ok = inst.run(plan)
        assertFalse(ok)
        assertTrue(events.filterIsInstance<InstallEvent.StepFailed>().any { it.reason.contains("Остановлено") })
    }
}
