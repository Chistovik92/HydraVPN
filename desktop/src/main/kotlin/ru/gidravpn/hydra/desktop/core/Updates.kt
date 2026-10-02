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

    fun latest(): Release {
        val info = UpdateFeed.latest("Hydra-desktop-update-check")
        return Release(info.version, info.pageUrl, pick(info, Platform.os, arch(), installKind()))
    }

    fun isNewer(remote: String, current: String): Boolean = UpdateFeed.isNewer(remote, current)

    /** Что скачивать: [kind] — как Hydra установлена на этой машине. */
    fun pick(info: ReleaseInfo, os: Os, arch: String, kind: InstallKind): ReleaseAsset? = when (os) {
        Os.WINDOWS -> UpdateFeed.pickDesktop(info, "windows", arch, when (kind) {
            InstallKind.PORTABLE -> listOf("zip")
            else -> listOf("msi", "exe")
        })
        Os.MACOS -> UpdateFeed.pickDesktop(info, "macos", arch, listOf("dmg"))
        Os.LINUX -> UpdateFeed.pickDesktop(info, "linux", arch, when (kind) {
            InstallKind.APPIMAGE -> listOf("AppImage")
            InstallKind.DEB -> listOf("deb")
            InstallKind.RPM -> listOf("rpm")
            else -> listOf("tar.gz")
        })
    }

    enum class InstallKind { INSTALLED, PORTABLE, APPIMAGE, DEB, RPM, ARCHIVE }

    fun arch(): String = System.getProperty("os.arch").lowercase().let { if (it == "aarch64" || it == "arm64") "arm64" else "x64" }

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
        return when {
            n.endsWith(".msi") -> { ProcessBuilder("msiexec", "/i", file.absolutePath).start(); true to "Запущен установщик — Hydra закроется." }
            n.endsWith(".exe") -> { ProcessBuilder(file.absolutePath).start(); true to "Запущен установщик — Hydra закроется." }
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
