// :app = aplicación Android (Compose). Único lugar que declara AGP.
// Con AGP 9 el soporte de Kotlin es built-in: no se aplica org.jetbrains.kotlin.android.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.gimcheck"

    // compileSdk 37 (Android 17) es obligatorio: androidx.core 1.19.1 y el Compose
    // BOM 2026.09.00 (compose 1.12.1) declaran minCompileSdk=37 en su AAR metadata.
    // API 37 se publica como plataforma con minor (android-37.0), no como
    // android-37, por eso se fija `compileSdkMinor = 0`.
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "app.gimcheck"
        minSdk = 26
        // targetSdk se mantiene en 36 (contrato del ticket): compilar contra 37 no
        // cambia el comportamiento de target.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        // Bytecode 17 (el host es JDK 21); con built-in Kotlin el jvmTarget sigue
        // automáticamente a targetCompatibility.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
