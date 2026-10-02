package ru.gidravpn.hydra.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.gidravpn.hydra.desktop.core.SelfUpdate
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Самообновление portable-версий: распаковка, защита от zip-slip и настоящая подмена папки скриптом на этой ОС. */
class SelfUpdateTest {
    private fun zip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { z -> entries.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
    }

    @Test fun appRootFromExecutablePath() {
        assertEquals(File("C:/Tools/Hydra"), SelfUpdate.appRoot(Os.WINDOWS, "C:/Tools/Hydra/Hydra.exe"))
        assertNull(SelfUpdate.appRoot(Os.WINDOWS, "C:/Java/bin/java.exe"))            // запуск из gradle — не упакованное приложение
        assertEquals(File("/home/u/Hydra"), SelfUpdate.appRoot(Os.LINUX, "/home/u/Hydra/bin/Hydra"))
        assertNull(SelfUpdate.appRoot(Os.LINUX, "/usr/lib/jvm/bin/java"))
        assertEquals(File("/Applications/Hydra.app"), SelfUpdate.appRoot(Os.MACOS, "/Applications/Hydra.app/Contents/MacOS/Hydra"))
    }

    @Test fun extractFindsPayloadAndRejectsZipSlipAndForeignArchives() {
        val tmp = Files.createTempDirectory("hydra-su").toFile()
        val ok = File(tmp, "ok.zip").also { zip(it, mapOf("Hydra/Hydra.exe" to "x", "Hydra/app/a.jar" to "j")) }
        val payload = SelfUpdate.extract(ok, File(tmp, "stage1"), Os.WINDOWS)
        assertEquals("Hydra", payload.name); assertTrue(File(payload, "app/a.jar").isFile)

        val slip = File(tmp, "slip.zip").also { zip(it, mapOf("Hydra/Hydra.exe" to "x", "../evil.txt" to "boom")) }
        try { SelfUpdate.extract(slip, File(tmp, "stage2"), Os.WINDOWS); fail("ожидался отказ") } catch (_: IllegalStateException) {}
        assertFalse(File(tmp, "evil.txt").exists())

        val foreign = File(tmp, "foreign.zip").also { zip(it, mapOf("Other/readme.txt" to "x")) }
        try { SelfUpdate.extract(foreign, File(tmp, "stage3"), Os.WINDOWS); fail("ожидался отказ") } catch (_: IllegalStateException) {}
    }

    /** Настоящая подмена: ждём «умирающий» процесс, папка заменяется, новая версия запускается, старая и каталог распаковки убраны. */
    @Test fun scriptSwapsFolderAndRelaunches() {
        val os = Platform.os
        val tmp = Files.createTempDirectory("hydra-swap").toFile()
        val root = File(tmp, "Hydra").apply { mkdirs() }
        File(root, "version.txt").writeText("old")
        File(root, "stale.jar").writeText("must disappear")
        val stage = File(tmp, ".hydra-update-Hydra")
        val payload = File(stage, "Hydra").apply { mkdirs() }
        File(payload, "version.txt").writeText("new")
        val marker = File(tmp, "launched.txt")
        val launcher: String?
        if (os == Os.WINDOWS) {
            File(payload, "Hydra.cmd").writeText("@echo off\r\ntype \"%~dp0version.txt\" > \"${marker.absolutePath}\"\r\n")
            launcher = "Hydra.cmd"
        } else {
            File(payload, "run.sh").writeText("#!/bin/sh\ncat \"\$(dirname \"\$0\")/version.txt\" > '${marker.absolutePath}'\n")
            launcher = "sh \"\$ROOT/run.sh\""
        }
        val dying = ProcessBuilder(if (os == Os.WINDOWS) listOf("ping", "-n", "3", "127.0.0.1") else listOf("sleep", "2")).start()
        SelfUpdate.launch(os, root, payload, stage, launcher, pid = dying.pid())

        val end = System.currentTimeMillis() + 40_000
        while (!marker.exists() && System.currentTimeMillis() < end) Thread.sleep(200)
        assertTrue("новая версия не запустилась", marker.exists())
        Thread.sleep(2500)                                                   // скрипт чистит за собой после запуска
        assertEquals("new", File(root, "version.txt").readText().trim())
        assertFalse("старый файл не должен остаться", File(root, "stale.jar").exists())
        assertFalse(File(tmp, "Hydra.old").exists())
        assertTrue(marker.readText().trim() == "new")
        assertNotNull(root.listFiles())
    }
}
