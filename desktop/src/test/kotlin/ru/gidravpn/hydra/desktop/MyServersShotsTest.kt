package ru.gidravpn.hydra.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.gidravpn.hydra.desktop.ui.MyServersScreen
import ru.gidravpn.hydra.servers.*
import java.io.File

/** Скриншоты экранов «Мои серверы» для проверки глазами: HYDRA_SCREENSHOTS_DIR=каталог gradle :desktop:test --tests '*MyServersShotsTest*'. */
class MyServersShotsTest {
    @Test fun shots() {
        val dir = System.getenv("HYDRA_SCREENSHOTS_DIR") ?: return assumeTrue(false)
        val out = File(dir).apply { mkdirs() }
        val spec = AwgServerSpec.create("203.0.113.5", 51820, AwgGeneration.V2_0)
        val dev1 = AwgServerSpec.newClient("Hydra", "10.8.1.2/32")
        val dev2 = AwgServerSpec.newClient("telefon-mamy", "10.8.1.3/32")
        val awg = MyServer("1", "Мой VPN", "203.0.113.5", fingerprint = "SHA256:zbpUVHTcvF428Kc5ow9cydNVleZ8w9h+X2F8RuXzCOY", awg = spec, clients = listOf(dev1, dev2))
        val ofx = MyServer("2", "Мой узел", "198.51.100.7", fingerprint = "SHA256:abc", ofx = OfxNodeSpec("198.51.100.7", "hydra", 28763, OfxNodeSpec.newKey()))
        val panel = MyServer("3", "Моя панель", "192.0.2.9", fingerprint = "SHA256:def",
            panel = PanelInstance(PanelKind.XUI3, "http://192.0.2.9:24432/MwHZjvFIQGPjlS", "hyadmin", "Hy-Test-91kQ", "token", "v3.10.0", 1, "hydra-1"))
        val file = File.createTempFile("hydra-shot", ".json").apply { deleteOnExit(); delete() }
        fun controller(servers: List<MyServer>): AppController {
            Store(file).save(HydraState(myServers = servers))
            return AppController(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), Store(file), background = false)
        }
        fun render(name: String, c: AppController, @Suppress("UNUSED_PARAMETER") startWizard: Boolean = false, @Suppress("UNUSED_PARAMETER") step: Int = 1, h: Int = 900) {
            val scheme = AppTheme.AMBIENT.colors().let { k ->
                darkColorScheme(primary = Color(k.accent), onPrimary = Color(k.onAccent), background = Color(k.bg), surface = Color(k.surface),
                    surfaceVariant = Color(k.surfaceVariant), onSurface = Color(k.text), onSurfaceVariant = Color(k.textSecondary))
            }
            val scene = ImageComposeScene(1000, h, Density(1f)) {
                val ui by c.ui.collectAsState()
                MaterialTheme(colorScheme = scheme) {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                        androidx.compose.foundation.layout.Box(Modifier.padding(24.dp)) { MyServersScreen(c, ui) }
                    }
                }
            }
            scene.render(0)
            File(out, "ms-$name.png").writeBytes(scene.render(1_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
        val pre = Preflight("amd64", "Ubuntu 24.04", true, true, true, true, "none", emptyList())
        val plan = AwgInstallPlan.steps(spec, emptyList(), AwgBundle("amd64", listOf("amneziawg-go", "awg", "awg-quick").associateWith { BundleFile(it, ByteArray(1), "") }), false, "none")

        render("0-empty", controller(emptyList()), h = 700)
        controller(emptyList()).let { c -> render("1-choose", c, true, 1, 1000) }
        controller(emptyList()).let { c -> render("2-data", c, true, 2, 900) }
        controller(emptyList()).let { c ->
            c.myServers.previewState(MyServersUi(stage = Stage.CONFIRM_FINGERPRINT, fingerprint = "SHA256:zbpUVHTcvF428Kc5ow9cydNVleZ8w9h+X2F8RuXzCOY"))
            render("3-fingerprint", c, true, 1, 600)
        }
        controller(emptyList()).let { c ->
            c.myServers.previewState(MyServersUi(install = InstallUi("1", ServiceForm(), InstallStage.READY, pre, plan)))
            render("4-check-ok", c, true, 1, 800)
        }
        controller(emptyList()).let { c ->
            c.myServers.previewState(MyServersUi(install = InstallUi("1", ServiceForm(), InstallStage.READY, pre.copy(root = false, portFree = false, issues = listOf("x")), plan)))
            render("4-check-bad", c, true, 1, 900)
        }
        controller(emptyList()).let { c ->
            c.myServers.previewState(MyServersUi(install = InstallUi("1", ServiceForm(), InstallStage.RUNNING), busy = true, opServer = "1", progress = "4/9: Включить пересылку пакетов", log = listOf("▶ Создать каталоги", "✔ Создать каталоги", "▶ Загрузить amneziawg-go")))
            render("5-running", c, true, 1, 520)
        }
        controller(emptyList()).let { c ->
            c.myServers.previewState(MyServersUi(stage = Stage.FAILED, error = "com.jcraft.jsch.JSchException: Auth fail for methods 'password,keyboard-interactive'"))
            render("6-failed", c, true, 2, 1000)
        }
        controller(listOf(awg)).let { c ->
            c.myServers.previewState(MyServersUi(stage = Stage.DONE, progress = "Готово"))
            render("7-done", c, true, 1, 600)
        }
        controller(listOf(awg, ofx, panel)).let { c ->
            c.myServers.previewState(MyServersUi(panelClients = mapOf("3" to listOf("hydra-1", "second"))))
            render("8-cards", c, h = 1250)
        }
        controller(listOf(awg)).let { c ->
            c.myServers.previewState(MyServersUi(shown = "telefon-mamy" to dev2.let { spec.clientConf(it) }))
            render("9-access", c, h = 800)
        }
    }
}
