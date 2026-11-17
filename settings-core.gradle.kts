// Settings reducido del gate local (Moto, sin Android SDK): SOLO :core.
//
// Gradle 9.9.1 ya no acepta `-c/--settings-file`, así que este archivo no se pasa
// por línea de comandos: lo aplica settings.gradle.kts cuando se invoca con
// `-PcoreOnly` (lo que hace scripts/check-core.sh):
//
//     gradle -PcoreOnly :core:test
//
// Incluye únicamente :core, así que no configura AGP ni necesita Android SDK.
include(":core")
