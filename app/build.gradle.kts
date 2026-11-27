// :app = aplicación Android (Compose). Único lugar que declara AGP.
// Con AGP 9 el soporte de Kotlin es built-in: no se aplica org.jetbrains.kotlin.android.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // KSP es el único generador de código: Room 2.8.5 lo usa para los DAO y para
    // exportar el schema (ver docs/adr/0003-persistencia-room.md).
    alias(libs.plugins.ksp)
    // El plugin de Room es el que copia el schema exportado por KSP a app/schemas/
    // (Room 2.8 escribe primero en build/). Sin él no hay JSON versionado.
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "app.gimcheck"

    // compileSdk 37 (Android 17) es obligatorio: androidx.core 1.19.1 y el Compose
    // BOM 2026.09.00 (compose 1.12.1) declaran minCompileSdk=37 en su AAR metadata.
    // API 37 se publica como plataforma con minor (android-37.0), no como
    // android-37, por eso se fija `compileSdkMinor = 0`.
    compileSdk = 37
    compileSdkMinor = 0

    // Firma estable para los APK de prueba. La debug key que genera cada runner de CI
    // es distinta, y Android rechaza instalar una compilación sobre otra firmada con
    // otra clave ("App not installed"): el dueño quedaba clavado en el primer APK.
    // Esta clave vive en el repo porque es una **debug key** (no protege nada, es la
    // misma convención que ~/.android/debug.keystore); GIM-30 la reemplaza por la de
    // release. Con esto, cada APK de CI se instala encima del anterior sin desinstalar.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("ci/gimcheck-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "app.gimcheck"
        minSdk = 26
        // targetSdk se mantiene en 36 (contrato del ticket): compilar contra 37 no
        // cambia el comportamiento de target.
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0-debug"

        // Tests instrumentados del aviso de fin de descanso (GIM-44): corren en el
        // emulador de CI, no en este equipo (ver docs/build.md).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        // Bytecode 17 (el host es JDK 21); con built-in Kotlin el jvmTarget sigue
        // automáticamente a targetCompatibility.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // BuildConfig.APPLICATION_ID lo verifica MainActivitySkeletonTest: es el
        // único dato del esqueleto que genera el build (en AGP 9 está apagado por
        // defecto).
        buildConfig = true
    }

    // Los tests de Room (creación/migración, DAO, integridad) corren en la JVM con
    // Robolectric: no hay emulador ni en este equipo ni en CI. Robolectric necesita
    // los recursos y assets de la variante, de ahí includeAndroidResources.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        // El schema exportado por Room se copia a los assets de `debug` para que
        // MigrationTestHelper lo lea con context.assets.open("<clase>/<version>.json")
        // en los tests JVM. Sólo debug: el APK de release no lo lleva.
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }
}

// exportSchema = true + el plugin de Room escriben app/schemas/<clase>/<version>.json.
// El schema se commitea: es el contrato que valida MigrationTestHelper.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    // `:core` declara kotlinx-datetime como `implementation`, pero expone
    // LocalDateTime/LocalDate en su API pública (Session.startedAt, BodyMetric.date).
    // Sin esta línea :app no puede referenciar esos tipos al mapear dominio ↔ Room.
    implementation(libs.kotlinx.datetime)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Navegación de las nueve pantallas de §8 (GIM-13). Rutas por String, no
    // type-safe: el grafo no tiene argumentos y la lista de destinos vive en un enum
    // (`ui/nav/GimDestination.kt`) que el test puede leer sin serialización.
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Room: la persistencia local de todo el modelo (docs/especificacion.md §2).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Tests JVM de :app (`:app:testDebugUnitTest`, sin emulador). Robolectric
    // porque Room necesita Context/Instrumentation reales; room-testing trae
    // MigrationTestHelper.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)

    // Tests instrumentados: emulador API 34 en CI (`:app:connectedDebugAndroidTest`).
    // Verifican lo que la JVM no puede: overlay encima de otra app y alarma exacta que
    // despierta el receptor con el proceso sin UI (GIM-44).
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
