// Build raíz. NO declara AGP a propósito: settings-core.gradle.kts evalúa este
// archivo en el gate local, y el gate tiene que correr sin Android SDK.
// AGP se declara únicamente en app/build.gradle.kts (ver docs/build.md).
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // AGP 9.5.1 depende de KGP 2.2.10; lo subimos a 2.4.21 para alinear el
        // compilador de Kotlin con el plugin de Compose (misma versión).
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}
