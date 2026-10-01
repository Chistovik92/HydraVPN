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
import ru.gidravpn.hydra.desktop.ui.HydraApp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Все экраны собираются и рисуются без окна (ловит падения композиции до релиза). */
class UiRenderTest {

    @Test
    fun `every screen renders`() {
        val out = File(System.getProperty("hydra.configDump") ?: "build").parentFile.resolve("ui-render").apply { mkdirs() }
        val storeFile = File.createTempFile("hydra-ui", ".json").apply { deleteOnExit(); delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = AppController(scope, Store(storeFile), background = false)
        c.import(
            "vless://11111111-2222-3333-4444-555555555555@nl.example.com:443?security=reality&pbk=x&sid=1&sni=a.com#Нидерланды\n" +
                "trojan://pw@de.example.com:443#Германия\n" +
                "awg://AAAA@fi.example.com:51820#AmneziaWG Финляндия",
        )
        // Все редакторы маршрутизации и раздачи — открыты, чтобы рисовались и они.
        c.updateRouting {
            it.copy(appMode = ru.gidravpn.hydra.data.model.SplitTunnelMode.EXCLUDE, apps = listOf("chrome.exe"),
                netMode = ru.gidravpn.hydra.data.model.SplitTunnelMode.INCLUDE,
                geoMode = ru.gidravpn.hydra.data.model.GeoRoutingMode.DIRECT)
        }
        c.addNetRule(null, "youtube.com")
        c.setLanShare(true)
        c.saveProfile("Дом")
        c.toast(null)
        for (tab in 0 until 7) {
            val scene = ImageComposeScene(980, 680, Density(1f)) {
                val ui by c.ui.collectAsState()
                HydraApp(c, ui, onRelaunchAdmin = {}, startTab = tab)
            }
            scene.render(0)
            val img = scene.render(1_000_000_000L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "screen-$tab.png").writeBytes(png)
            scene.close()
            assertTrue(png.size > 10_000, "экран $tab пустой")
        }
        scope.cancel()
    }
}
