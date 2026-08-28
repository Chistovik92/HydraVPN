plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "ru.gidravpn.hydra"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.gidravpn.hydra"
        minSdk = 26            // Android 8.0. VpnService РґРѕСЃС‚СѓРїРµРЅ СЃ API 14, IKEv2 С‡РµСЂРµР· VpnManager вЂ” СЃ API 33 (РїСЂРѕРІРµСЂСЏРµС‚СЃСЏ РІ СЂР°РЅС‚Р°Р№РјРµ)
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.1"

        // ABI, РїРѕРґ РєРѕС‚РѕСЂС‹Рµ СЃРѕР±СЂР°РЅС‹ РЅР°С‚РёРІРЅС‹Рµ СЏРґСЂР° (libbox / libXray)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
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
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }


    flavorDimensions += "engine"
    productFlavors {
        // РЎРѕР±РёСЂР°РµС‚СЃСЏ Р±РµР· РЅР°С‚РёРІРЅС‹С… .aar. РЇРґСЂР° вЂ” Р·Р°РіР»СѓС€РєРё (СЃРёРјСѓР»СЏС†РёСЏ СЃРѕРµРґРёРЅРµРЅРёСЏ),
        // СѓРґРѕР±РЅРѕ РґР»СЏ СЂР°Р·СЂР°Р±РѕС‚РєРё UI Рё CI Р±РµР· С‚СЏР¶С‘Р»С‹С… Р±РёРЅР°СЂРЅРёРєРѕРІ.
        create("stub") {
            dimension = "engine"
            versionNameSuffix = "-stub"
        }
        // Р РµР°Р»СЊРЅР°СЏ РёРЅС‚РµРіСЂР°С†РёСЏ СЃ libbox.aar (sing-box) Рё libXray.aar (Xray).
        // РўСЂРµР±СѓРµС‚ РїРѕР»РѕР¶РёС‚СЊ .aar РІ app/libs (СЃРј. docs/BUILD.md).
        create("native") {
            dimension = "engine"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
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

    // Room (С…СЂР°РЅРµРЅРёРµ СЃРµСЂРІРµСЂРѕРІ / РїРѕРґРїРёСЃРѕРє)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore (РЅР°СЃС‚СЂРѕР№РєРё РїСЂРѕС‚РѕРєРѕР»РѕРІ)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // РЎРµС‚СЊ (Р·Р°РіСЂСѓР·РєР° РїРѕРґРїРёСЃРѕРє)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // === РќР°С‚РёРІРЅС‹Рµ СЏРґСЂР°. Р¤Р°Р№Р»С‹ .aar РєР»Р°РґСѓС‚СЃСЏ РІ app/libs РІСЂСѓС‡РЅСѓСЋ (СЃРј. docs/BUILD.md) ===
    // sing-box: github.com/SagerNet/sing-box (experimental/libbox), СЃРѕР±СЂР°РЅ С‡РµСЂРµР· gomobile
    "nativeImplementation"(":libbox@aar")
    // Xray-core: github.com/XTLS/libXray, СЃРѕР±СЂР°РЅ С‡РµСЂРµР· gomobile
    "nativeImplementation"(":libXray@aar")
}
