// :core = dominio Kotlin puro. Sin Android, sin Compose (ADR 0006).
// Se compila y testea en el Moto con settings-core.gradle.kts.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // DTOs de backup/plan con kotlinx-serialization (GIM-8). Sólo :core lo necesita:
    // el plugin se declara acá y no en el root (ver el comentario de build.gradle.kts).
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        // JDK 17 de bytecode. El host es JDK 21 en el Moto y en CI; no se declara
        // toolchain(17) porque exigiría descargar un JDK 17 que el equipo no tiene.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
