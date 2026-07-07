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
        // Локальные .aar (libbox.aar, libXray.aar) лежат в app/libs
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "ShadowLink"
include(":app")
