package ru.gidravpn.hydra.desktop

import ru.gidravpn.hydra.desktop.core.CoreRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 0.8.0-rc2: процессы ядер не должны оставаться после остановки, даже если ссылка на них потеряна. */
class CoreKillTest {
    private fun longRunning(): Process = if (System.getProperty("os.name").lowercase().contains("win"))
        ProcessBuilder("ping", "-n", "60", "127.0.0.1").redirectErrorStream(true).start()
    else ProcessBuilder("sleep", "60").redirectErrorStream(true).start()

    @Test fun killByExecutableEndsMatchingProcesses() {
        val p = longRunning()
        val exe = File(p.toHandle().info().command().get())
        try {
            assertTrue(p.isAlive)
            val n = CoreRunner.killByExecutable(listOf(exe))
            assertTrue(n >= 1, "убито: $n")
            assertTrue(p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally { p.destroyForcibly() }
    }

    @Test fun killByExecutableIgnoresOtherPaths() {
        val p = longRunning()
        try {
            assertEquals(0, CoreRunner.killByExecutable(listOf(File("no-such-dir/definitely-not-a-core.exe"))))
            assertTrue(p.isAlive)
        } finally { p.destroyForcibly() }
    }

    @Test fun killTreeEndsProcess() {
        val p = longRunning()
        CoreRunner.killTree(p)
        assertTrue(p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse(p.isAlive)
    }
}
