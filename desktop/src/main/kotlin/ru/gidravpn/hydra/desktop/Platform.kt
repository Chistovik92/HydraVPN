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

    /**
     * Архитектура процессора в терминах имён файлов релиза: x64, arm64, x86 (32-бит Intel/AMD), armv7 (32-бит ARM).
     * 32-битная JVM на 64-битной ОС даёт x86 — для неё ядра берутся 32-битные, как и сама Hydra.
     */
    val arch: String = archOf(System.getProperty("os.arch") ?: "")

    /** Classic — сборка на Swing для систем, где Compose не работает (32-бит, Windows 7); флаг ставит скрипт запуска. */
    val classic: Boolean = System.getProperty("hydra.classic") == "true"

    fun archOf(osArch: String): String = when (osArch.lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "arm", "armv7", "armv7l", "armv8l", "armhf", "aarch32" -> "armv7"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> "x64"
    }

    /** Как устройство называется в боте при подключении: «Hydra Windows · имя-компьютера». */
    fun deviceName(): String {
        val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: ""
        val name = when (os) { Os.WINDOWS -> "Windows"; Os.MACOS -> "macOS"; Os.LINUX -> "Linux" }
        return ("Hydra $name" + if (host.isNotEmpty()) " · $host" else "").take(40)
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

    /** Клиент OpenFlux (готовый бинарь из релиза апстрима) и клиент olcRTC (собран из закреплённого коммита) — 0.7.4. */
    fun bundledOpenFlux(): File? = if (goClientsSupported) bundled("openflux", "HYDRA_OPENFLUX") else null
    fun bundledOlcRtc(): File? = if (goClientsSupported) bundled("olcrtc", "HYDRA_OLCRTC") else null

    /** ByeDPI (`ciadpi`, MIT) — обход DPI (0.7.13); готовый бинарь релиза или сборка из исходников метки. */
    fun bundledByeDpi(): File? = bundled("ciadpi", "HYDRA_BYEDPI")

    /** Скачанные geo-базы (0.7.13): имеют приоритет над вшитыми из [geoDir]. */
    val geoStore: ru.gidravpn.hydra.data.geo.GeoStore by lazy { ru.gidravpn.hydra.data.geo.GeoStore(File(dataDir, "geo")) }

    /**
     * Клиенты olcRTC и OpenFlux собраны современным Go, а он не поддерживает Windows 7 / 8.0 (нужна 8.1+ — NT 6.3).
     * В Hydra Classic для Windows они лежат в архиве (тот же архив и для Windows 10 x86), но на старой системе не предлагаются (0.7.10).
     */
    val goClientsSupported: Boolean by lazy {
        if (os != Os.WINDOWS) return@lazy true
        val v = System.getProperty("os.version").orEmpty().split('.').mapNotNull { it.toIntOrNull() }
        val major = v.getOrNull(0) ?: 10
        val minor = v.getOrNull(1) ?: 0
        major > 6 || (major == 6 && minor >= 3)
    }

    private fun bundled(name: String, env: String): File? {
        // Переопределение через переменную — только для разработки: при запуске от администратора подмена исполняемого файла недопустима.
        if (!ru.gidravpn.hydra.desktop.core.Elevation.admin) System.getenv(env)?.let { File(it) }?.takeIf { it.isFile }?.let { return it }
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

    /** Настоящий исполняемый файл процесса: лаунчер jpackage, а в `gradle run` и в Classic — java. */
    val processExecutable: String? by lazy { ProcessHandle.current().info().command().orElse(null) }

    /** Classic: сценарий запуска (Hydra.vbs / hydra.sh) — его передаёт скрипт запуска; по нему Hydra перезапускает сама себя. */
    val classicLauncher: File? = System.getProperty("hydra.launcher")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { classic && it.isFile }

    /** Путь к тому, чем Hydra запускается: лаунчер jpackage, сценарий Classic или java в `gradle run`. */
    val selfExecutable: String? by lazy { classicLauncher?.absolutePath ?: processExecutable }

    /** Каталог с geoip/ и geosite/ (*.srs); null — баз нет, geo-маршрутизация недоступна. */
    fun geoDir(): File? {
        System.getProperty("hydra.geoDir")?.let(::File)?.takeIf { File(it, "geoip").isDirectory }?.let { return it }
        return resourcesDir?.let { File(it, "geo") }?.takeIf { File(it, "geoip").isDirectory }
    }
}
