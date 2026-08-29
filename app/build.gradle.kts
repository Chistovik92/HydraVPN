import java.io.FileInputStream
import java.util.Properties

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
        versionCode = 13
        versionName = "0.6.1"

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
        }
        // Реальная интеграция с libbox.aar (sing-box) и libXray.aar (Xray).
        // Требует положить .aar в app/libs (см. docs/BUILD.md).
        create("native") {
            dimension = "engine"
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
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
    if (file("libs/libXray.aar").exists()) "nativeImplementation"(":libXray@aar")
}
