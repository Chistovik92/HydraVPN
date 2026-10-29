package ru.gidravpn.hydra.desktop

import ru.gidravpn.hydra.servers.PanelKind
import ru.gidravpn.hydra.servers.PanelNodes
import ru.gidravpn.hydra.servers.XuiPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R2: главная панель получает HTTPS-ноду с leaf SHA-256, без HTTP и InsecureSkipVerify. */
class XuiNodeHttpsTest {
    private val pin = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    @Test fun nodeRequestPinsTrustedLeafCertificate() {
        val body = PanelNodes.xuiNodeBody("Hydra-node", "203.0.113.5", 24680, "secret", "node-sync-token", pin)
        assertEquals("https", body.getString("scheme"))
        assertEquals("pin", body.getString("tlsVerifyMode"))
        assertEquals(pin, body.getString("pinnedCertSha256"))
        assertEquals("203.0.113.5", body.getString("address"))
        assertFalse(body.toString().contains("skip"))
        assertTrue(PanelNodes.validXuiCertPin(pin))
    }

    @Test fun missingOrWrongPinCannotBecomeUnencryptedNode() {
        for (bad in listOf("", "abc", pin.dropLast(1), pin + "=", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA-"))
            assertTrue(runCatching { PanelNodes.xuiNodeBody("n", "203.0.113.5", 1, "p", "t", bad) }.isFailure, bad)
    }

    @Test fun fingerprintIsReadFromInstalledLeafOverSsh() {
        val cmd = XuiPanel.certFingerprintCommand()
        assertTrue(cmd.startsWith("openssl x509 -in ${XuiPanel.CERT_DIR}/fullchain.pem -outform DER"), cmd)
        assertTrue("openssl dgst -sha256 -binary" in cmd && "openssl base64 -A" in cmd, cmd)
        // Отпечаток не берётся из непроверенного TLS-рукопожатия.
        assertFalse("-k" in cmd || "s_client" in cmd)
    }

    @Test fun planUsesCommonAcmeInstallAndApply() {
        val steps = XuiPanel.httpsSteps(PanelKind.XUI3, "203.0.113.5", true, "ufw", 24680, "secret")
        assertTrue(steps.any { it.id == "cert-install" && "--installcert" in it.command.orEmpty() })
        assertTrue(steps.any { it.id == "cert-panel" && "x-ui cert -webCert" in it.command.orEmpty() })
        assertTrue(steps.any { it.id == "cert-check" && "https" in it.command.orEmpty() })
        assertTrue(PanelNodes.planPreview(PanelKind.XUI3, "main").any { it.id == "node-cert" })
    }
}
