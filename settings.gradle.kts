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
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/maven")
        // Локальные .aar (libbox.aar, libXray.aar) лежат в app/libs
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "Hydra"
include(":app", ":shared", ":desktop")
