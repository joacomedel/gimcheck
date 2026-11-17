// Build completo (CI y desarrollo en PC): :core (Kotlin JVM puro) + :app (Android + Compose).
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
    }
}

rootProject.name = "gimcheck"

// Gate local sin Android SDK. Gradle 9 eliminó `-c/--settings-file`
// (ver docs/build.md), así que el settings reducido se selecciona con una
// propiedad: `gradle -PcoreOnly :core:test`. En ese modo :app no se incluye,
// app/build.gradle.kts no se evalúa y AGP nunca se resuelve.
if (providers.gradleProperty("coreOnly").isPresent) {
    apply(from = "settings-core.gradle.kts")
} else {
    include(":core", ":app")
}
