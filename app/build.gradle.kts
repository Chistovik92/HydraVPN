import java.io.FileInputStream
import java.util.Properties
import java.util.zip.ZipFile

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
        versionCode = 20
        versionName = "0.6.10"

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
    // Какая из них попадает в сборку flavor `native` — решает наличие .aar —
    // так `assembleNativeDebug` не ломается, если libXray.aar ещё не собран.
    // .so и classes.dex для процесса :xray подключаются НЕ как Gradle-зависимость
    // (см. extractLibXrayNativeLibs/libXrayToDex ниже и комментарий там же).
    sourceSets {
        getByName("native") {
            val hasLibXray = file("libs/libXray.aar").exists()
            java.srcDir(if (hasLibXray) "src/nativeXrayReal/java" else "src/nativeXrayStub/java")
            if (hasLibXray) {
                jniLibs.srcDir(layout.buildDirectory.dir("libXrayJni"))
                assets.srcDir(layout.buildDirectory.dir("libXrayDexAsset"))
            }
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
        // Требование AGP при android:extractNativeLibs="true" (см. AndroidManifest.xml).
        jniLibs.useLegacyPackaging = true
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
 * каждый несёт свою копию обвязки gobind (пакет `go.*`: Seq/Universe/error).
 *
 * Она не взаимозаменяема между сборками (у каждой свой
 * `System.loadLibrary(...)` — "box" vs "gojni" — зашитый в `go.Seq.<clinit>`)
 * И НЕ ПЕРЕИМЕНОВЫВАЕТСЯ: JNI у gomobile — implicit-linking, символы в
 * `libgojni.so` жёстко экспортированы как `Java_go_Seq_init` и т.п.
 * (по исходному имени класса). Проверено на практике (0.6.6):
 *   1. Оставить одну копию (от libbox) — процесс `:xray` грузит `libbox.so`
 *      вместо `libgojni.so` → `UnsatisfiedLinkError` на `LibXray._init()`.
 *   2. Переименовать пакет `go`→`xraygo` (ASM) — грузится уже правильный
 *      `.so`, но JNI ищет `Java_xraygo_Seq_init`, а .so экспортирует только
 *      `Java_go_Seq_init` → тот же `UnsatisfiedLinkError`, другая точка.
 *
 * Единственный работающий вариант — НЕ подключать классы libXray.aar в общий
 * classpath приложения вообще. Java/нативный код Xray грузится в рантайме
 * (в процессе `:xray`, см. `XrayEngineService`) через ИЗОЛИРОВАННЫЙ
 * `DexClassLoader` со своим `classes.dex` и своим native library search
 * path — тогда его `go.Seq` живёт в отдельном classloader'е, не пересекаясь
 * с libbox'овским, и грузит именно свою `.so` по своим же JNI-символам.
 * `XrayEngineService` обращается к загруженным классам через reflection —
 * компилировать `libXray.*` напрямую (значит, и включать как обычную Gradle
 * зависимость) не нужно вовсе.
 */
val libXrayRaw = layout.projectDirectory.file("libs/libXray.aar").asFile
val libXrayJniDir = layout.buildDirectory.dir("libXrayJni")
val libXrayDexAssetDir = layout.buildDirectory.dir("libXrayDexAsset")

/** .so для процесса :xray — обычные jniLibs, распаковка АPK делает всё сама. */
val extractLibXrayNativeLibs = tasks.register("extractLibXrayNativeLibs") {
    inputs.file(libXrayRaw)
    outputs.dir(libXrayJniDir)
    onlyIf { libXrayRaw.exists() }
    doLast {
        val outDir = libXrayJniDir.get().asFile
        outDir.deleteRecursively()
        ZipFile(libXrayRaw).use { srcAar ->
            val entries = srcAar.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (!e.name.startsWith("jni/") || e.isDirectory) continue
                val dest = File(outDir, e.name.removePrefix("jni/"))
                dest.parentFile.mkdirs()
                srcAar.getInputStream(e).use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        }
        logger.lifecycle("libXray.aar: .so извлечены в $outDir")
    }
}

// classes.jar (нетронутый, оригинальные имена go.* и libXray.*) -> classes.dex через d8, как asset.
val libXrayToDex = tasks.register("libXrayToDex") {
    inputs.file(libXrayRaw)
    val extractedJar = layout.buildDirectory.file("libXrayDex/classes.jar")
    val outAsset = libXrayDexAssetDir.map { it.file("libxray.dex") }
    outputs.file(outAsset)
    onlyIf { libXrayRaw.exists() }
    doLast {
        val jarFile = extractedJar.get().asFile
        jarFile.parentFile.mkdirs()
        ZipFile(libXrayRaw).use { srcAar ->
            val classesEntry = srcAar.getEntry("classes.jar")
                ?: throw GradleException("libXray.aar: classes.jar не найден внутри архива")
            srcAar.getInputStream(classesEntry).use { input -> jarFile.outputStream().use { input.copyTo(it) } }
        }

        val d8 = fileTree(android.sdkDirectory)
            .matching { include("build-tools/*/d8.bat", "build-tools/*/d8") }
            .files.maxByOrNull { it.parentFile.name }
            ?: throw GradleException("d8 не найден в \$ANDROID_HOME/build-tools/*/")

        val outDir = outAsset.get().asFile.parentFile
        outDir.mkdirs()
        // Project.exec{} убран в Gradle 9 (был deprecated с 7.x) — CI генерирует
        // wrapper системным Gradle ДО того, как появляется закреплённая версия
        // (gradle-wrapper.jar сознательно не в репозитории, см. docs/BUILD.md),
        // так что скрипт должен компилироваться и под 9.x. providers.exec{} —
        // текущая замена, доступна уже в 7.5+, так что работает и под локальный
        // портативный Gradle 8.9.
        providers.exec {
            commandLine(d8.absolutePath, "--release", "--min-api", "26", "--output", outDir.absolutePath, jarFile.absolutePath)
        }.result.get()
        val produced = File(outDir, "classes.dex")
        produced.copyTo(outAsset.get().asFile, overwrite = true)
        produced.delete()
        logger.lifecycle("libXray.aar: classes.jar -> ${outAsset.get().asFile} (через d8)")
    }
}

// Множество задач читает assets/jniLibs сгенерированного сорсета (merge,
// packaging, lint-vital и т.д.) — гонять regex по каждой из них хрупко (уже
// не совпало один раз с lint-задачей). Вместо этого — тот же хук
// `preNative*Build`, что и у checkNativeCores выше: он гарантированно стоит
// в графе ДО всех остальных задач flavor `native`, так что достаточно
// одной явной связи здесь.
tasks.matching { it.name.matches(Regex("^preNative.*Build\$")) }.configureEach {
    dependsOn(extractLibXrayNativeLibs, libXrayToDex)
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

    // JVM unit-тесты. org.json из android.jar в unit-тестах — заглушки
    // ("Method ... not mocked"), поэтому настоящая реализация отдельно.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    // === Нативные ядра. Файлы .aar кладутся в app/libs вручную (см. docs/BUILD.md) ===
    // sing-box: github.com/SagerNet/sing-box (experimental/libbox), собран через gomobile.
    // Проверено против libbox 1.12.9 (docs/BUILD.md: сборка с -checklinkname=0).
    "nativeImplementation"(":libbox@aar")
    // Xray-core: github.com/XTLS/libXray — опционален (XrayCore честно откажет
    // при подключении, пока .aar не собран). НЕ подключаем как обычную
    // Gradle-зависимость (`libXray@aar`) — её classes.jar конфликтует с
    // libbox'овским go.* на уровне JNI (см. комментарий у
    // extractLibXrayNativeLibs/libXrayToDex выше). .so и classes.dex для
    // изолированного рантайм-загрузчика (XrayEngineService) подключены
    // через jniLibs.srcDir/assets.srcDir в sourceSets["native"] выше.
}
