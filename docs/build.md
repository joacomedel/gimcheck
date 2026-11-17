# Build y verificación — GimCheck

Tres niveles, porque este equipo no puede compilar Android (ver
`docs/analisis-tecnico.md` §3 y ADR 0002):

| Nivel | Dónde | Comando | Qué cubre |
|---|---|---|---|
| L0/L1 (lógica) | Moto | `scripts/check-core.sh` | `:core` (dominio + máquina de estados + JSON), sin Android SDK |
| L2 (APK) | GitHub Actions | push a `master` / PR | `:core:test` + `:app:lintDebug` + `:app:assembleDebug` + artifact `gimcheck-debug.apk` |
| L3 (dispositivo) | teléfono del dueño | checklist de QA | instalación, alertas de descanso, vibración, sonido, cronómetro con app cerrada |

## Matriz de versiones final (la que compila en CI)

| Pieza | Versión | Dónde se declara |
|---|---|---|
| Gradle | **9.9.1** | `gradle/wrapper/gradle-wrapper.properties` (wrapper) |
| Android Gradle Plugin | **9.5.1** | `gradle/libs.versions.toml` → `agp`, aplicado **solo** en `app/build.gradle.kts` |
| Kotlin (KGP) | **2.4.21** | catálogo (`kotlin`) + `buildscript` del root (AGP 9.5.1 trae 2.2.10; se sube a 2.4.21) |
| Compose compiler | **2.4.21** | plugin `org.jetbrains.kotlin.plugin.compose` (misma versión que Kotlin) |
| KSP | 2.3.14 | catálogo (`ksp`); se aplica recién con Room (GIM-9) |
| Compose BOM | 2026.09.00 | catálogo (`composeBom`) |
| Room | 2.8.5 | catálogo (`room`); sin aplicar todavía |
| activity-compose | 1.13.0 | catálogo |
| navigation-compose | 2.10.2 | catálogo |
| lifecycle-* | 2.11.0 | catálogo |
| androidx.core-ktx | 1.19.1 | catálogo |
| kotlinx-serialization-json | 1.13.0 | catálogo |
| kotlinx-datetime | 0.8.0 | `:core` (implementation) |
| Robolectric | 4.18 | catálogo (tests de UI en JVM, más adelante) |
| JUnit4 | 4.13.2 | `:core` (testImplementation) |
| JDK de build | **21** (temurin en CI, OpenJDK 21.0.13 en el Moto) | `setup-java` / host |
| Bytecode / `jvmTarget` | **17** | `compileOptions` en `:app`, `java` + `kotlin.compilerOptions` en `:core` |
| compileSdk / targetSdk / minSdk | **36 / 36 / 26** | `app/build.gradle.kts` |
| Android SDK en CI | `platforms;android-36`, `build-tools;36.0.0` | `android-actions/setup-android` |

Notas de la matriz:

- **AGP 9 tiene Kotlin built-in**: no se aplica `org.jetbrains.kotlin.android`
  (sería un error de configuración). `:app` aplica `com.android.application` +
  `org.jetbrains.kotlin.plugin.compose`.
- AGP 9.5.1 declara una dependencia de runtime a KGP **2.2.10**. El root hace el
  upgrade documentado (`buildscript { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21") }`)
  para que Kotlin y el compilador de Compose queden en 2.4.21.
- No se declara `toolchain(17)`: en el Moto solo existe JDK 21 y un toolchain 17
  obligaría a descargar otro JDK. El bytecode sale 17 igual (`jvmTarget` y
  `sourceCompatibility`/`targetCompatibility` = 17).
- `minSdk 26`, `compileSdk 36` y `targetSdk 36` son los del ticket GIM-1.
- No hay dependencias fuera del catálogo.

## Gate local (`:core` sin Android SDK)

```bash
./scripts/check-core.sh        # = gradle -PcoreOnly :core:test, con guardas
```

Equivale a:

```bash
taskset -c 0-1 gradle --daemon -Dorg.gradle.jvmargs=-Xmx768m -PcoreOnly :core:test
```

