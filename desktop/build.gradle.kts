import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URI
import java.security.MessageDigest
import javax.imageio.ImageIO

// Hydra Desktop (Windows / Linux / macOS): Compose Desktop UI + ядра sing-box и
// Xray-core отдельными процессами. Данные/парсеры/конфиг sing-box — из :shared.
//
// Код лежит в src/main/kotlin и src/test/kotlin. Каталоги src/commonMain,
// src/desktopMain, src/desktopTest — заготовка 1936f1d (консольный «Core Test»,
// ручные маршруты netsh/nftables/pfctl) — этим плагином НЕ компилируются.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// ---- Версия: один источник — versionName в app/build.gradle.kts ---------------
val appVersion: String = Regex("versionName\\s*=\\s*\"([^\"]+)\"")
    .find(rootProject.file("app/build.gradle.kts").readText())!!.groupValues[1]

// jpackage принимает только MAJOR.MINOR.PATCH (0.6.22.2 → 0.6.22).
val pkgVersion: String = appVersion.split('.').take(3)
    .map { part -> part.filter(Char::isDigit).ifEmpty { "0" } }
    .let { it + List(3 - it.size) { "0" } }
    .joinToString(".")

// macOS (DMG/CFBundleVersion) требует MAJOR > 0: 0.X.Y → 1.X.Y. Порядок версий
// сохраняется; настоящая версия видна в самом приложении (hydra.version).
val macPkgVersion: String = pkgVersion.split('.').let { (a, b, c) -> "${a.toInt() + 1}.$b.$c" }

// ---- Ядро sing-box -------------------------------------------------------------
// Та же версия, что libbox в Android и Libbox.xcframework в iOS (ios.yml).
// SHA-256 архивов с https://github.com/SagerNet/sing-box/releases/tag/v1.12.9 .
val singBoxVersion = "1.12.9"

data class CoreArchive(val target: String, val archive: String, val sha256: String) {
    val isZip get() = archive.endsWith(".zip")
    val exe get() = if (target.startsWith("windows")) "sing-box.exe" else "sing-box"
}

val coreArchives = listOf(
    CoreArchive("windows-x64", "windows-amd64.zip", "f9f9b55d394fe08a8afe1fd3bb1c037e687a12fbe701f16481be6941d1766840"),
    CoreArchive("windows-arm64", "windows-arm64.zip", "4a490b0d114e0ae4c7e154232860cad7b9897bfcd9324d40b07a48dae90eb1d0"),
    CoreArchive("linux-x64", "linux-amd64.tar.gz", "519bc521e6b25f779b37738c5fca0fa3f68175b3d8e434fcacd8ea42da9e70ef"),
    CoreArchive("linux-arm64", "linux-arm64.tar.gz", "0d571bf961c651cc5a4eaffe9715d7759edb484b90473f3fa25aaab87fa11961"),
    CoreArchive("macos-x64", "darwin-amd64.tar.gz", "1657fb9fd356bc17d4b657052db93a0741547348070e605ed2553a067281fd8b"),
    CoreArchive("macos-arm64", "darwin-arm64.tar.gz", "d37141302f0c9e1ea5a2f071e78146961a7b4f9045feaafee51d8b3535c6ff0b"),
)

/** Цель текущей машины в терминах каталогов ресурсов Compose (<os>-<arch>). */
fun hostTarget(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val o = when {
        "win" in os -> "windows"
        "mac" in os || "darwin" in os -> "macos"
        else -> "linux"
    }
    val a = if (arch == "aarch64" || arch == "arm64") "arm64" else "x64"
    return "$o-$a"
}

val coreCacheDir = layout.buildDirectory.dir("sing-box-cache")
val appResourcesDir = layout.buildDirectory.dir("hydra-app-resources")

/**
 * Скачивает (с проверкой SHA-256) и распаковывает sing-box для текущей ОС/архитектуры
 * в <appResources>/<os>-<arch>/. Compose Desktop кладёт в пакет только каталог своей
 * платформы, поэтому каждый MSI/DMG/DEB получает ровно одно ядро.
 */
