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
        // Termux's terminal emulator (SSH terminal, ADR-0005) is only published there.
        maven("https://jitpack.io") { content { includeGroup("com.github.termux.termux-app") } }
    }
}

rootProject.name = "idroid"
include(":app")