**Por qué `-PcoreOnly` y no `-c settings-core.gradle.kts`:** Gradle 9 eliminó
`-c`/`--settings-file` y `-b`/`--build-file` (ver *Upgrading to Gradle 9.0*,
"Removal of custom build layout options"). El settings reducido sigue existiendo
en `settings-core.gradle.kts` y **solo incluye `:core`**; `settings.gradle.kts`
lo aplica cuando se pasa `-PcoreOnly` y en ese modo **no incluye `:app`**, así
que `app/build.gradle.kts` no se evalúa y AGP nunca se resuelve. Ese es el
punto que mantiene el gate sin Android SDK: AGP está declarado únicamente en
`app/build.gradle.kts`, nunca en el root ni en `settings`.

Guardas del script: no arranca con menos de 800 MB de RAM libre ni con la zona
térmica más caliente ≥ 55 °C; corre con `taskset -c 0-1`, `-Xmx768m`, y avisa si
al terminar la temperatura llegó a 58 °C. Una corrida a la vez. Al cerrar la
sesión de trabajo: `gradle --stop`.

### Medición sobre el `:core` real (2026-11-17, este equipo)

Equipo: 3.4 GiB RAM (1.8 GiB disponibles), 8 núcleos, `taskset -c 0-1`.

| Operación | Tiempo | RAM pico (procesos `java`) | Temp |
|---|---|---|---|
| `gradle -PcoreOnly wrapper` (primer arranque del daemon, resuelve KGP) | 2 m 25 s | — | 48 °C |
| `gradle -PcoreOnly :core:test` en frío (baja kotlinx-datetime, JUnit y kotlin-test) | **1 m 22 s** | **755 MB** | 48 °C |
| `gradle -PcoreOnly :core:test` sin cambios (daemon caliente) | **9 s** | 755 MB | 49 °C |

Conclusiones:

- El gate local sirve: **9 s** en régimen y pico ~755 MB. Queda por debajo del
  techo de RAM del equipo y la temperatura no pasó de 49 °C.
- El número de RAM es más alto que la sonda de `docs/analisis-tecnico.md`
  (400 MB) porque ahí se medía el daemon solo; acá suma el worker de tests.
- El primer arranque del daemon (2–2,5 min) es único por sesión, no es el régimen.

## CI (GitHub Actions)

`.github/workflows/build.yml`, disparadores `push` a `master`, `pull_request` y
`workflow_dispatch`:

1. `actions/checkout@v7`
2. `actions/setup-java@v6` — temurin **21**
3. `gradle/actions/setup-gradle@v6` — caché de `~/.gradle`
4. `android-actions/setup-android@v4` — `platforms;android-36` + `build-tools;36.0.0`
5. `./gradlew :core:test`
6. `./gradlew :app:lintDebug`
7. `./gradlew :app:assembleDebug`
8. `actions/upload-artifact@v7` — **name `gimcheck-debug.apk`**,
   `path: app/build/outputs/apk/debug/app-debug.apk`, retención 30 días

Cómo se genera el APK: `assembleDebug` compila `:core`, compila `:app` (Kotlin
built-in de AGP + Compose compiler 2.4.21), procesa recursos con `aapt2`,
dexea con D8 y firma con el debug keystore autogenerado por AGP. El archivo queda
en `app/build/outputs/apk/debug/app-debug.apk`.

Runner `ubuntu-latest` (x86_64, 4 vCPU, 16 GB): el único lugar donde existe
`aapt2`. Repositorio **público** → minutos ilimitados (ADR 0002).

## Trampas del entorno

- **`-c`/`--settings-file` ya no existe en Gradle 9**: cualquier documento o
  script que lo use está roto. Usar `-PcoreOnly` (o `-p <dir>`).
- No hay `unzip` (usar `python3 -c "import zipfile; ..."`).
- No hay `/usr/bin/time` (medir RSS con `ps -eo rss,comm` o `/proc/*/statm`).
- `/tmp` es tmpfs: los logs y artefactos grandes van a `/tmp/opencode` y se
  borran al terminar.
- `gradle --stop` al cerrar: el daemon deja ~750 MB tomados si queda vivo.
- El perfil del agente implementador deniega el patrón `git push*`; en este
  ticket el dueño autorizó el push (ver el ticket GIM-1).
