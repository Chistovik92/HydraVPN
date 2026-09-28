plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.0.20"
    id("com.google.devtools.ksp") version "2.0.20-1.0.25"
}

kotlin {
    jvm("desktop") {
        withJava()
        compilations.all {
            kotlinOptions.jvmTarget = "17"
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation("org.slf4j:slf4j-api:2.0.13")
                implementation("ch.qos.logback:logback-classic:1.5.6")
                implementation("org.json:json:20240303")
                // JNA for Windows native API access (WinTun, etc.)
                implementation("net.java.dev.jna:jna:5.13.0")
                implementation("net.java.dev.jna:jna-platform:5.13.0")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("junit:junit:4.13.2")
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xopt-in=kotlin.RequiresOptIn")
    }
}