// Build raíz. NO declara AGP a propósito: settings-core.gradle.kts evalúa este
// archivo en el gate local, y el gate tiene que correr sin Android SDK.
// AGP se declara únicamente en app/build.gradle.kts (ver docs/build.md).
//
// Tampoco se fuerza una versión de KGP con buildscript{classpath(...)}: AGP 9.5.1
// usa built-in Kotlin y construye un KotlinAndroidTarget de la KGP que trae
// (2.2.10). Forzar 2.4.21 rompe en la aplicación del plugin
// (NoClassDefFoundError: com.android.build.gradle.api.BaseVariant).
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}
