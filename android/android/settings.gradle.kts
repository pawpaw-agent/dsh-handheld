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
        maven(url = "https://jitpack.io") // Termux terminal-view / terminal-emulator
    }
}

rootProject.name = "DshHandheld"
include(":app")

// Terminal conformance harness: pure JVM, never a dependency of :app.
// See docs/terminal-rewrite-plan.md (phase 0) and the module's build script.
include(":terminal-conformance")
