package ru.gidravpn.hydra.desktop.core

import ru.gidravpn.hydra.desktop.Os
import ru.gidravpn.hydra.desktop.Platform
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Самообновление без установщика (0.7.3): portable-ZIP на Windows, tar.gz на Linux и .app на macOS.
 * Приложение скачивает архив (SHA-256 сверяется в [Updates.download]), распаковывает его РЯДОМ со своей папкой
 * (на том же томе — каталог переименовывается мгновенно), затем запускает короткий скрипт и завершается; скрипт
 * ждёт выхода процесса, подменяет папку (старая остаётся как `.old` до успешного запуска, при сбое возвращается),
 * запускает новую Hydra и убирает за собой. Пользовательские данные лежат в каталоге данных, а не в папке
 * приложения, поэтому подмена их не трогает.
 */
object SelfUpdate {

    /** Корень установки по пути запущенного файла; null — Hydra запущена не из упакованного приложения (например, `gradle run`). */
    fun appRoot(os: Os = Platform.os, exe: String? = Platform.selfExecutable): File? {
        val f = exe?.let(::File) ?: return null
        // Classic: корень — каталог со сценарием запуска (Hydra.vbs / hydra.sh), рядом lib/ и jre/.
        if (Platform.classic) return if (f.name == classicLauncherName(os)) f.absoluteFile.parentFile else null
        return when (os) {
            Os.WINDOWS -> if (f.name.equals("Hydra.exe", true)) f.parentFile else null
            // …/Hydra/bin/Hydra → …/Hydra
            Os.LINUX -> if (f.name == "Hydra" && f.parentFile?.name == "bin") f.parentFile.parentFile else null
            // /Applications/Hydra.app/Contents/MacOS/Hydra → /Applications/Hydra.app
            Os.MACOS -> generateSequence(f) { it.parentFile }.firstOrNull { it.name.endsWith(".app") }
        }
    }

    /** Подмена возможна, если можно переименовать папку приложения и создать рядом временную. */
    fun canReplace(root: File): Boolean {
        val parent = root.absoluteFile.parentFile ?: return false
        return root.isDirectory && Files.isWritable(root.toPath()) && Files.isWritable(parent.toPath())
    }

    /** Каталог распаковки — на одном томе с [root]. */
    fun stageDir(root: File): File = File(root.absoluteFile.parentFile, ".hydra-update-" + root.name)

    /** Распаковывает [archive] в [stage] и возвращает папку с новой версией (единственный верхний каталог / `.app`). */
    fun extract(archive: File, stage: File, os: Os = Platform.os): File {
        stage.deleteRecursively()
        stage.mkdirs()
        val n = archive.name.lowercase()
        when {
            n.endsWith(".zip") -> unzip(archive, stage)
            n.endsWith(".tar.gz") -> run(listOf("tar", "-xzf", archive.absolutePath, "-C", stage.absolutePath))
            n.endsWith(".dmg") -> extractDmg(archive, stage)
            else -> error("неизвестный формат архива: ${archive.name}")
        }
        val children = stage.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
        val payload = children.singleOrNull { it.isDirectory } ?: error("в архиве ожидалась одна папка приложения")
        check(File(payload, launcherPath(os)).exists()) { "в архиве нет ${launcherPath(os)} — это не Hydra" }
        return payload
    }

    private fun classicLauncherName(os: Os) = if (os == Os.WINDOWS) "Hydra.vbs" else "hydra.sh"

    private fun launcherPath(os: Os) =
        if (Platform.classic) classicLauncherName(os)
        else when (os) { Os.WINDOWS -> "Hydra.exe"; Os.LINUX -> "bin/Hydra"; Os.MACOS -> "Contents/MacOS/Hydra" }

