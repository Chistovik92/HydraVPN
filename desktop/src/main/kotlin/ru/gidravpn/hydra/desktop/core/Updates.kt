package ru.gidravpn.hydra.desktop.core

import ru.gidravpn.hydra.desktop.Os
import ru.gidravpn.hydra.desktop.Platform
import ru.gidravpn.hydra.update.ReleaseAsset
import ru.gidravpn.hydra.update.ReleaseInfo
import ru.gidravpn.hydra.update.UpdateFeed
import java.io.File

/**
 * Обновления ПК-клиента (0.7.1): последний релиз на GitHub → файл под эту ОС и архитектуру → загрузка со
 * сверкой SHA-256 → запуск установщика ОС. Логика поиска и загрузки общая с Android (`:shared`, UpdateFeed).
 */
object Updates {
    const val REPO = UpdateFeed.REPO

    /** [asset] — файл для этой машины; null — подходящего нет (тогда только страница релиза). */
    data class Release(val version: String, val pageUrl: String, val asset: ReleaseAsset? = null)

    fun latest(channel: ru.gidravpn.hydra.update.UpdateChannel = ru.gidravpn.hydra.update.UpdateChannel.STABLE): Release {
        val info = UpdateFeed.latest("Hydra-desktop-update-check", channel)
        return Release(info.version, info.pageUrl, pick(info, Platform.os, arch(), installKind(), Platform.classic))
    }

    fun isNewer(remote: String, current: String): Boolean = UpdateFeed.isNewer(remote, current)

    /** Что скачивать: [kind] — как Hydra установлена на этой машине. */
    fun pick(info: ReleaseInfo, os: Os, arch: String, kind: InstallKind, classic: Boolean = false): ReleaseAsset? = when {
        // Classic (Swing, 32-бит и Windows 7) — только архив: zip на Windows, tar.gz на Linux.
        classic -> UpdateFeed.pickDesktop(info, if (os == Os.WINDOWS) "windows" else "linux", arch,
            listOf(if (os == Os.WINDOWS) "zip" else "tar.gz"), classic = true)
        os == Os.WINDOWS -> UpdateFeed.pickDesktop(info, "windows", arch, when (kind) {
            InstallKind.PORTABLE -> listOf("zip")
            else -> listOf("msi", "exe")
        })
        os == Os.MACOS -> UpdateFeed.pickDesktop(info, "macos", arch, listOf("dmg"))
        else -> UpdateFeed.pickDesktop(info, "linux", arch, when (kind) {
            InstallKind.APPIMAGE -> listOf("AppImage")
            InstallKind.DEB -> listOf("deb")
            InstallKind.RPM -> listOf("rpm")
            else -> listOf("tar.gz")
        })
    }

    enum class InstallKind { INSTALLED, PORTABLE, APPIMAGE, DEB, RPM, ARCHIVE }

    fun arch(): String = Platform.arch

    /** Как запущена эта копия Hydra: от этого зависит, какой файл релиза ей подходит. */
    fun installKind(): InstallKind = when (Platform.os) {
        Os.WINDOWS -> {
            val exe = (Platform.selfExecutable ?: "").lowercase()
            if ("program files" in exe || "\\appdata\\local\\" in exe) InstallKind.INSTALLED else InstallKind.PORTABLE
        }
        Os.MACOS -> InstallKind.INSTALLED
        Os.LINUX -> when {
            !System.getenv("APPIMAGE").isNullOrBlank() -> InstallKind.APPIMAGE
            (Platform.selfExecutable ?: "").startsWith("/opt/") && onPath("dpkg") -> InstallKind.DEB
            (Platform.selfExecutable ?: "").startsWith("/opt/") && onPath("rpm") -> InstallKind.RPM
            else -> InstallKind.ARCHIVE
        }
    }

    private fun onPath(cmd: String) = (System.getenv("PATH") ?: "").split(File.pathSeparator).any { File(it, cmd).canExecute() }

    fun download(r: Release, onProgress: (Long, Long) -> Boolean): File {
        val a = r.asset ?: error("для этой системы нет готового файла — откройте страницу релиза")
        // Имя файла — из релиза, но без путей: с диска пишем только в свой каталог обновлений.
        val name = File(a.name).name
        return UpdateFeed.download(a, File(File(Platform.dataDir, "updates"), name), onProgress)
    }

