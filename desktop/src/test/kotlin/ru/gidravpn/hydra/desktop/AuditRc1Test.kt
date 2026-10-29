package ru.gidravpn.hydra.desktop

import ru.gidravpn.hydra.servers.FileVault
import ru.gidravpn.hydra.servers.KeyFileCipher
import ru.gidravpn.hydra.servers.MyServer
import ru.gidravpn.hydra.servers.MyServerService
import ru.gidravpn.hydra.servers.SavedLogin
import ru.gidravpn.hydra.servers.ServerOps
import ru.gidravpn.hydra.servers.ServerStatus
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Исправления аудита 0.8.2-rc1: хранилище входа, оборванная проверка сервера, строгая копия базы. */
class AuditRc1Test {
    private fun tmp(): File = Files.createTempDirectory("hydra-audit").toFile().apply { deleteOnExit() }

    @Test
    fun `vault keeps working after corrupt file and quarantines it`() {
        val dir = tmp()
        val file = File(dir, "servers-login.json")
        val vault = FileVault(file, KeyFileCipher(File(dir, "servers-login.key")))
        vault.save("a", SavedLogin(password = "p1"))
        file.writeText("{oops")   // повреждение: чтение — как пустое, запись не падает и откладывает копию
        assertNull(vault.load("a"))
        vault.save("b", SavedLogin(password = "p2"))
        assertTrue(File(dir, "servers-login.json.corrupt").exists(), "повреждённый файл отложен рядом")
        assertEquals("p2", vault.load("b")?.password)
    }

    @Test
    fun `vault key of wrong length is regenerated and file is replaced atomically`() {
        val dir = tmp()
        val key = File(dir, "k.key").apply { writeBytes(ByteArray(7)) }   // недописанный ключ
        val vault = FileVault(File(dir, "v.json"), KeyFileCipher(key))
        vault.save("x", SavedLogin(password = "secret"))
        assertEquals(32L, key.length())
        assertEquals("secret", vault.load("x")?.password)
        assertFalse(File(dir, "v.json.tmp").exists())
    }

    @Test
    fun `incomplete status output is not applied by reconcile`() {
        val partial = ServerOps.parseStatus("OS=Ubuntu\nARCH=x86_64\nAWG_UNIT=")   // оборвалось до ---END
        assertFalse(partial.complete)
        val srv = MyServer("i", "n", "203.0.113.5", fingerprint = "x")
        val (same, write) = MyServerService { error("") }.reconcile(srv, partial)
        assertEquals(srv, same)
        assertFalse(write)
        assertTrue(ServerOps.parseStatus("OS=Ubuntu\n---END\n").complete)
        assertTrue(ServerStatus().complete)
    }

    @Test
    fun `panel backup step fails when a copy cannot be made`() {
        val cmd = ServerOps.backupPanelsCommand()
        assertTrue("ok=1" in cmd && "[ -s " in cmd && cmd.trimEnd().endsWith("[ \$ok -eq 0 ]"), cmd)
        assertNotNull(ServerOps.backupPanelsStep().failHint)
    }
}
