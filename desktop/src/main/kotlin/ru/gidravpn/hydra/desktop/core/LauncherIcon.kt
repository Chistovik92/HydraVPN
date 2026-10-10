package ru.gidravpn.hydra.desktop.core

import ru.gidravpn.hydra.desktop.AppIcon
import ru.gidravpn.hydra.desktop.AppTheme
import ru.gidravpn.hydra.desktop.Os
import ru.gidravpn.hydra.desktop.Platform
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Иконка не только в окне, но и у ярлыков (0.7.14): окно, панель задач и трей Hydra рисует сама, а «Пуск», рабочий стол и меню
 * приложений берут значок из ярлыка — его приходится переписывать. Всё делается по возможности и без прав администратора:
 *  - Windows — обычные `.lnk` на рабочем столе и в «Пуске» пользователя, которые указывают на Hydra;
 *  - Linux — копия `.desktop`-файла Hydra в `~/.local/share/applications` с другим `Icon=`;
 *  - macOS — значок в Dock (`java.awt.Taskbar`).
 * Закреплённые на панели задач ярлыки Windows хранит Проводник, их приложение не трогает.
 */
object LauncherIcon {
    private val marker get() = File(Platform.runDir, "launcher-icon")

    /** Применяет значок, если он отличается от уже применённого; ошибки не мешают работе. */
    fun apply(icon: AppIcon, theme: AppTheme) {
        val res = icon.resource(theme)
        // Dock macOS сбрасывается при каждом запуске — его ставим всегда; ярлыки и .desktop — только при смене.
        if (Platform.os != Os.MACOS && runCatching { marker.readText() }.getOrNull() == res) return
        val ok = runCatching {
            val png = LauncherIcon::class.java.getResourceAsStream(res)?.use { it.readBytes() } ?: return
            when (Platform.os) {
                Os.WINDOWS -> windows(res, png)
                Os.LINUX -> linux(res, png)
                Os.MACOS -> macos(png)
            }
        }.isSuccess
        if (ok) runCatching { marker.writeText(res) }
    }

    /** ICO с одним PNG внутри (Vista+). */
    internal fun icoOf(png: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        fun le16(v: Int) { o.write(v and 0xff); o.write((v shr 8) and 0xff) }
        fun le32(v: Int) { le16(v and 0xffff); le16((v shr 16) and 0xffff) }
        le16(0); le16(1); le16(1)
        o.write(0); o.write(0); o.write(0); o.write(0); le16(1); le16(32)
        le32(png.size); le32(22)
        o.write(png)
        return o.toByteArray()
    }

    private fun windows(res: String, png: ByteArray) {
        val dir = File(Platform.dataDir, "icons").apply { mkdirs() }
        // Своё имя на каждый вариант: Проводник кэширует значок по пути.
        val ico = File(dir, "hydra-" + res.substringAfterLast('/').removeSuffix(".png") + ".ico").apply { writeBytes(icoOf(png)) }
        val script = """
            ${'$'}ico = '${ico.absolutePath.replace("'", "''")}'
            ${'$'}sh = New-Object -ComObject WScript.Shell
            ${'$'}dirs = @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))
            foreach (${'$'}d in ${'$'}dirs) {
              if (-not (Test-Path ${'$'}d)) { continue }
              Get-ChildItem -Path ${'$'}d -Filter *.lnk -Recurse -ErrorAction SilentlyContinue | ForEach-Object {
                ${'$'}l = ${'$'}sh.CreateShortcut(${'$'}_.FullName)
                if (${'$'}l.TargetPath -match '(?i)\\Hydra[^\\]*\.exe${'$'}') { ${'$'}l.IconLocation = ${'$'}ico; ${'$'}l.Save() }
              }
            }
        """.trimIndent()
        val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor()
    }

    private fun linux(res: String, png: ByteArray) {
        val icon = File(Platform.dataDir, "icons").apply { mkdirs() }.resolve("hydra-" + res.substringAfterLast('/')).apply { writeBytes(png) }
        val home = System.getProperty("user.home")
        val target = File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "applications").apply { mkdirs() }
        val roots = listOf(File("/usr/share/applications"), File("/opt/hydra/lib"), File("/opt/Hydra/lib"))
        roots.flatMap { r -> r.listFiles { f -> f.isFile && f.name.endsWith(".desktop") && f.name.contains("ydra") }?.toList().orEmpty() }.forEach { src ->
            val text = src.readText()
            val out = if (Regex("(?m)^Icon=").containsMatchIn(text)) text.replace(Regex("(?m)^Icon=.*$"), "Icon=" + icon.absolutePath) else text + "\nIcon=${icon.absolutePath}\n"
            File(target, src.name).writeText(out)
        }
    }

    private fun macos(png: ByteArray) {
        if (!java.awt.Taskbar.isTaskbarSupported()) return
        val tb = java.awt.Taskbar.getTaskbar()
        if (tb.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) tb.iconImage = javax.imageio.ImageIO.read(png.inputStream())
    }
}
