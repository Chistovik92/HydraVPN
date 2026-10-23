package ru.gidravpn.hydra.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.gidravpn.hydra.servers.*
import kotlin.test.Test
import kotlin.test.assertEquals

/** 0.8.2-rc2: кнопка «Установить ноду» на карточке сервера — какие панели ей предлагаются. */
class NodePanelsTest {
    private fun panel(kind: PanelKind) = PanelInstance(kind, "http://h:1/p", "u", "p", "t")
    private fun srv(id: String, panel: PanelInstance? = null, fp: String = "SHA256:x", nodeOf: NodeRole? = null) =
        MyServer(id = id, name = id, host = "$id.example", fingerprint = fp, panel = panel, nodeOf = nodeOf)

    private fun manager(list: List<MyServer>): MyServersManager {
        var saved = list
        return MyServersManager(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), { saved }, { saved = it },
            { error("не нужен") }, MemoryVault()) { _, _ -> }
    }

    @Test fun offersOnlyPanelsThatHaveNodes() {
        val m = manager(listOf(
            srv("free"), srv("x3", panel(PanelKind.XUI3)), srv("pg", panel(PanelKind.PASARGUARD)), srv("rw", panel(PanelKind.REMNAWAVE)),
            srv("sui", panel(PanelKind.SUI)), srv("xui", panel(PanelKind.XUI)),
        ))
        assertEquals(listOf("x3", "pg", "rw"), m.nodePanels("free").map { it.id })
    }

    @Test fun panelOnlyServerWithoutSshIsNotOffered() {
        val m = manager(listOf(srv("free"), srv("api", panel(PanelKind.XUI3), fp = "")))
        assertEquals(emptyList(), m.nodePanels("free").map { it.id })
    }

    @Test fun serverWithServicesCannotBecomeNode() {
        val m = manager(listOf(srv("busy", panel(PanelKind.XUI3)), srv("x3", panel(PanelKind.XUI3)),
            srv("node", nodeOf = NodeRole(PanelKind.XUI3, "x3", "x3"))))
        assertEquals(emptyList(), m.nodePanels("busy").map { it.id })
        assertEquals(emptyList(), m.nodePanels("node").map { it.id })
        assertEquals(emptyList(), m.nodePanels("unknown").map { it.id })
    }
}
