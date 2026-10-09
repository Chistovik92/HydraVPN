import org.jetbrains.kotlin.gradle.dsl.JvmTarget as KJvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
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
// SHA-256 архивов с https://github.com/SagerNet/sing-box/releases/tag/v1.12.25 .
val singBoxVersion = "1.12.25"

data class CoreArchive(val target: String, val archive: String, val sha256: String) {
    val isZip get() = archive.endsWith(".zip")
    val exe get() = if (target.startsWith("windows")) "sing-box.exe" else "sing-box"
}

val coreArchives = listOf(
    CoreArchive("windows-x64", "windows-amd64.zip", "492df6eb97c4c749d45350d792ecece71278d83c4979149ba6a142765b8fc1d4"),
    CoreArchive("windows-arm64", "windows-arm64.zip", "5c722c7e3ff23fbb0f09a49dce10726b3e4f39e4bc4bf17d2826fbb6b990ef7a"),
    CoreArchive("linux-x64", "linux-amd64.tar.gz", "a1ec76e2b6b139eb747a1b1ebee7d14b8d4be5a833596cad8070a31ef960301f"),
    CoreArchive("linux-arm64", "linux-arm64.tar.gz", "719b76196c8b31efa636b2d8f669e314547e0da0a5ab38a75e1882d307bbd154"),
    CoreArchive("macos-x64", "darwin-amd64.tar.gz", "fb9cb2a1d3160b9ef4a288818286ad27711907228232b8d84410cad7e42726d8"),
    CoreArchive("macos-arm64", "darwin-arm64.tar.gz", "a4a06d507f3f4d951490168d1372fce4c02db7211e88af9da13f93ed98068d5e"),
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
// 26.9.30 — пре-релиз по меткам апстрима, но тот же коммит (b26a91d), что собран в libXray.aar на Android,
// так что на всех платформах один и тот же Xray. Стабильный 26.9.30 отстаёт на полгода, а панели (3x-ui 3.9,
// PasarGuard и др.) выдают настройки под свежий Xray. SHA-256 — дайджесты ассетов релиза
// https://github.com/XTLS/Xray-core/releases/tag/v26.9.30 .
val xrayVersion = "26.9.30"

val xrayArchives = mapOf(
    "windows-x64" to ("windows-64" to "b17a619343c11b89d8faf36278298856749b15c52277d875d32051b33dcda617"),
    "windows-arm64" to ("windows-arm64-v8a" to "5cf7ad4fd9aac5aa248ae7e598f3f0a0d0f79a6195089d611ddef9653830f1b0"),
    "linux-x64" to ("linux-64" to "f851110beaff16e78d643f0ccfd9524b4a44dfd59bae3e34bb52bba378f7690e"),
    "linux-arm64" to ("linux-arm64-v8a" to "9886f077f9fd8e6713b84c377c1c7db4e53b9bfa8c276a5bd12561139522b473"),
    "macos-x64" to ("macos-64" to "1f366aaf21d3c3003d1556d066c4e08f9dd62d1f96555ecedde85b397df0f684"),
    "macos-arm64" to ("macos-arm64-v8a" to "4b363bd924df5bf09f87bd445755ff8e5742b9a8a0480cda261061416c3c7dce"),
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


// ---- olcRTC и OpenFlux (0.7.4, BETA): клиенты-подпроцессы -------------------------
// OpenFlux — готовые бинари из релиза апстрима (GPL-3.0), SHA-256 закреплены (SHA256SUMS.txt релиза v0.4.2).
// 04.10.2026 апстрим переставил метку v0.3.0 на новый коммит bb55dc3 («Boards, mailru and yandex: the new native transports»)
// и перезалил все бинари — хеши обновлены; Android собирается из того же коммита (scripts/build-openflux.sh).
val openFluxVersion = "0.4.2"
val openFluxBinaries = mapOf(
    "windows-x64" to ("openflux-windows-amd64.exe" to "c6b4b2db082099aba88a1dbf28b93defd62e8759351f7204aaa0c6eb7340f520"),
    "windows-arm64" to ("openflux-windows-arm64.exe" to "dbf1b33b6d38fbab59d12a1dd3ee9912620d44313c968aaa5aaaae8390a55a8f"),
    "linux-x64" to ("openflux-linux-amd64" to "c51e82c1dc9c74b1fd6b264ee25533fb5d885e3e8bea451fb8920245ac1bf2c1"),
    "linux-arm64" to ("openflux-linux-arm64" to "5e69a7ce3160684cf7e4c51592cb71084ef56819407b556478bb4abc75156427"),
    "macos-x64" to ("openflux-darwin-amd64" to "f20268215c8952698b424824180f9f1bd24cc789163fb09a620d780b751367b7"),
    "macos-arm64" to ("openflux-darwin-arm64" to "c2c41fadbfbb2eeba8bfa22e89787e0cb9b796045cf09a6ed2e401320ff778e0"),
)

val downloadOpenFlux by tasks.registering {
    group = "hydra"
    val target = hostTarget()
    val (asset, sha256) = openFluxBinaries.getValue(target)
    val exe = if (target.startsWith("windows")) "openflux.exe" else "openflux"
    val outDir = appResourcesDir.map { it.dir(target) }
    outputs.dir(outDir)
    inputs.property("openflux", "$openFluxVersion-$asset-$sha256")
    doLast {
        val cache = coreCacheDir.get().asFile.apply { mkdirs() }
        val file = cache.resolve("OpenFlux-$openFluxVersion-$asset")
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (!file.exists() || sha(file) != sha256) {
            val url = "https://github.com/p1neappleXpress/OpenFlux/releases/download/v$openFluxVersion/$asset"
            logger.lifecycle("Скачиваю $url")
            URI(url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val actual = sha(file)
        check(actual == sha256) { "SHA-256 ${file.name} не совпал: $actual (ожидался $sha256)" }
        val dest = outDir.get().asFile.apply { mkdirs() }
        file.copyTo(dest.resolve(exe), overwrite = true)
        dest.resolve(exe).setExecutable(true, false)
    }
}

// olcRTC — апстрим заархивирован и релизов не выпускает: клиент (`cmd/olcrtc`, чистый Go, WTFPL) собирается из закреплённого
// коммита. Нужен `go` (и git); без них клиент в пакет не попадает, а в приложении пункт olcRTC неактивен.
val olcRtcCommit = "f3ad8fb7c0d0fa981423f83269526fa94077c23d"
val buildOlcRtc by tasks.registering {
    group = "hydra"
    val target = hostTarget()
    val exe = if (target.startsWith("windows")) "olcrtc.exe" else "olcrtc"
    val outDir = appResourcesDir.map { it.dir(target) }
    val workDir = layout.buildDirectory.dir("olcrtc-src")
    outputs.dir(outDir)
    inputs.property("olcrtc", olcRtcCommit)
    doLast {
        fun run(dir: File, vararg cmd: String, env: Map<String, String> = emptyMap()) {
            val pb = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true)
            pb.environment().putAll(env)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "${cmd.joinToString(" ")}: $out" }
        }
        val haveTools = runCatching { run(workDir.get().asFile.apply { mkdirs() }, "go", "version"); run(workDir.get().asFile, "git", "--version") }.isSuccess
        if (!haveTools) {
            logger.warn("buildOlcRtc: нет go или git — клиент olcRTC в пакет не войдёт")
            return@doLast
        }
        val src = workDir.get().asFile
        if (!src.resolve(".git").isDirectory) {
            run(src, "git", "init", "-q")
            run(src, "git", "remote", "add", "origin", "https://github.com/openlibrecommunity/olcrtc.git")
        }
        run(src, "git", "fetch", "-q", "--depth", "1", "origin", olcRtcCommit)
        run(src, "git", "checkout", "-q", "--force", "FETCH_HEAD")
        val dest = outDir.get().asFile.apply { mkdirs() }
        // Сборка под ЭТУ платформу, без cgo — один и тот же результат на любой машине.
        run(src, "go", "build", "-trimpath", "-ldflags=-s -w", "-o", dest.resolve(exe).absolutePath, "./cmd/olcrtc", env = mapOf("CGO_ENABLED" to "0"))
        dest.resolve(exe).setExecutable(true, false)
    }
}

val hydraAppResources by tasks.registering {
    group = "hydra"
    dependsOn(downloadSingBox, downloadXray, downloadOpenFlux, buildOlcRtc, syncGeoAssets)
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

// Приватный DNS Hydra VPN (DoH с токеном в пути) — только для вошедших через бота. Токен — секрет и в
// публичный репозиторий не попадает: берётся из свойства Gradle `hydraPrivateDns` или переменной
// окружения HYDRA_PRIVATE_DNS (в CI — секрет репозитория). Пусто — пункт DNS в приложении скрыт.
val generateSecrets by tasks.registering {
    val out = layout.buildDirectory.dir("generated/secrets")
    val raw = (findProperty("hydraPrivateDns") as String?) ?: System.getenv("HYDRA_PRIVATE_DNS") ?: ""
    val value = raw.trim().filter { it.isLetterOrDigit() || it in ":/._-?=&%" }
    inputs.property("privateDns", value)
    outputs.dir(out)
    doLast {
        val f = out.get().file("ru/gidravpn/hydra/desktop/HydraSecrets.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package ru.gidravpn.hydra.desktop\n\n/** Сгенерировано сборкой (generateSecrets); не править. */\nobject HydraSecrets {\n    const val PRIVATE_DNS: String = \"$value\"\n}\n")
    }
}
kotlin.sourceSets.getByName("main").kotlin.srcDir(layout.buildDirectory.dir("generated/secrets"))
tasks.matching { it.name.startsWith("compileKotlin") || it.name.endsWith("SourcesJar") }.configureEach { dependsOn(generateSecrets) }

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
            // JNA (jdk.unsupported), Compose (management/naming/accessibility). java.net.http не нужен: всё идёт через OkHttp и HttpURLConnection.
            modules("java.logging", "java.naming", "java.management",
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
        if (name in setOf("sing-box", "xray", "openflux", "olcrtc")) permissions { unix("rwxr-xr-x") }
    }
}
tasks.matching { it.name.startsWith("package") || it.name.startsWith("createDistributable") || it.name.startsWith("createReleaseDistributable") }
    .configureEach { dependsOn(generateIcons) }

// jpackage кладёт ресурсы в образ приложения без бита исполнения. Ставим его в готовом
// образе: из него же собираются DEB/RPM (--app-image) и DMG в scripts/package-desktop.sh.
tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    doLast {
        outputs.files.asFileTree.matching { include("**/resources/sing-box", "**/resources/xray", "**/resources/openflux", "**/resources/olcrtc") }.forEach { it.setExecutable(true, false) }
    }
}

// Hydra Classic (0.7.5) — оболочка на Swing для систем, где Compose Desktop не работает:
//   • 32-битные Windows и Linux (x86, armv7) — у Skiko (графика Compose) нет 32-битных библиотек;
//   • Windows 7 (в том числе 64-битная) — Compose и Java 17 требуют новее.
// Это тот же AppController и те же ядра, что у обычной Hydra, только окно другое (src/lite/kotlin).
// Подключается из build.gradle.kts: apply(from = "classic.gradle.kts").
//
// Архивы собираются на любой ОС (Gradle сам скачивает ядра и JRE, jpackage не нужен):
//   gradle :desktop:classicDist            — все четыре
//   gradle :desktop:classicDistWindowsX86  — один
// Результат — build/classic/dist/Hydra-desktop-<версия>-<os>-<arch>-classic.(zip|tar.gz).


// ---- Исходники: весь код приложения, кроме Compose-интерфейса, плюс Swing-оболочка ----------------------------
val javaSourceSets = extensions.getByType<SourceSetContainer>()
val lite = javaSourceSets.create("lite")
extensions.getByType<KotlinJvmProjectExtension>().sourceSets.getByName("lite").kotlin.apply {
    srcDir("src/main/kotlin")
    srcDir(layout.buildDirectory.dir("generated/secrets"))
    exclude("ru/gidravpn/hydra/desktop/Main.kt", "ru/gidravpn/hydra/desktop/ui/**")
}
dependencies {
    add("liteImplementation", project(":shared"))
    add("liteImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    add("liteImplementation", "com.squareup.okhttp3:okhttp:4.12.0")
    add("liteImplementation", "org.json:json:20240303")
    add("liteImplementation", "net.java.dev.jna:jna-platform:5.14.0")
}
// Java 11 — самая новая, которая работает на Windows 7 и есть в 32-битной сборке Windows.
tasks.named<KotlinCompile>("compileLiteKotlin") {
    compilerOptions.jvmTarget.set(KJvmTarget.JVM_11)
    dependsOn("generateSecrets")
    // Плагин Compose-компилятора подключается ко всем компиляциям, а в Classic Compose нет вовсе — отключаем его здесь.
    pluginClasspath.setFrom(pluginClasspath.filter { !it.name.contains("compose", ignoreCase = true) })
}
tasks.named<JavaCompile>("compileLiteJava") {
    sourceCompatibility = "11"
    targetCompatibility = "11"
}
tasks.named<ProcessResources>("processLiteResources") {
    from(rootProject.file("ios/Hydra/Assets.xcassets/AppIcon.appiconset/icon-1024.png")) { rename { "hydra-icon.png" } }
    // Иконки тем (0.7.9) — общие с обычной Hydra.
    from("src/main/resources/icons") { into("icons") }
}

// ---- Загрузки ------------------------------------------------------------------------------------------------
val cacheDir = layout.buildDirectory.dir("classic-cache")

fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

/** Скачивает [url] в кэш и сверяет SHA-256; уже скачанный и совпавший файл не трогает. */
fun fetch(url: String, sha: String, name: String): File {
    val dir = cacheDir.get().asFile.apply { mkdirs() }
    val file = File(dir, name)
    if (!file.exists() || sha256(file) != sha) {
        logger.lifecycle("Скачиваю $url")
        var attempt = 0
        while (true) {
            try {
                URI(url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                break
            } catch (e: java.io.IOException) {
                // GitHub иногда отвечает 503/5xx на скачивание релизов — повторяем с паузой.
                if (++attempt >= 4) throw e
                logger.lifecycle("  не вышло (${e.message}), повтор $attempt…")
                Thread.sleep(3000L * attempt)
            }
        }
    }
    val actual = sha256(file)
    check(actual == sha) { "SHA-256 $name не совпал: $actual (ожидался $sha)" }
    return file
}


/** Один вид сборки Classic: какие ядра и какой JRE в неё входят. */
data class Classic(
    val id: String,                  // windows-x86
    val taskSuffix: String,
    val os: String,                  // windows | linux
    val arch: String,                // x86 | x64 | armv7 (так называется файл релиза)
    val singBoxAsset: String, val singBoxSha: String,
    val xrayAsset: String, val xraySha: String,
    /** Готовый клиент OpenFlux из релиза апстрима; null — для этой цели его нет (или он не запустится). */
    val openFlux: Pair<String, String>? = null,
    /** Сборка olcRTC из исходников: GOOS/GOARCH/GOARM; null — не входит. */
    val olcRtc: Triple<String, String, String>? = null,
    /** Сборка OpenFlux из закреплённого коммита (0.7.10) — там, где апстрим готового бинаря не выпускает. */
    val openFluxSrc: Triple<String, String, String>? = null,
    /** JRE, которая кладётся в архив (Windows). Linux берёт Java системы. */
    val jre: Triple<String, String, String>? = null,   // url, имя файла, sha256
)

val classicTargets = listOf(
    // Windows: ядра «legacy-windows-7» собраны патченым Go, который ещё работает на Windows 7; на новых системах тоже.
    // olcRTC и OpenFlux (0.7.10) собраны обычным Go и работают только с Windows 8.1/10: на Windows 7 Hydra Classic их
    // не предлагает (Platform.goClientsSupported), на 32-битной Windows 10 — работают.
    Classic("windows-x86", "WindowsX86", "windows", "x86",
        "sing-box-$singBoxVersion-windows-386-legacy-windows-7.zip", "e4024508be5616015353c893b0d3f2dd3c080617a750ef6bd64f8183107611c8",
        "Xray-win7-32.zip", "3d73134f74e119589cc117c8f45b4564a14417f9b74508cc48599f142ac3aa81",
        olcRtc = Triple("windows", "386", ""), openFluxSrc = Triple("windows", "386", ""),
        jre = Triple("https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.29%2B7/OpenJDK11U-jre_x86-32_windows_hotspot_11.0.29_7.zip",
            "OpenJDK11U-jre_x86-32_windows_hotspot_11.0.29_7.zip", "b747698a05a39391a58b9caac30310275e4e6bd9fef92d6c149cba310d91d2be")),
    Classic("windows-x64", "WindowsX64", "windows", "x64",
        "sing-box-$singBoxVersion-windows-amd64-legacy-windows-7.zip", "4f27f807f99198b2681a59c1f12564b3683331e68215344e75761ee9199a9b90",
        "Xray-win7-64.zip", "75e67c738fdfafb9649f1a81a7ab6b3ba97a0f2e09a44254bff03c5b077547d3",
        olcRtc = Triple("windows", "amd64", ""), openFluxSrc = Triple("windows", "amd64", ""),
        jre = Triple("https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.32.1%2B1/OpenJDK11U-jre_x64_windows_hotspot_11.0.32.1_1.zip",
            "OpenJDK11U-jre_x64_windows_hotspot_11.0.32.1_1.zip", "f8c7da672f5dba36b6f870608820b6b598cfae91296929f1b8f21ef2f1e8a0dd")),
    Classic("linux-x86", "LinuxX86", "linux", "x86",
        "sing-box-$singBoxVersion-linux-386.tar.gz", "b2361b5eb0ef6da8f068d9bba762b9edbb3a8637515e30251ee8d5d78b30d846",
        "Xray-linux-32.zip", "277ffde84d86cb593ae9c3d144b11a5e4c80ba579fdfe6fe09830e04c85d04aa",
        olcRtc = Triple("linux", "386", ""), openFluxSrc = Triple("linux", "386", "")),
    Classic("linux-armv7", "LinuxArmv7", "linux", "armv7",
        "sing-box-$singBoxVersion-linux-armv7.tar.gz", "9eedd8eb2d3ea66a48794359d1cc1e32ce618b6e1b105c9cd314da6408c9fc87",
        "Xray-linux-arm32-v7a.zip", "0b9719471c7c69752857714e9711d4da57cf38a6beb75dcddfb21425f7919908",
        openFlux = "openflux-linux-arm" to "489387fd9bd8eed248933b147ed8f9964d61188baae5f9d9f6a06b6b810214f7",
        olcRtc = Triple("linux", "arm", "7")),
)

// ---- olcRTC и OpenFlux: кросс-сборка из закреплённых коммитов (чистый Go, без cgo) ---------------------------------
fun buildOlcRtc(goos: String, goarch: String, goarm: String, dest: File) =
    buildGoClient("olcrtc-src", "https://github.com/openlibrecommunity/olcrtc.git", olcRtcCommit, "./cmd/olcrtc", goos, goarch, goarm, dest)

/** Коммит OpenFlux — тот же, что у готовых бинарей (метка v0.4.2) и у Android-сборки. */
val openFluxCommit = "74cac6d47bf4c27947348ee957538a2c0728a485"
fun buildOpenFlux(goos: String, goarch: String, goarm: String, dest: File) =
    buildGoClient("openflux-src", "https://github.com/p1neappleXpress/OpenFlux.git", openFluxCommit, ".", goos, goarch, goarm, dest)

fun buildGoClient(srcDir: String, repo: String, commit: String, pkg: String, goos: String, goarch: String, goarm: String, dest: File) {
    fun run(dir: File, vararg cmd: String, env: Map<String, String> = emptyMap()) {
        val pb = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "${cmd.joinToString(" ")}: $out" }
    }
    val src = layout.buildDirectory.dir(srcDir).get().asFile.apply { mkdirs() }
    val haveTools = runCatching { run(src, "go", "version"); run(src, "git", "--version") }.isSuccess
    if (!haveTools) { logger.warn("classic: нет go или git — ${dest.name} в архив не войдёт"); return }
    if (!src.resolve(".git").isDirectory) {
        run(src, "git", "init", "-q")
        run(src, "git", "remote", "add", "origin", repo)
    }
    run(src, "git", "fetch", "-q", "--depth", "1", "origin", commit)
    run(src, "git", "checkout", "-q", "--force", "FETCH_HEAD")
    val env = mutableMapOf("CGO_ENABLED" to "0", "GOOS" to goos, "GOARCH" to goarch)
    if (goarm.isNotEmpty()) env["GOARM"] = goarm
    // -checklinkname=0: обе программы через зависимости ссылаются на внутренности net (так же собирает апстрим).
    run(src, "go", "build", "-trimpath", "-ldflags=-s -w -checklinkname=0", "-o", dest.absolutePath, pkg, env = env)
}

// ---- Сборка архива ---------------------------------------------------------------------------------------------
fun launcherVbs(): String = """
' Hydra Classic: запуск без окна консоли. Параметры (например, --minimized) передаются дальше.
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
root = fso.GetParentFolderName(WScript.ScriptFullName)
q = Chr(34)
java = root & "\jre\bin\javaw.exe"
If Not fso.FileExists(java) Then java = "javaw"
cmd = q & java & q & " -XX:+UseSerialGC -Xms16m -Xmx256m -Dfile.encoding=UTF-8" & _
  " -Dhydra.version=$appVersion -Dhydra.classic=true" & _
  " -Dhydra.launcher=" & q & WScript.ScriptFullName & q & _
  " -Dcompose.application.resources.dir=" & q & root & "\resources" & q & _
  " -cp " & q & root & "\lib\*" & q & " ru.gidravpn.hydra.desktop.lite.LiteMainKt"
For Each a In WScript.Arguments
  cmd = cmd & " " & a
Next
sh.Run cmd, 0, False
""".trimIndent().replace("\n", "\r\n") + "\r\n"

fun launcherSh(): String = """
#!/bin/sh
# Hydra Classic: запуск. Нужна Java 11 или новее (sudo apt install default-jre); HYDRA_JAVA — путь к своей java.
SELF="${'$'}(readlink -f "${'$'}0")"
HERE="${'$'}(dirname "${'$'}SELF")"
JAVA="${'$'}{HYDRA_JAVA:-java}"
[ -x "${'$'}HERE/jre/bin/java" ] && JAVA="${'$'}HERE/jre/bin/java"
if ! command -v "${'$'}JAVA" >/dev/null 2>&1; then
  echo "Hydra: не найдена Java. Установите Java 11 или новее (например, sudo apt install default-jre)." >&2
  command -v zenity >/dev/null 2>&1 && zenity --error --text="Hydra: не найдена Java 11+. Установите default-jre." 2>/dev/null
  exit 1
fi
exec "${'$'}JAVA" -XX:+UseSerialGC -Xms16m -Xmx256m -Dfile.encoding=UTF-8 \
  -Dhydra.version=$appVersion -Dhydra.classic=true -Dhydra.launcher="${'$'}SELF" \
  -Dcompose.application.resources.dir="${'$'}HERE/resources" \
  -cp "${'$'}HERE/lib/*" ru.gidravpn.hydra.desktop.lite.LiteMainKt "${'$'}@"
""".trimIndent() + "\n"

fun readme(t: Classic): String = """
Hydra Classic $appVersion (${t.os}, ${t.arch})
=====================================
Упрощённая оболочка Hydra для 32-битных систем и Windows 7. Те же ядра sing-box и Xray, те же настройки,
подписки и маршрутизация, что у обычной Hydra; отличается только окно.

Запуск: ${if (t.os == "windows") "Hydra.vbs (двойной щелчок)" else "./hydra.sh"}
${if (t.os == "windows") "Требуется Windows 7 SP1 или новее. Java уже внутри (папка jre)."
else "Требуется Java 11 или новее: sudo apt install default-jre (или свой путь в HYDRA_JAVA)."}

Обновление: Hydra сама скачивает и ставит новую версию (Настройки → «Проверить обновления сейчас»).
Данные (серверы, настройки) лежат вне этой папки и при обновлении сохраняются.
Подробности: docs/LEGACY.md в репозитории проекта.
""".trimIndent().replace("\n", if (t.os == "windows") "\r\n" else "\n") + "\n"

val classicDistTasks = classicTargets.map { t ->
    val root = layout.buildDirectory.dir("classic/${t.id}/Hydra")
    val prepare = tasks.register("classicPrepare${t.taskSuffix}") {
        group = "hydra classic"
        description = "Раскладка Hydra Classic для ${t.id}"
        dependsOn("liteJar")
        val liteRuntime = configurations.getByName("liteRuntimeClasspath")
        inputs.files(liteRuntime)
        inputs.files(tasks.named("liteJar").map { it.outputs.files })
        inputs.property("t", t.toString() + singBoxVersion + xrayVersion + olcRtcCommit + openFluxCommit)
        outputs.dir(root)
        doLast {
            val out = root.get().asFile
            out.deleteRecursively()
            val res = File(out, "resources").apply { mkdirs() }
            val lib = File(out, "lib").apply { mkdirs() }

            // Код: собранный jar оболочки и все его зависимости.
            (liteRuntime.files + tasks.getByName("liteJar").outputs.files.files).forEach { it.copyTo(File(lib, it.name), overwrite = true) }

            val exe = if (t.os == "windows") ".exe" else ""
            fun extract(archive: File, wanted: String, outName: String, license: String? = null) {
                val tree = if (archive.name.endsWith(".zip")) zipTree(archive) else tarTree(resources.gzip(archive))
                copy {
                    from(tree)
                    include("**/$wanted", "**/LICENSE")
                    eachFile { path = if (name == "LICENSE") (license ?: "LICENSE") else outName }
                    includeEmptyDirs = false
                    into(res)
                }
            }
            extract(fetch("https://github.com/SagerNet/sing-box/releases/download/v$singBoxVersion/${t.singBoxAsset}", t.singBoxSha, t.singBoxAsset),
                "sing-box$exe", "sing-box$exe", "LICENSE-sing-box")
            val xrayFile = fetch("https://github.com/XTLS/Xray-core/releases/download/v$xrayVersion/${t.xrayAsset}", t.xraySha, t.xrayAsset)
            extract(xrayFile, "xray$exe", "xray$exe", "LICENSE-xray")
            t.openFlux?.let { (asset, sha) ->
                val f = fetch("https://github.com/p1neappleXpress/OpenFlux/releases/download/v$openFluxVersion/$asset", sha, "OpenFlux-$openFluxVersion-$asset")
                f.copyTo(File(res, "openflux$exe"), overwrite = true)
            }
            t.olcRtc?.let { (goos, goarch, goarm) -> buildOlcRtc(goos, goarch, goarm, File(res, "olcrtc$exe")) }
            t.openFluxSrc?.let { (goos, goarch, goarm) -> buildOpenFlux(goos, goarch, goarm, File(res, "openflux$exe")) }

            // Geo-базы — те же, что в Android и в обычной Hydra.
            copy { from(rootProject.file("app/src/main/assets")) { include("geoip/*.srs", "geosite/*.srs") }; into(File(res, "geo")) }

            t.jre?.let { (url, name, sha) ->
                val zip = fetch(url, sha, name)
                copy { from(zipTree(zip)) { eachFile { path = path.substringAfter('/') } }; includeEmptyDirs = false; into(File(out, "jre")) }
            }

            if (t.os == "windows") File(out, "Hydra.vbs").writeText(launcherVbs())
            else File(out, "hydra.sh").apply { writeText(launcherSh()); setExecutable(true, false) }
            File(out, "README.txt").writeText(readme(t))
            File(rootProject.projectDir, "LICENSE").copyTo(File(out, "LICENSE"), overwrite = true)

            // Бит исполнения: Gradle-архив берёт его из файла.
            res.listFiles()?.filter { it.isFile && !it.name.startsWith("LICENSE") }?.forEach { it.setExecutable(true, false) }
            check(File(res, "sing-box$exe").isFile && File(res, "xray$exe").isFile) { "в раскладке нет ядер" }
        }
    }
    val name = "classicDist${t.taskSuffix}"
    val fileName = "Hydra-desktop-$appVersion-${t.os}-${t.arch}-classic.${if (t.os == "windows") "zip" else "tar.gz"}"
    fun AbstractArchiveTask.configureCommon() {
        group = "hydra classic"
        description = "Архив Hydra Classic для ${t.id}"
        dependsOn(prepare)
        from(layout.buildDirectory.dir("classic/${t.id}")) { include("Hydra/**") }
        destinationDirectory.set(layout.buildDirectory.dir("classic/dist"))
        archiveFileName.set(fileName)
        isReproducibleFileOrder = true
    }
    if (t.os == "windows") tasks.register<Zip>(name) { configureCommon() }
    else tasks.register<Tar>(name) {
        configureCommon()
        compression = Compression.GZIP
        // Бит исполнения задаём явно: на Windows-машине сборки файловая система его не хранит.
        filesMatching(listOf("**/hydra.sh", "**/resources/sing-box", "**/resources/xray", "**/resources/openflux", "**/resources/olcrtc")) {
            permissions { unix("rwxr-xr-x") }
        }
    }
}

tasks.register("classicDist") {
    group = "hydra classic"
    description = "Все архивы Hydra Classic (Windows x86/x64, Linux x86/armv7)"
    dependsOn(classicDistTasks)
}

// Jar оболочки: код приложения без Compose + Swing-окно. Зависимости кладутся рядом, в lib/.
tasks.register<Jar>("liteJar") {
    group = "hydra classic"
    archiveBaseName.set("hydra-classic")
    from(lite.output)
    manifest { attributes("Main-Class" to "ru.gidravpn.hydra.desktop.lite.LiteMainKt") }
}


tasks.register<JavaExec>("runClassic") {
    group = "hydra classic"
    description = "Запуск Hydra Classic из исходников (для проверки на этой машине)"
    classpath = lite.runtimeClasspath
    mainClass.set("ru.gidravpn.hydra.desktop.lite.LiteMainKt")
    systemProperty("hydra.version", appVersion)
    systemProperty("hydra.classic", "true")
}
