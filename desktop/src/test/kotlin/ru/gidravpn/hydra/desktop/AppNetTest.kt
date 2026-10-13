package ru.gidravpn.hydra.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.gidravpn.hydra.desktop.core.AppNet
import ru.gidravpn.hydra.servers.AppProxy
import java.net.Proxy
import java.net.URI

class AppNetTest {
    @Test fun remoteHostsGoThroughCoreProxyWhileConnected() {
        AppNet.use(2080)
        try {
            val p = AppNet.selector.select(URI("https://sub.example.com/x")).single()
            assertEquals(Proxy.Type.HTTP, p.type())
            assertTrue(p.address().toString().endsWith(":2080"))
            assertEquals(2080, AppProxy.port)
            // Clash API и прочий loopback — напрямую
            assertEquals(Proxy.NO_PROXY, AppNet.selector.select(URI("http://127.0.0.1:9090/proxies")).single())
        } finally { AppNet.clear() }
        assertEquals(0, AppProxy.port)
        assertEquals(Proxy.NO_PROXY, AppNet.selector.select(URI("https://sub.example.com/x")).single())
    }
}
