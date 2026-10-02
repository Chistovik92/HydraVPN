package ru.gidravpn.hydra.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.Subscription
import ru.gidravpn.hydra.desktop.ui.HydraApp
import ru.gidravpn.hydra.router.RouterLink
import ru.gidravpn.hydra.router.RouterLogEntry
import ru.gidravpn.hydra.router.RouterNode
import ru.gidravpn.hydra.router.RouterSection
import ru.gidravpn.hydra.router.RouterSubscription
import ru.gidravpn.hydra.router.RouterUi
import java.io.File

/**
 * Скриншоты ПК-версии для README: настоящий Compose-интерфейс с демо-данными (серверы-заглушки, вымышленные
 * адреса). Запуск: `HYDRA_SCREENSHOTS_DIR=docs/screenshots gradle :desktop:test --tests '*ScreenshotsTest*'`.
 */
class ScreenshotsTest {
    @Test fun desktopScreenshots() {
        val dir = System.getenv("HYDRA_SCREENSHOTS_DIR") ?: return assumeTrue(false)
        val out = File(dir).apply { mkdirs() }
        val now = System.currentTimeMillis()
        fun s(id: Long, name: String, flag: String, host: String, ping: Int, proto: String = "vless", sub: Long? = 1) = ServerProfile(
            id = id, name = name, protocolId = proto, address = host, port = 443, uuidOrPassword = "00000000-0000-0000-0000-00000000000$id",
            security = "reality", subscriptionId = sub, pingMs = ping, flag = flag,
        )
        val servers = listOf(
            s(1, "Нидерланды · Амстердам", "🇳🇱", "nl.example.net", 38), s(2, "Германия · Франкфурт", "🇩🇪", "de.example.net", 44),
            s(3, "Финляндия · Хельсинки", "🇫🇮", "fi.example.net", 61), s(4, "США · Нью-Йорк", "🇺🇸", "us.example.net", 128),
            s(5, "Швеция · Стокгольм", "🇸🇪", "se.example.net", 52, "trojan"), s(6, "Личный сервер", "🏠", "home.example.net", 17, "hysteria2", null),
        )
        val sub = Subscription(
            id = 1, name = "Hydra VPN", serverTitle = "Hydra VPN", url = "https://sub.example.net/demo", lastUpdated = now,
            expireAt = now / 1000 + 21 * 86400, totalBytes = 200L shl 30, downloadBytes = 37L shl 30, uploadBytes = 4L shl 30, botPanel = "1",
        )
        val router = RouterLink("192.168.1.1", 8088, "demo-token", tls = true, fingerprint = "a".repeat(64), name = "Дом · OpenWrt")
        val file = File.createTempFile("hydra-shot", ".json").apply { deleteOnExit(); delete() }
        Store(file).save(HydraState(
            servers = servers, subscriptions = listOf(sub), routers = listOf(router),
            bot = BotLink("https://radar.example.org", "demo", "ivan", 2),
            settings = DesktopSettings(selectedServerId = 1, killSwitch = true),
        ))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = AppController(scope, Store(file), background = false)
        c.previewState { it.copy(status = Status.CONNECTED, statusText = "Подключено · sing-box", connectedId = 1, delayMs = 41,
            downSpeed = 4_200_000, upSpeed = 380_000, downTotal = 612L shl 20, upTotal = 48L shl 20, engine = ru.gidravpn.hydra.data.model.EngineToggles.Kind.SINGBOX,
            log = listOf("[12:01:02] INFO inbound/tun[tun-in]: started", "[12:01:02] INFO outbound/vless[proxy]: connected", "[12:01:05] INFO router: sniff tls: example.com")) }
        c.routers.previewState(RouterUi(
            selected = router.baseUrl, online = true, version = "1.2.4", state = "running", uptime = "3d 4h",
            sections = listOf(
                RouterSection("main", "Основной", true, "connection", "singbox"),
                RouterSection("ru", "Российские сайты", true, "bypass", "singbox"),
                RouterSection("yt", "YouTube (zapret)", false, "bypass", "zapret"),
            ),
            subscriptions = listOf(RouterSubscription(0, "main", "https://sub.example.net/********", true, 24.0)),
            nodes = listOf(
                RouterNode("main", "Selector", now = "Нидерланды", members = listOf("Нидерланды", "Германия", "Финляндия")),
                RouterNode("Нидерланды", "VLESS", delayMs = 41, country = "NL"), RouterNode("Германия", "VLESS", delayMs = 47, country = "DE"),
                RouterNode("Финляндия", "VLESS", delayMs = 63, country = "FI"),
            ),
            logs = listOf(RouterLogEntry("2026-10-02T12:00:01Z", "info", "subscription main refreshed: 12 nodes"), RouterLogEntry("2026-10-02T12:00:05Z", "info", "dns: 77.88.8.8 ok")),
        ))
        val names = listOf("home", "servers", "subscriptions", "routing", "routers", "settings", "log")
        for ((tab, name) in names.withIndex()) {
            val scene = ImageComposeScene(1100, 720, Density(1f)) {
                val ui by c.ui.collectAsState()
                HydraApp(c, ui, onRelaunchAdmin = {}, startTab = tab)
            }
            scene.render(0)
            val png = scene.render(1_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "desktop-$name.png").writeBytes(png)
            scene.close()
        }
        scope.cancel()
    }
}
