package ru.gidravpn.hydra.desktop

import org.json.JSONObject
import ru.gidravpn.hydra.servers.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Nexora v0.0.6: стабильные тела запросов, пагинация API v1 и SSH install-jobs. */
class NexoraPanelTest {
    private class FakeTransport : XuiTransport {
        val calls = mutableListOf<Triple<String, String, String?>>()
        val responses = ArrayDeque<String>()
        override fun call(method: String, path: String, body: String?): String {
            calls += Triple(method, path, body)
            return responses.removeFirst()
        }
    }

    @Test fun setupTokenAndCredentialsHaveStableShapes() {
        val setup = NexoraPanel.setupBody("once", "hydra", "Strong-password1", 23456)
        assertEquals("127.0.0.1", setup.getString("listenIp"))
        assertEquals("23456", setup.getString("listenPort"))
        assertEquals("none", setup.getString("tlsMode"))
        assertEquals("sub", setup.getString("subBasepath"))
        val target = SshTarget("[2001:db8::1]", 2222, "root", "secret")
        val creds = NexoraPanel.credentials(target)
        assertEquals("2001:db8::1", creds.getString("sshHost"))
        assertEquals(2222, creds.getInt("sshPort"))
        assertFalse(creds.has("privateKey"))
        val install = NexoraPanel.installBody(target)
        assertEquals("script", install.getString("method"))
        assertEquals("upload", install.getString("source"))
        assertTrue(install.getBoolean("installKey"))
        assertTrue(install.getBoolean("rememberHost"))
        assertFalse(install.getBoolean("uninstall"))
        assertTrue(NexoraPanel.installBody(target, uninstall = true).getBoolean("uninstall"))
        val node = NexoraPanel.nodeBody("Hydra-node", target)
        assertEquals(62050, node.getInt("port"))
        assertEquals("2001:db8::1", node.getString("address"))
        assertEquals("Hydra-node", node.getString("name"))
        assertTrue(NexoraPanel.userBody("alice").getBoolean("allTemplates"))
        assertEquals("Hydra", NexoraPanel.tokenBody().getString("desc"))
    }

    @Test fun usersReadEveryPaginatedPageAndCreateSubscription() {
        val t = FakeTransport()
        t.responses += """{"items":[{"id":1,"name":"alice","subUrl":"http://p/sub/a"}],"total":2,"limit":1,"offset":0}"""
        t.responses += """{"items":[{"id":2,"name":"bob","subUrl":"http://p/sub/b"}],"total":2,"limit":1,"offset":1}"""
        val users = NexoraApi(t).users()
        assertEquals(listOf("alice", "bob"), users.map { it.name })
        assertEquals("/api/v1/users?limit=1000&offset=0", t.calls[0].second)
        assertEquals("/api/v1/users?limit=1000&offset=1", t.calls[1].second)
        t.responses += """{"id":3,"name":"carol","subUrl":"http://p/sub/c"}"""
        assertEquals("http://p/sub/c", NexoraApi(t).addUser("carol").subUrl)
        assertEquals("POST", t.calls.last().first)
        assertEquals("carol", JSONObject(t.calls.last().third).getString("name"))
    }

    @Test fun nodesReadStableListEnvelope() {
        val t = FakeTransport()
        t.responses += """{"items":[{"id":7,"name":"n1","status":"connected","message":""}],"total":1,"limit":1000,"offset":0}"""
        val node = NexoraApi(t).nodes().single()
        assertEquals(7, node.id)
        assertEquals("connected", node.status)
        assertEquals("/api/v1/nodes?limit=1000&offset=0", t.calls.single().second)
    }

    @Test fun installJobPollsCursorAndForwardsOnlyNewLines() {
        val t = FakeTransport()
        t.responses += """{"id":"job-1","status":"running","message":""}"""
        t.responses += """{"job":{"id":"job-1","status":"running","message":""},"lines":["downloading"],"cursor":1}"""
        t.responses += """{"job":{"id":"job-1","status":"done","message":""},"lines":["installed"],"cursor":2}"""
        val sleeps = mutableListOf<Long>()
        val lines = mutableListOf<String>()
        val job = NexoraApi(t, sleeps::add).awaitInstall(7, SshTarget("203.0.113.5", 22, "root", "pw"), onLines = lines::addAll)
        assertEquals("done", job.status)
        assertEquals(listOf("downloading", "installed"), lines)
        assertEquals(listOf(2000L), sleeps)
        assertEquals("/api/v1/nodes/install/jobs/job-1?cursor=0", t.calls[1].second)
        assertEquals("/api/v1/nodes/install/jobs/job-1?cursor=1", t.calls[2].second)
    }

    @Test fun installJobErrorIsNotSuccess() {
        val t = FakeTransport()
        t.responses += """{"id":"job-2","status":"running","message":""}"""
        t.responses += """{"job":{"id":"job-2","status":"error","message":"SSH failed"},"lines":[],"cursor":0}"""
        val e = assertFailsWith<XuiApiException> { NexoraApi(t) { }.awaitInstall(7, SshTarget("h", 22, "root", "pw")) }
        assertTrue("SSH failed" in e.message!!)
    }

    @Test fun installAndCleanupUsePinnedOfficialInstaller() {
        assertEquals("93d94a7e77fa804752a579211e33dbab80079f1585380671f0c9e2bfca34aa9f",
            ScriptPins.sha256(NexoraPanel.REPO, NexoraPanel.TAG, "install.sh"))
        val install = NexoraPanel.installSteps().first { it.id == "nexora-install" }.command!!
        assertTrue("--version v0.0.6" in install && "--node-version v0.0.6" in install)
        val uninstall = NexoraPanel.uninstallSteps().single { it.id == "nexora-remove" }.command!!
        assertTrue("--uninstall" in uninstall && "sha256sum -c" in uninstall && "rm -rf ${NexoraPanel.DATA}" in uninstall)
    }
}
