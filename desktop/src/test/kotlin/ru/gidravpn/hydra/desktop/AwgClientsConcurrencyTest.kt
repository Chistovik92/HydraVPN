package ru.gidravpn.hydra.desktop

import ru.gidravpn.hydra.servers.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AwgClientsConcurrencyTest {
    private class FakeSsh(private val spec: AwgServerSpec, private val clients: List<AwgClient>) : SshSession {
        var uploadedState: String? = null
        override fun exec(command: String, onLine: (String) -> Unit): ExecResult {
            if ("---AWGDUMP" in command) return ExecResult(0, "AWG_UNIT=1\nAWG_STATE=active\n---AWGSTATE\n${ServerOps.awgStateJson(spec, clients)}\n---END")
            return ExecResult(0, "")
        }
        override fun upload(data: ByteArray, remotePath: String, mode: Int) {
            if (remotePath == ServerOps.AWG_STATE || remotePath == ServerOps.AWG_STATE + ".hydra-new") uploadedState = String(data)
        }
        override fun close() {}
    }

    private val service = MyServerService { error("пакет не нужен") }

    @Test fun addUsesFreshClientsFromAnotherDevice() {
        val spec = AwgServerSpec.create("203.0.113.5", 51820, AwgGeneration.V3)
        val local = AwgServerSpec.newClient("local", "10.8.1.2/32")
        val remote = AwgServerSpec.newClient("remote", "10.8.1.3/32")
        val s = MyServer("id", "srv", spec.host, fingerprint = "x", awg = spec, clients = listOf(local))
        val ssh = FakeSsh(spec, listOf(local, remote))
        val result = service.addClient(ssh, s, "new", Installer(ssh) {})
        assertNotNull(result)
        assertEquals(listOf("local", "remote", "new"), result.first.clients.map { it.name })
        assertEquals("10.8.1.4/32", result.second.address)
        assertNotNull(ssh.uploadedState)
        assertTrue(ServerOps.parseAwgState(ssh.uploadedState!!)!!.second.any { it.name == "remote" })
    }

    @Test fun removeDoesNotDeleteFreshClients() {
        val spec = AwgServerSpec.create("203.0.113.5", 51820, AwgGeneration.V3)
        val local = AwgServerSpec.newClient("local", "10.8.1.2/32")
        val remote = AwgServerSpec.newClient("remote", "10.8.1.3/32")
        val s = MyServer("id", "srv", spec.host, fingerprint = "x", awg = spec, clients = listOf(local))
        val ssh = FakeSsh(spec, listOf(local, remote))
        val result = service.removeClient(ssh, s, "local", Installer(ssh) {})
        assertNotNull(result)
        assertEquals(listOf("remote"), result.clients.map { it.name })
    }

    @Test fun removedServiceIsNotRecreated() {
        val spec = AwgServerSpec.create("203.0.113.5", 51820, AwgGeneration.V3)
        val local = AwgServerSpec.newClient("local", "10.8.1.2/32")
        val s = MyServer("id", "srv", spec.host, fingerprint = "x", awg = spec, clients = listOf(local))
        val ssh = object : SshSession {
            override fun exec(command: String, onLine: (String) -> Unit) = ExecResult(0, "AWG_UNIT=0\n---END")
            override fun upload(data: ByteArray, remotePath: String, mode: Int) {}
            override fun close() {}
        }
        assertNull(service.removeClient(ssh, s, "local", Installer(ssh) {}))
    }
}
