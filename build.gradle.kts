// Build raíz deliberadamente vacío.
//
// Dos reglas que sostienen este repo:
//  1. NO declarar AGP acá (ni con `apply false`): settings-core.gradle.kts evalúa
//     este archivo en el gate local, y el gate tiene que correr sin Android SDK.
//     AGP se declara únicamente en app/build.gradle.kts.
//  2. NO declarar KGP acá (ni `kotlin("jvm") apply false`): declarar KGP en el
//     root lo carga en el classloader del proyecto raíz, y entonces el built-in
//     Kotlin de AGP 9 resuelve `KotlinAndroidTarget` desde ese scope, donde las
//     clases de AGP no son visibles:
//       NoClassDefFoundError: com.android.build.gradle.api.BaseVariant
//     Cada módulo declara su propio plugin (core: kotlin-jvm; app: AGP + compose).