    private fun unzip(zip: File, dest: File) {
        val base = dest.canonicalFile.toPath()
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val out = dest.resolve(e.name).canonicalFile
                // Защита от zip-slip: ничего вне каталога распаковки.
                check(out.toPath().startsWith(base)) { "небезопасный путь в архиве: ${e.name}" }
                if (e.isDirectory) out.mkdirs() else { out.parentFile.mkdirs(); out.outputStream().use { zin.copyTo(it) } }
            }
        }
    }

    /** macOS: образ монтируется, `Hydra.app` копируется (`ditto` сохраняет права и ссылки), образ отключается. */
    private fun extractDmg(dmg: File, dest: File) {
        val mount = Files.createTempDirectory("hydra-dmg").toFile()
        run(listOf("hdiutil", "attach", "-nobrowse", "-readonly", "-noverify", "-mountpoint", mount.absolutePath, dmg.absolutePath))
        try {
            val app = mount.listFiles()?.firstOrNull { it.name.endsWith(".app") } ?: error("в образе нет приложения")
            run(listOf("ditto", app.absolutePath, File(dest, app.name).absolutePath))
        } finally {
            runCatching { run(listOf("hdiutil", "detach", "-force", mount.absolutePath)) }
            mount.delete()
        }
    }

    private fun run(cmd: List<String>) {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "${cmd.first()}: ${out.trim().take(300)}" }
    }

    // ------------------------------------------------------------ подмена

    /** Скрипт подмены: ждёт выхода процесса [pid], меняет [root] на [payload], запускает новую Hydra, чистит [stage]. */
    fun script(os: Os, pid: Long, root: File, payload: File, stage: File, launcher: String? = null): String = when (os) {
        Os.WINDOWS -> {
            val start = launcher ?: "Hydra.exe"
            """
            |@echo off
            |chcp 65001 >nul
            |:wait
            |tasklist /FI "PID eq $pid" 2>nul | find "$pid" >nul && (timeout /t 1 /nobreak >nul & goto wait)
            |set "ROOT=${root.absolutePath}"
            |set "NEW=${payload.absolutePath}"
            |set "OLD=%ROOT%.old"
            |rd /s /q "%OLD%" 2>nul
            |set N=0
            |:retry
            |move "%ROOT%" "%OLD%" >nul 2>&1 && goto moved
            |set /a N+=1
            |if %N% geq 30 goto launch
            |timeout /t 1 /nobreak >nul
            |goto retry
            |:moved
            |move "%NEW%" "%ROOT%" >nul 2>&1 || move "%OLD%" "%ROOT%" >nul 2>&1
            |:launch
            |start "" "%ROOT%\$start"
            |rd /s /q "%OLD%" 2>nul
            |rd /s /q "${stage.absolutePath}" 2>nul
            |(goto) 2>nul & del "%~f0"
            """.trimMargin().replace("\n", "\r\n") + "\r\n"
        }
        Os.LINUX, Os.MACOS -> {
            val run = launcher ?: if (os == Os.MACOS) "open -n \"${'$'}ROOT\"" else "\"${'$'}ROOT/bin/Hydra\""
            """
            |#!/bin/sh
            |while kill -0 $pid 2>/dev/null; do sleep 1; done
            |ROOT='${root.absolutePath.replace("'", "'\\''")}'
            |NEW='${payload.absolutePath.replace("'", "'\\''")}'
            |OLD="${'$'}ROOT.old"
            |rm -rf "${'$'}OLD"
            |if mv "${'$'}ROOT" "${'$'}OLD" 2>/dev/null; then
            |  mv "${'$'}NEW" "${'$'}ROOT" 2>/dev/null || mv "${'$'}OLD" "${'$'}ROOT"
            |fi
            |$run >/dev/null 2>&1 &
            |sleep 2
            |rm -rf "${'$'}OLD" '${stage.absolutePath.replace("'", "'\\''")}'
            |rm -f "${'$'}0"
            """.trimMargin() + "\n"
        }
    }

    /** Пишет скрипт и запускает его отдельным процессом, переживающим выход Hydra. */
    fun launch(os: Os, root: File, payload: File, stage: File, launcher: String? = null, pid: Long = ProcessHandle.current().pid()): File {
        val ext = if (os == Os.WINDOWS) ".cmd" else ".sh"
        // Скрипт лежит вне каталога распаковки: тот чистится самим скриптом.
        val file = File.createTempFile("hydra-swap", ext)
        file.writeText(script(os, pid, root, payload, stage, launcher), Charsets.UTF_8)
        file.setExecutable(true)
        val cmd = when (os) {
            Os.WINDOWS -> listOf("cmd", "/c", "start", "\"\"", "/min", file.absolutePath)
            // setsid есть на Linux, но не на macOS: без него — nohup (скрипт всё равно переживает выход Hydra).
            else -> listOf("sh", "-c",
                "if command -v setsid >/dev/null 2>&1; then (setsid sh '${file.absolutePath}' >/dev/null 2>&1 &); " +
                    "else (nohup sh '${file.absolutePath}' >/dev/null 2>&1 &); fi")
        }
        ProcessBuilder(cmd).redirectErrorStream(true).start()
        return file
    }

    /**
     * Полный путь: распаковать архив, запустить подмену. Возвращает true, если Hydra должна завершиться.
     * Бросает исключение до запуска скрипта, если что-то не так (тогда ничего не изменено).
     */
    fun apply(archive: File): Boolean {
        val root = appRoot() ?: return false
        if (!canReplace(root)) return false
        val stage = stageDir(root)
        val payload = extract(archive, stage)
        // Classic после подмены стартует своим сценарием (в скрипте подмены $ROOT уже указывает на новую папку).
        val launcher = if (!Platform.classic) null else if (Platform.os == Os.WINDOWS) "Hydra.vbs" else "\"${'$'}ROOT/hydra.sh\""
        launch(Platform.os, root, payload, stage, launcher)
        return true
    }
}
