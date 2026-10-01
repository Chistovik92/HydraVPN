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

    /** Как устройство называется в боте при подключении: «Hydra Windows · имя-компьютера». */
    fun deviceName(): String {
        val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: ""
        val name = when (os) { Os.WINDOWS -> "Windows"; Os.MACOS -> "macOS"; Os.LINUX -> "Linux" }
        return ("Hydra $name" + if (host.isNotEmpty()) " · $host" else "").take(60)
    }

    /** %APPDATA%\Hydra, ~/Library/Application Support/Hydra, $XDG_CONFIG_HOME/hydra. */
    val dataDir: File by lazy {
        val home = System.getProperty("user.home")
        val dir = when (os) {
            Os.WINDOWS -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "Hydra")
            Os.MACOS -> File(home, "Library/Application Support/Hydra")
            Os.LINUX -> File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config", "hydra")
        }
        dir.apply { mkdirs(); privateDir(this) }
    }

    /** Конфиги с паролями, pid-файлы, лог ядра — каталог только для владельца. */
    val runDir: File get() = File(dataDir, "run").apply { mkdirs(); privateDir(this) }

    /** chmod 700 (Linux/macOS); на Windows %APPDATA% и так закрыт для других пользователей. */
    fun privateDir(dir: File) {
        if (os == Os.WINDOWS) return
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(dir.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }
    }

    /**
     * Каталог ресурсов пакета (Compose кладёт сюда common/ + <os>-<arch>/ из
     * build/hydra-app-resources). В `gradle run` — build/compose/tmp/.../resources.
     */
    private val resourcesDir: File? =
        System.getProperty("compose.application.resources.dir")?.let(::File)?.takeIf { it.isDirectory }

    private fun exe(name: String) = if (os == Os.WINDOWS) "$name.exe" else name

    /** Ядро sing-box из пакета; HYDRA_SING_BOX — ручное переопределение (разработка). */
    fun bundledCore(): File? = bundled("sing-box", "HYDRA_SING_BOX")

    /** Ядро Xray-core из пакета; HYDRA_XRAY — ручное переопределение (разработка). */
    fun bundledXray(): File? = bundled("xray", "HYDRA_XRAY")

    private fun bundled(name: String, env: String): File? {
        System.getenv(env)?.let { File(it) }?.takeIf { it.isFile }?.let { return it }
        val file = exe(name)
        val core = resourcesDir?.let { File(it, file) }?.takeIf { it.isFile } ?: return null
        if (os == Os.WINDOWS || core.canExecute() || core.setExecutable(true)) return core
        // Пакет без бита исполнения на read-only носителе (AppImage/DMG): копия в каталог данных.
        val bin = File(dataDir, "bin").apply { mkdirs() }
        val copy = File(bin, file)
        if (!copy.isFile || copy.length() != core.length()) core.copyTo(copy, overwrite = true)
        copy.setExecutable(true, true)
        return copy
    }

    /** Путь к исполняемому файлу самого Hydra (лаунчер jpackage или java в `gradle run`). */
    val selfExecutable: String? by lazy { ProcessHandle.current().info().command().orElse(null) }

    /** Каталог с geoip/ и geosite/ (*.srs); null — баз нет, geo-маршрутизация недоступна. */
    fun geoDir(): File? {
        System.getProperty("hydra.geoDir")?.let(::File)?.takeIf { File(it, "geoip").isDirectory }?.let { return it }
        return resourcesDir?.let { File(it, "geo") }?.takeIf { File(it, "geoip").isDirectory }
    }
}
