pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Compose Multiplatform публикуется в Maven Central; старый репозиторий
        // maven.pkg.jetbrains.space закрыт (503/301) — из-за него и «блокировался» Compose.
        // Локальные .aar (libbox.aar, libXray.aar) лежат в app/libs
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "Hydra"
include(":app", ":shared", ":desktop")
