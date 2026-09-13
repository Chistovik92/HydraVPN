import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * Ключ подписи не хранится в репозитории. Положите keystore.properties в корень
 * проекта (он в .gitignore) — см. docs/BUILD.md, раздел «Подпись релиза»:
 *   storeFile=... storePassword=... keyAlias=... keyPassword=...
 * Если файла нет, release собирается как раньше (debug-подпись) — чтобы сборка
 * не ломалась у того, у кого ключа нет.
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.exists() &&
    keystoreProps.getProperty("storeFile")?.isNotBlank() == true

android {
    namespace = "ru.gidravpn.hydra"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.gidravpn.hydra"
        minSdk = 26            // Android 8.0. VpnService доступен с API 14
        targetSdk = 35
        versionCode = 16
        versionName = "0.6.6"

        // ABI, под которые собраны нативные ядра (libbox / libXray)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }


    flavorDimensions += "engine"
    productFlavors {
        // Собирается без нативных .aar. Ядра — заглушки (симуляция соединения),
        // удобно для разработки UI и CI без тяжёлых бинарников.
        create("stub") {
            dimension = "engine"
            versionNameSuffix = "-stub"
            buildConfigField("boolean", "XRAY_AVAILABLE", "false")
        }
        // Реальная интеграция с libbox.aar (sing-box) и libXray.aar (Xray).
        // Требует положить .aar в app/libs (см. docs/BUILD.md).
        create("native") {
            dimension = "engine"
            buildConfigField("boolean", "XRAY_AVAILABLE", file("libs/libXray.aar").exists().toString())
        }
    }

    // XrayCore.kt существует в двух вариантах (см. docs/BUILD.md, раздел 2.2):
    // заглушка (nativeXrayStub) и рабочая реализация на libXray.aar (nativeXrayReal).
    // Какая из них попадает в сборку flavor `native` — решает наличие .aar,
    // тем же условием, что и подключение зависимости `:libXray@aar` ниже —
    // так `assembleNativeDebug` не ломается, если libXray.aar ещё не собран.
    sourceSets {
        getByName("native") {
            val xraySrc = if (file("libs/libXray.aar").exists()) "src/nativeXrayReal/java" else "src/nativeXrayStub/java"
            java.srcDir(xraySrc)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true   // IXrayEngine/IXraySocketProtector — мост к процессу :xray
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // libbox.aar и libXray.aar — каждый собран отдельным `gomobile bind` и
        // тащит свою копию generic-обвязки gobind (golang.org/x/mobile/bind/java,
        // общий для ЛЮБОГО gomobile-биндинга код, без нативной части — сам Go-код
        // и его рантайм остаются в разных .so, изолированы через отдельный
        // процесс :xray, см. AndroidManifest.xml). Идентичные классы — оставляем
        // одну копию, иначе checkNativeDebugDuplicateClasses валит сборку.
        resources.excludes += setOf(
            "go/Seq.class", "go/Seq\$*.class",
            "go/Universe.class", "go/Universe\$*.class",
            "go/error.class",
        )
    }
}

/**
 * Предохранитель от «пустых» сборок.
 *
 * Без `app/libs/libbox.aar` flavor `native` падает невнятной ошибкой резолва
 * зависимости `:libbox@aar`, которую легко принять за «native собрать нельзя» —
 * именно так в 0.6.0 релиз чуть не уехал с одной stub-сборкой (симуляция
 * соединения вместо VPN). Здесь ошибка становится объяснением, что положить.
 * Подробности — docs/HANDOFF.md, «Честные оговорки» (0.6.0).
 */
val checkNativeCores = tasks.register("checkNativeCores") {
    group = "verification"
    description = "Проверяет наличие нативных ядер (.aar) перед сборкой flavor `native`"
    val libbox = layout.projectDirectory.file("libs/libbox.aar").asFile
    doLast {
        if (!libbox.exists()) throw GradleException(
            """
            |
            |Нет app/libs/libbox.aar — flavor `native` собрать нельзя.
            |
            |Это ядро sing-box; без него приложение не умеет поднимать туннель.
            |Что делать:
            |  • положить готовый libbox.aar в app/libs/, либо
            |  • собрать его самому — docs/BUILD.md, раздел 2.1, либо
            |  • для работы над UI/CI собрать stub:  gradlew :app:assembleStubDebug
            |
            |ВАЖНО: stub-сборка симулирует соединение и не годится для релиза.
            |Релиз выпускается только через scripts/release.sh.
            |
            """.trimMargin()
        )
    }
}

tasks.matching { it.name.matches(Regex("^preNative.*Build$")) }.configureEach {
    dependsOn(checkNativeCores)
}

/**
 * libbox.aar и libXray.aar собраны двумя независимыми `gomobile bind` —
 * каждый несёт свою копию generic-обвязки gobind (`go.Seq`/`go.Universe`/
 * `go.error`, пакет `golang.org/x/mobile/bind/java`, без нативного кода —
 * это чистый Java-мост, реальные Go-рантаймы остаются в разных .so и
 * изолированы отдельным процессом `:xray`, см. AndroidManifest.xml). Байт в
 * байт одинаковые классы под одним именем валят `checkNativeDebugDuplicateClasses`,
 * а обычный `packagingOptions.resources.excludes` эту проверку не обходит —
 * она смотрит на исходные classes.jar каждого модуля ДО этапа packaging.
 * Поэтому вырезаем дубли прямо из classes.jar внутри libXray.aar здесь и
 * скармливаем Gradle уже очищенную копию.
 */
val libXrayRaw = layout.projectDirectory.file("libs/libXray.aar").asFile
val libXrayDeduped = layout.buildDirectory.file("libXrayDedup/libXray.aar")

val dedupLibXrayClasses = tasks.register("dedupLibXrayClasses") {
    inputs.file(libXrayRaw)
    outputs.file(libXrayDeduped)
    onlyIf { libXrayRaw.exists() }
    doLast {
        val output = libXrayDeduped.get().asFile
        output.parentFile.mkdirs()
        val dupPatterns = listOf(
            Regex("""^go/Seq(\$.*)?\.class$"""),
            Regex("""^go/Universe(\$.*)?\.class$"""),
            Regex("""^go/error\.class$"""),
        )
        fun isDup(name: String) = dupPatterns.any { it.matches(name) }

        ZipFile(libXrayRaw).use { srcAar ->
            val classesEntry = srcAar.getEntry("classes.jar")
                ?: throw GradleException("libXray.aar: classes.jar не найден внутри архива")

            val newClassesBytes = ByteArrayOutputStream().also { bos ->
                ZipOutputStream(bos).use { zos ->
                    ZipInputStream(srcAar.getInputStream(classesEntry)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!isDup(entry.name)) {
                                zos.putNextEntry(ZipEntry(entry.name))
                                zis.copyTo(zos)
                                zos.closeEntry()
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
            }.toByteArray()

            ZipOutputStream(output.outputStream()).use { zos ->
                val entries = srcAar.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    zos.putNextEntry(ZipEntry(e.name))
                    if (e.name == "classes.jar") zos.write(newClassesBytes)
                    else srcAar.getInputStream(e).copyTo(zos)
                    zos.closeEntry()
                }
            }
        }
        logger.lifecycle("libXray.aar: удалены дублирующиеся с libbox классы go.Seq/go.Universe/go.error -> $output")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Jetpack Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.1")

    // Room (хранение серверов / подписок)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore (настройки протоколов)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Сеть (загрузка подписок)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // === Нативные ядра. Файлы .aar кладутся в app/libs вручную (см. docs/BUILD.md) ===
    // sing-box: github.com/SagerNet/sing-box (experimental/libbox), собран через gomobile.
    // Проверено против libbox 1.12.9 (docs/BUILD.md: сборка с -checklinkname=0).
    "nativeImplementation"(":libbox@aar")
    // Xray-core: github.com/XTLS/libXray — опционален (XrayCore честно откажет
    // при подключении, пока .aar не собран); включается при наличии файла.
    // Не сырой :libXray@aar — очищенная от дублей с libbox копия, см.
    // dedupLibXrayClasses выше.
    if (libXrayRaw.exists()) "nativeImplementation"(files(dedupLibXrayClasses))
}