    /**
     * Запускает установщик скачанного файла. Возвращает true, если Hydra должна завершиться (установщику
     * нужно заменить её файлы), и текст для пользователя.
     */
    fun install(file: File): Pair<Boolean, String> {
        val n = file.name.lowercase()
        // 0.7.3: portable-ZIP, tar.gz и .app подменяются сами (SelfUpdate); не вышло — обычный путь ниже.
        if (n.endsWith(".zip") || n.endsWith(".tar.gz") || (n.endsWith(".dmg") && Platform.os == Os.MACOS)) {
            val replaced = try { SelfUpdate.apply(file) } catch (e: Exception) { return false to "Не удалось подготовить обновление: ${e.message}. Файл: ${file.absolutePath}" }
            if (replaced) return true to "Обновление скачано: Hydra перезапустится сама через несколько секунд."
        }
        return when {
            n.endsWith(".msi") -> { startAfterExit(file, listOf("msiexec", "/i", file.absolutePath)); true to "Hydra закроется, установщик откроется следом." }
            n.endsWith(".exe") -> { startAfterExit(file, listOf(file.absolutePath)); true to "Hydra закроется, установщик откроется следом." }
            n.endsWith(".dmg") -> { ProcessBuilder("open", file.absolutePath).start(); false to "Образ открыт: перетащите Hydra в «Программы», заменив старую, и перезапустите." }
            n.endsWith(".appimage") -> replaceAppImage(file)
            n.endsWith(".deb") || n.endsWith(".rpm") -> {
                // Графический установщик пакетов — ему нужны права, он сам их спросит.
                val opened = runCatching { ProcessBuilder("xdg-open", file.absolutePath).start() }.isSuccess
                false to if (opened) "Открыт установщик пакетов. После установки перезапустите Hydra." else "Пакет скачан: ${file.absolutePath}"
            }
            else -> {
                runCatching {
                    when (Platform.os) {
                        Os.WINDOWS -> ProcessBuilder("explorer", "/select,${file.absolutePath}").start()
                        Os.MACOS -> ProcessBuilder("open", "-R", file.absolutePath).start()
                        Os.LINUX -> ProcessBuilder("xdg-open", file.parentFile.absolutePath).start()
                    }
                }
                false to "Архив скачан: ${file.absolutePath}. Распакуйте поверх старой копии."
            }
        }
    }

    /**
     * A7 (0.8.2-a4): установщик Windows — только после того, как Hydra и её ядра вышли. Раньше он запускался сразу, пока Hydra
     * ещё закрывалась: занятые файлы (`runtime\lib\modules`, ресурсы, ядра) установщик без прав администратора заменить не
     * может (ошибка 1304, проверка `installer-nonadmin` в CI), а старая версия к этому моменту уже удалена — Hydra пропадала,
     * с окнами это выглядело как зависание. Ждёт скрытый PowerShell: выход этого процесса и процессов из папки установки.
     */
    private fun startAfterExit(installer: File, cmd: List<String>) {
        val dir = Platform.selfExecutable?.let { File(it).parentFile?.absolutePath }
        val ps1 = File(installer.parentFile, "hydra-install.ps1")
        ProcessBuilder(powershellFile(writeScript(ps1, afterExitScript(ProcessHandle.current().pid(), dir, cmd)))).start()
    }

    /**
     * Скрипт — файлом, а не в `-Command`: Java на Windows не экранирует кавычки внутри аргумента, и путь в кавычках
     * (`msiexec /i "…\Hydra VPN\…"`) развалил бы командную строку. `-EncodedCommand` не годится: антивирусы
     * (проверено: Kaspersky — «Отказано в доступе») не дают запустить скрытый PowerShell с закодированной командой.
     * UTF-8 с BOM — иначе Windows PowerShell 5 прочтёт кириллицу в пути как ANSI.
     */
    internal fun writeScript(f: File, script: String): File = f.apply {
        parentFile?.mkdirs()
        writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + script.toByteArray(Charsets.UTF_8))
    }

    internal fun powershellFile(f: File): List<String> = listOf("powershell", "-NoProfile", "-NonInteractive",
        "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", f.absolutePath)

    internal fun afterExitScript(pid: Long, installDir: String?, cmd: List<String>): String {
        fun lit(s: String) = "'" + s.replace("'", "''") + "'"
        val args = cmd.drop(1).joinToString(" ") { if (it.any { c -> c == ' ' || c == '\t' }) "\"$it\"" else it }
        val waitDir = installDir?.let {
            "\$d = ${lit(it.trimEnd('\\') + "\\")}; " +
                "Get-Process -ErrorAction SilentlyContinue | Where-Object { \$_.Path -and \$_.Path.StartsWith(\$d, 'OrdinalIgnoreCase') -and \$_.Id -ne \$PID } | " +
                "Wait-Process -Timeout 30 -ErrorAction SilentlyContinue; "
        }.orEmpty()
        return "Wait-Process -Id $pid -Timeout 90 -ErrorAction SilentlyContinue; $waitDir" +
            "Start-Sleep -Seconds 2; " +
            "Start-Process -FilePath ${lit(cmd.first())}" + (if (args.isNotEmpty()) " -ArgumentList ${lit(args)}" else "")
    }

    /** AppImage заменяет сам себя: новый файл ложится на место старого, запуск — вручную (старый ещё работает). */
    private fun replaceAppImage(file: File): Pair<Boolean, String> {
        val current = System.getenv("APPIMAGE")?.takeIf { it.isNotBlank() }?.let(::File)
        if (current == null || !current.canWrite() && !(current.parentFile?.canWrite() ?: false)) {
            return false to "AppImage скачан: ${file.absolutePath}. Замените им старый файл."
        }
        val next = File(current.parentFile, current.name + ".new")
        file.copyTo(next, overwrite = true)
        next.setExecutable(true)
        if (!next.renameTo(current)) return false to "AppImage скачан: ${next.absolutePath}. Замените им старый файл."
        return true to "Hydra обновлена. Запустите её снова."
    }
}
