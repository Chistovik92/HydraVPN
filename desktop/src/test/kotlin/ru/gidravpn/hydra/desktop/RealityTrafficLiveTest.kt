package ru.gidravpn.hydra.desktop

import org.junit.Assume.assumeTrue
import ru.gidravpn.hydra.data.subscription.LinkParser
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Быстрая проверка трафика по готовой ссылке (0.8.1-rc2): HYDRA_LINK=vless://…, HYDRA_XRAY / HYDRA_SINGBOX — пути к ядрам.
 * Без установки панели — для разбора «подключено, но трафика нет».
 */
class RealityTrafficLiveTest {
    @Test fun linkCarriesTraffic() {
        val link = System.getenv("HYDRA_LINK")
        assumeTrue(link != null)
        val p = LinkParser.parseLine(link!!)!!
        System.getenv("HYDRA_SINGBOX")?.let { println("SINGBOX: " + XuiTraffic.through(it, p)) }
        System.getenv("HYDRA_XRAY")?.let { val r = XuiTraffic.throughXray(it, p); println("XRAY: $r"); assertTrue(r.startsWith("ok"), r) }
    }
}
