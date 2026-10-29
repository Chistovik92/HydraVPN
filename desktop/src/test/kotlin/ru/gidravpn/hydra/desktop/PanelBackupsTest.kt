package ru.gidravpn.hydra.desktop

import ru.gidravpn.hydra.servers.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PanelBackupsTest {
    @Test fun allPanelBackupsAreListedAndValidated() {
        val out = """
            x-ui-20261023-183005.db
            s-ui-20261024-183005.db
            pasarguard-20261025-183005.db
            remnawave-20261026-183005.sql
            remnawave-20261025-183005.db
            pasarguard-../../etc.db
        """.trimIndent()
        assertEquals(listOf("x-ui-20261023-183005.db"), PanelBackups.parse(out, PanelKind.XUI3).map { it.file })
        assertEquals(listOf("pasarguard-20261025-183005.db"), PanelBackups.parse(out, PanelKind.PASARGUARD).map { it.file })
        assertEquals(listOf("remnawave-20261026-183005.sql"), PanelBackups.parse(out, PanelKind.REMNAWAVE).map { it.file })
        for (kind in listOf(PanelKind.XUI3, PanelKind.XUI, PanelKind.SUI, PanelKind.PASARGUARD, PanelKind.REMNAWAVE)) assertTrue(PanelBackups.supports(kind))
        assertFalse(PanelBackups.validFile("remnawave-../../etc.sql"))
    }

    @Test fun pasarguardRestoreChecksSqliteAndCanRollBack() {
        val steps = PanelBackups.restoreSteps(PanelKind.PASARGUARD, "pasarguard-20261025-183005.db")
        assertTrue(steps[0].command!!.contains("PRAGMA integrity_check"))
        assertTrue(steps[1].command!!.contains("cp -a ${ServerOps.PANEL_DBS["pasarguard"]}"))
        assertTrue(steps[2].command!!.contains("docker compose -p pasarguard stop"))
        assertTrue(steps.last().command!!.contains("restore-rolled-back"))
    }

    @Test fun remnawaveRestoreUsesPostgresAndReturnsOldVolume() {
        val steps = PanelBackups.restoreSteps(PanelKind.REMNAWAVE, "remnawave-20261026-183005.sql")
        assertTrue(steps[0].command!!.contains("PostgreSQL database dump complete"))
        assertTrue(steps[1].command!!.contains("pg_dump -U postgres postgres"))
        val restore = steps[3]
        assertTrue(restore.command!!.contains("psql -v ON_ERROR_STOP=1"))
        assertNotNull(restore.rollback)
        assertTrue(restore.rollback!!.contains("mv /root/hydra-backups/remnawave-before-restore.volume"))
        assertTrue(steps.last().command!!.contains("restore-rolled-back"))
    }
}
