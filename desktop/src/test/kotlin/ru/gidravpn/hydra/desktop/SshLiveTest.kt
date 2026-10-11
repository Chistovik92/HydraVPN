package ru.gidravpn.hydra.desktop

import org.junit.Assume.assumeTrue
import ru.gidravpn.hydra.servers.HostKeyMismatch
import ru.gidravpn.hydra.servers.SshConnector
import ru.gidravpn.hydra.servers.SshTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Живой тест против настоящего sshd: HYDRA_SSH_TEST=host:port:user:password (например, WSL). Без переменной пропускается. */
class SshLiveTest {
    private fun target(): SshTarget {
        val v = System.getenv("HYDRA_SSH_TEST")
        assumeTrue(v != null)
        val p = v!!.split(":", limit = 4)
        return SshTarget(p[0], p[1].toInt(), p[2], password = p[3])
    }

    @Test fun pinExecUploadAndMismatch() {
        val t = target()
        val fp = SshConnector.fingerprint(t)
        assertTrue(fp.startsWith("SHA256:"))
        SshConnector.connect(t, fp).use { s ->
            val r = s.exec("echo hi; id -un")
            assertTrue(r.ok); assertTrue(r.out.contains("hi"))
            s.upload("данные".toByteArray(), "/tmp/hydra-ssh-test.txt", 384)
            assertEquals("данные", s.exec("cat /tmp/hydra-ssh-test.txt").out.trim())
            assertEquals(3, s.exec("exit 3").exit)
            s.exec("rm -f /tmp/hydra-ssh-test.txt")
        }
        assertFailsWith<HostKeyMismatch> { SshConnector.connect(t, "SHA256:AAAA") }
    }
}
