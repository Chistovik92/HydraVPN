package ru.gidravpn.hydra.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SecretMaskTest {
    @Test fun vlessUuidAndRealityKeysAreHidden() {
        val link = "vless://0f3c1a2b-aaaa-bbbb-cccc-123456789abc@de.example:443?security=reality" +
            "&pbk=AbCdEfGhIjKlMnOp&sid=1a2b&sni=www.example.com#DE-1"
        assertEquals(
            "vless://••••@de.example:443?security=reality&pbk=••••&sid=••••&sni=www.example.com#DE-1",
            SecretMask.mask(link),
        )
    }

    @Test fun subscriptionTokenIsHidden() =
        assertEquals("https://panel.example/sub/••••", SecretMask.mask("https://panel.example/sub/Zx81kQpLmN0w"))

    @Test fun base64VmessShowsOnlyScheme() =
        assertEquals("vmess://••••", SecretMask.mask("vmess://eyJhZGQiOiIxLjIuMy40IiwicG9ydCI6NDQzfQ=="))

    @Test fun shortPublicPathIsKept() =
        assertEquals("https://example.com/sub", SecretMask.mask("https://example.com/sub"))

    @Test fun trojanPasswordIsHidden() =
        assertFalse(SecretMask.mask("trojan://s3cretPassw0rd@t.example:443#T").contains("s3cret"))
}
