package ru.gidravpn.hydra.desktop

import java.io.File

enum class Os { WINDOWS, LINUX, MACOS }

/** Всё, что зависит от ОС и места установки: каталоги данных, ядро, geo-базы. */
object Platform {

    val os: Os = System.getProperty("os.name").lowercase().let {
        when {
            "win" in it -> Os.WINDOWS
            "mac" in it || "darwin" in it -> Os.MACOS
            else -> Os.LINUX
        }
    }

    val version: String = System.getProperty("hydra.version") ?: "dev"

    /** %APPDATA%\Hydra, ~/Library/Application Support/Hydra, $XDG_CONFIG_HOME/hydra. */
    val dataDir: File by lazy {
        val home = System.getProperty("user.home")
        val dir = when (os) {
            Os.WINDOWS -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "Hydra")
            Os.MACOS -> File(home, "Library/Application Support/Hydra")
            Os.LINUX -> File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config", "hydra")
        }
        dir.apply { mkdirs() }
    }

    val runDir: File get() = File(dataDir, "run").apply { mkdirs() }

    /**
     * Каталог ресурсов пакета (Compose кладёт сюда common/ + <os>-<arch>/ из
     * build/hydra-app-resources). В `gradle run` — build/compose/tmp/.../resources.
     */
    private val resourcesDir: File? =
        System.getProperty("compose.application.resources.dir")?.let(::File)?.takeIf { it.isDirectory }

    private val coreName = if (os == Os.WINDOWS) "sing-box.exe" else "sing-box"

    /** Ядро из пакета; HYDRA_SING_BOX — ручное переопределение (разработка). */
    fun bundledCore(): File? {
        System.getenv("HYDRA_SING_BOX")?.let { File(it) }?.takeIf { it.isFile }?.let { return it }
        val core = resourcesDir?.let { File(it, coreName) }?.takeIf { it.isFile } ?: return null
        if (os == Os.WINDOWS || core.canExecute() || core.setExecutable(true)) return core
        // Пакет без бита исполнения на read-only носителе (AppImage/DMG): копия в каталог данных.
        val copy = File(dataDir, "bin/$coreName").apply { parentFile.mkdirs() }
        if (!copy.isFile || copy.length() != core.length()) core.copyTo(copy, overwrite = true)
        copy.setExecutable(true, true)
        return copy
    }

    /** Каталог с geoip/ и geosite/ (*.srs); null — баз нет, geo-маршрутизация недоступна. */
    fun geoDir(): File? {
        System.getProperty("hydra.geoDir")?.let(::File)?.takeIf { File(it, "geoip").isDirectory }?.let { return it }
        return resourcesDir?.let { File(it, "geo") }?.takeIf { File(it, "geoip").isDirectory }
    }
}