val downloadSingBox by tasks.registering {
    group = "hydra"
    val target = hostTarget()
    val core = coreArchives.first { it.target == target }
    val outDir = appResourcesDir.map { it.dir(target) }
    outputs.dir(outDir)
    inputs.property("core", core.toString())
    doLast {
        val cache = coreCacheDir.get().asFile.apply { mkdirs() }
        val archiveName = "sing-box-$singBoxVersion-${core.archive}"
        val file = cache.resolve(archiveName)
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (!file.exists() || sha(file) != core.sha256) {
            val url = "https://github.com/SagerNet/sing-box/releases/download/v$singBoxVersion/$archiveName"
            logger.lifecycle("Скачиваю $url")
            URI(url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val actual = sha(file)
        check(actual == core.sha256) { "SHA-256 $archiveName не совпал: $actual (ожидался ${core.sha256})" }
        val dest = outDir.get().asFile
        project.copy {
            from(if (core.isZip) zipTree(file) else tarTree(resources.gzip(file)))
            include("**/${core.exe}", "**/LICENSE")
            eachFile { path = if (name == "LICENSE") "LICENSE-sing-box" else name }
            includeEmptyDirs = false
            into(dest)
        }
        dest.resolve(core.exe).setExecutable(true, false)
    }
}

// ---- Ядро Xray-core (второй движок, как на Android) ------------------------------
// Последний стабильный (не pre-release) релиз XTLS/Xray-core. SHA-256 — дайджесты
// ассетов релиза https://github.com/XTLS/Xray-core/releases/tag/v26.3.27 .
val xrayVersion = "26.3.27"

val xrayArchives = mapOf(
    "windows-x64" to ("windows-64" to "d004c39288ce9ada487c6f398c7c545f7d749e44bdfdd59dbc9f865afba4e1ad"),
    "windows-arm64" to ("windows-arm64-v8a" to "35d4ed6ec21224fb22b07c2c3f672e2350cd536f2c74d309150175a76365ea88"),
    "linux-x64" to ("linux-64" to "23cd9af937744d97776ee35ecad4972cf4b2109d1e0fe6be9930467608f7c8ae"),
    "linux-arm64" to ("linux-arm64-v8a" to "4d30283ae614e3057f730f67cd088a42be6fdf91f8639d82cb69e48cde80413c"),
    "macos-x64" to ("macos-64" to "f5b0471d3459eff1b82e48af0aeac186abcc3298210070afbbbd8437a4e8b203"),
    "macos-arm64" to ("macos-arm64-v8a" to "2e93a67e8aa1936ecefb307e120830fcbd4c643ab9b1c46a2d0838d5f8409eaf"),
)

/** Xray-core своей платформы в тот же каталог ресурсов, что и sing-box. */
val downloadXray by tasks.registering {
    group = "hydra"
    val target = hostTarget()
    val (suffix, sha256) = xrayArchives.getValue(target)
    val exe = if (target.startsWith("windows")) "xray.exe" else "xray"
    val outDir = appResourcesDir.map { it.dir(target) }
    outputs.dir(outDir)
    inputs.property("xray", "$xrayVersion-$suffix-$sha256")
    doLast {
        val cache = coreCacheDir.get().asFile.apply { mkdirs() }
        val file = cache.resolve("Xray-$xrayVersion-$suffix.zip")
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (!file.exists() || sha(file) != sha256) {
            val url = "https://github.com/XTLS/Xray-core/releases/download/v$xrayVersion/Xray-$suffix.zip"
            logger.lifecycle("Скачиваю $url")
            URI(url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val actual = sha(file)
        check(actual == sha256) { "SHA-256 ${file.name} не совпал: $actual (ожидался $sha256)" }
        val dest = outDir.get().asFile
        project.copy {
            from(zipTree(file))
            include(exe, "LICENSE")
            eachFile { path = if (name == "LICENSE") "LICENSE-xray" else name }
            includeEmptyDirs = false
            into(dest)
        }
        dest.resolve(exe).setExecutable(true, false)
    }
}

/** geoip/geosite .srs — те же базы, что в Android (app/src/main/assets). */
val syncGeoAssets by tasks.registering(Sync::class) {
    group = "hydra"
    from(rootProject.file("app/src/main/assets")) { include("geoip/*.srs", "geosite/*.srs") }
    into(appResourcesDir.map { it.dir("common/geo") })
}

val hydraAppResources by tasks.registering {
    group = "hydra"
    dependsOn(downloadSingBox, downloadXray, syncGeoAssets)
}

// ---- Иконки: PNG/ICO/ICNS из одной 1024px-иконки iOS ---------------------------
val iconSource = rootProject.file("ios/Hydra/Assets.xcassets/AppIcon.appiconset/icon-1024.png")
val iconsDir = layout.buildDirectory.dir("icons")

val generateIcons by tasks.registering {
    group = "hydra"
    inputs.file(iconSource)
    outputs.dir(iconsDir)
    doLast {
        val src = ImageIO.read(iconSource)
        fun png(size: Int): ByteArray {
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            img.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(src, 0, 0, size, size, null)
                dispose()
            }
            return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        }
        val dir = iconsDir.get().asFile.apply { mkdirs() }
        dir.resolve("hydra.png").writeBytes(png(512))

        // ICO с PNG внутри (Vista+): ICONDIR + ICONDIRENTRY на каждый размер.
        val icoSizes = listOf(16, 32, 48, 256)
        val icoImages = icoSizes.map { png(it) }
        val ico = ByteArrayOutputStream()
        fun le16(v: Int) { ico.write(v and 0xff); ico.write((v shr 8) and 0xff) }
        fun le32(v: Int) { le16(v and 0xffff); le16((v shr 16) and 0xffff) }
        le16(0); le16(1); le16(icoSizes.size)
        var offset = 6 + 16 * icoSizes.size
        icoSizes.forEachIndexed { i, s ->
            ico.write(if (s >= 256) 0 else s); ico.write(if (s >= 256) 0 else s)
            ico.write(0); ico.write(0); le16(1); le16(32)
            le32(icoImages[i].size); le32(offset)
            offset += icoImages[i].size
        }
        icoImages.forEach { ico.write(it) }
        dir.resolve("hydra.ico").writeBytes(ico.toByteArray())

        // ICNS с PNG-элементами: ic07=128, ic08=256, ic09=512, ic10=1024.
        val entries = listOf("ic07" to 128, "ic08" to 256, "ic09" to 512, "ic10" to 1024)
            .map { (type, size) -> type to png(size) }
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { out ->
            entries.forEach { (type, data) -> out.writeBytes(type); out.writeInt(data.size + 8); out.write(data) }
        }
        val icns = ByteArrayOutputStream()
        DataOutputStream(icns).use { out -> out.writeBytes("icns"); out.writeInt(body.size() + 8); out.write(body.toByteArray()) }
        dir.resolve("hydra.icns").writeBytes(icns.toByteArray())
    }
}

// ---- Зависимости ---------------------------------------------------------------
dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.processResources {
    from(iconSource) { rename { "hydra-icon.png" } }
}

tasks.test {
    // Тесты DesktopConfigTest пишут конфиги сюда; sing-box `check` по ним — в CI.
    systemProperty("hydra.configDump", layout.buildDirectory.dir("singbox-configs").get().asFile.absolutePath)
    systemProperty("hydra.geoDir", rootProject.file("app/src/main/assets").absolutePath)
}

compose.desktop {
    application {
        mainClass = "ru.gidravpn.hydra.desktop.MainKt"
        jvmArgs += listOf("-Dhydra.version=$appVersion", "-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg)
            packageName = "Hydra"
            packageVersion = pkgVersion
            description = "Hydra VPN client"
            vendor = "Hydra VPN"
            copyright = "GPL-3.0"
            licenseFile.set(rootProject.file("LICENSE"))
            appResourcesRootDir.set(appResourcesDir)
            // Модули JRE сверх java.base/java.desktop: OkHttp (logging, TLS с EC-шифрами),
            // JNA (jdk.unsupported), Compose (management/naming/accessibility).
            modules("java.logging", "java.naming", "java.management", "java.net.http",
                "jdk.crypto.ec", "jdk.unsupported", "jdk.accessibility")

            windows {
                iconFile.set(iconsDir.map { it.file("hydra.ico") })
                menuGroup = "Hydra"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Постоянный UUID: без него MSI новой версии не заменяет старую.
                upgradeUuid = "6f1d0c0e-5c7b-4b8e-9a44-2d6f0b9e1a37"
            }
            linux {
                iconFile.set(iconsDir.map { it.file("hydra.png") })
                packageName = "hydra-vpn"
                debMaintainer = "Hydra VPN <dev@shadowlink.local>"
                menuGroup = "Network"
                appCategory = "Network"
                rpmLicenseType = "GPL-3.0-or-later"
                shortcut = true
            }
            macOS {
                iconFile.set(iconsDir.map { it.file("hydra.icns") })
                bundleID = "ru.gidravpn.hydra.desktop"
                packageVersion = macPkgVersion
                dmgPackageVersion = macPkgVersion
            }
        }
    }
}

// Ресурсы (ядро + geo) и иконки нужны и `run`, и всем задачам упаковки.
tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(hydraAppResources)
    // Compose копирует ресурсы без бита исполнения — ядро в пакете было бы «Permission denied».
    (this as? AbstractCopyTask)?.eachFile {
        if (name == "sing-box" || name == "xray") permissions { unix("rwxr-xr-x") }
    }
}
tasks.matching { it.name.startsWith("package") || it.name.startsWith("createDistributable") || it.name.startsWith("createReleaseDistributable") }
    .configureEach { dependsOn(generateIcons) }

// jpackage кладёт ресурсы в образ приложения без бита исполнения. Ставим его в готовом
// образе: из него же собираются DEB/RPM (--app-image) и DMG в scripts/package-desktop.sh.
tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    doLast {
        outputs.files.asFileTree.matching { include("**/resources/sing-box", "**/resources/xray") }.forEach { it.setExecutable(true, false) }
    }
}
