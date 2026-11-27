# Build y verificación — GimCheck

Tres niveles, porque este equipo no puede compilar Android (ver
`docs/analisis-tecnico.md` §3 y ADR 0002):

| Nivel | Dónde | Comando | Qué cubre |
|---|---|---|---|
| L0/L1 (lógica) | Moto | `scripts/check-core.sh` | `:core` (dominio + máquina de estados + JSON), sin Android SDK |
| L2 (APK + tests de `:app`) | GitHub Actions | push a `master` / PR | `:core:test` + `:app:testDebugUnitTest` + `:app:lintDebug` + `:app:assembleDebug` + artifact `gimcheck-debug.apk` |
| L3 (dispositivo) | teléfono del dueño | checklist de QA | instalación, alertas de descanso, vibración, sonido, cronómetro con app cerrada |

## Matriz de versiones final (la que compila en CI)

| Pieza | Versión | Dónde se declara |
|---|---|---|
| Gradle | **9.9.1** | `gradle/wrapper/gradle-wrapper.properties` (wrapper) |
| Android Gradle Plugin | **9.5.1** | `gradle/libs.versions.toml` → `agp`, aplicado **solo** en `app/build.gradle.kts` |
| Kotlin (KGP) | **2.2.10** | la que trae AGP 9.5.1 (built-in Kotlin). **No** se overridea con `buildscript` |
| Compose compiler | **2.2.10** | plugin `org.jetbrains.kotlin.plugin.compose` (misma versión que Kotlin) |
| KSP | 2.3.14 | catálogo (`ksp`); aplicado en `:app` desde GIM-9 (genera los DAO de Room y exporta el schema) |
| Compose BOM | 2026.09.00 | catálogo (`composeBom`) |
| Room | 2.8.5 | catálogo (`room`); aplicado en `:app` desde GIM-9 (`runtime` + `ktx` + `room-compiler` por KSP + plugin `androidx.room` para exportar el schema) |
| activity-compose | 1.13.0 | catálogo |
| navigation-compose | 2.10.2 | catálogo |
| lifecycle-* | 2.11.0 | catálogo |
| androidx.core-ktx | 1.19.1 | catálogo |
| kotlinx-serialization-json | **1.9.0** | catálogo (`kotlinxSerialization`), `:core` (implementation) |
| kotlinx-datetime | 0.8.0 | `:core` (implementation) |
| Robolectric | 4.18 | catálogo; `testImplementation` en `:app` desde GIM-9 (tests de Room en JVM) |
| androidx.test:core | 1.7.0 | catálogo (`androidxTestCore`); `testImplementation` y `androidTestImplementation` en `:app` (`ApplicationProvider`, `InstrumentationRegistry`) |
| androidx.test:runner / rules | 1.7.0 / 1.7.0 | catálogo (`androidxTestRunner`, `androidxTestRules`); `androidTestImplementation` en `:app` (GIM-44) |
| androidx.test.ext:junit | 1.3.0 | catálogo (`androidxTestExtJunit`); `androidTestImplementation` en `:app` (`AndroidJUnit4`) |
| Emulador de CI | API 34, `google_apis`, `x86_64`, `pixel_5` | `.github/workflows/build.yml` job `instrumented` (GIM-44) |
| JUnit4 | 4.13.2 | `:core` (testImplementation) |
| JDK de build | **21** (temurin en CI, OpenJDK 21.0.13 en el Moto) | `setup-java` / host |
| Bytecode / `jvmTarget` | **17** | `compileOptions` en `:app`, `java` + `kotlin.compilerOptions` en `:core` |
| compileSdk | **37.0** (`compileSdk = 37` + `compileSdkMinor = 0`) | `app/build.gradle.kts` |
| targetSdk / minSdk | **36 / 26** | `app/build.gradle.kts` |
| Android SDK en CI | `platforms;android-37.0`, `build-tools;36.0.0` + `build-tools;37.0.0` | `android-actions/setup-android` |

Notas de la matriz:

- **AGP 9 tiene Kotlin built-in**: no se aplica `org.jetbrains.kotlin.android`
  (sería un error de configuración). `:app` aplica `com.android.application` +
  `org.jetbrains.kotlin.plugin.compose`.
- **El root `build.gradle.kts` está vacío a propósito.** Declarar KGP ahí (aunque
  sea con `apply false`) mete KGP en el classloader del proyecto raíz; el built-in
  Kotlin de AGP resuelve entonces `KotlinAndroidTarget` desde ese scope, donde las
  clases de AGP no son visibles, y el build muere con
  `NoClassDefFoundError: com.android.build.gradle.api.BaseVariant`. Cada módulo
  declara su plugin: `:core` `kotlin("jvm")`, `:app` `com.android.application` +
  `plugin.compose`.
- **Kotlin queda en 2.2.10, no en 2.4.21** (§5 del análisis técnico proponía 2.4.21).
  AGP 9.5.1 usa built-in Kotlin y construye un `KotlinAndroidTarget` de la KGP que
  trae. Con el upgrade documentado a KGP 2.4.21 el build muere al aplicar
  `com.android.application`:
  `NoClassDefFoundError: com.android.build.gradle.api.BaseVariant` en
  `BuiltInKotlinServices.initBuiltInKotlinSupport` (AGP 9 eliminó la API vieja de
  variantes). Por eso **no** se fuerza KGP desde el root y el plugin de Compose usa
  la misma versión que Kotlin (2.2.10). Es el primer punto donde la matriz se
  aparta de §5; el segundo es kotlinx-serialization-json (nota siguiente), que
  baja por la misma causa. El resto (Gradle, AGP, Compose BOM, Room, AndroidX,
  kotlinx-datetime) queda como estaba.
- **kotlinx-serialization-json queda en 1.9.0, no en 1.13.0** (§5 del análisis
  técnico proponía 1.13.0). La 1.13.0 se publicó con **metadata de Kotlin 2.4** y
  el compilador del repo es **2.2.10** (la KGP built-in de AGP 9.5.1, que no se
  puede overridear sin romper `BaseVariant`, ver nota anterior). Resultado: la
  compilación de `:core` muere al leer el `.class` de la librería con
  `Module was compiled with an incompatible version of Kotlin. The binary version
  of its metadata is 2.4.0, expected version is 2.2.0`. La 1.9.0 es la que compila
  con Kotlin 2.2.10 y cubre todo lo que usa `:core` (`@Serializable`, JSON con
  `encodeDefaults`, `ignoreUnknownKeys`). **Cuando suba Kotlin** (AGP que traiga
  built-in ≥ 2.4) se puede volver a subir la librería: cambiar
  `kotlinxSerialization` en `gradle/libs.versions.toml`, correr
  `make check` y actualizar esta fila y la de §5. El motivo está repetido como
  comentario en el propio catálogo, arriba de la entrada `kotlinxSerialization`.
- No se declara `toolchain(17)`: en el Moto solo existe JDK 21 y un toolchain 17
  obligaría a descargar otro JDK. El bytecode sale 17 igual (`jvmTarget` y
  `sourceCompatibility`/`targetCompatibility` = 17).
- **`compileSdk` sube a 37.0, `targetSdk` queda en 36.** El ticket pedía 36/36, pero
  `androidx.core:core-ktx:1.19.1` y el Compose BOM 2026.09.00 (compose 1.12.1)
  declaran `minCompileSdk=37` en su AAR metadata: con `compileSdk 36` el build
  falla en `:app:checkDebugAarMetadata` (13 issues). Se prefiere respetar las
  versiones pinneadas de la §5 antes que bajarlas. Detalle del SDK: la API 37 se
  publica como `platforms;android-37.0` (con minor), no como `android-37`; por eso
  `compileSdk = 37` va acompañado de `compileSdkMinor = 0` (si no, AGP busca
  `android-37` y falla "Failed to find target with hash string 'android-37'").
  `targetSdk` sigue en 36: compilar contra 37 no cambia el comportamiento de target.
- `minSdk 26` es el del ticket GIM-1.
- No hay dependencias fuera del catálogo.

## Gate local (`:core` sin Android SDK)

```bash
make check                     # = ./scripts/check-core.sh (guardas + medición)
./scripts/check-core.sh        # lo mismo, sin make
```

`make` tiene cuatro atajos, y ninguno miente sobre lo que corre:

| Target | Qué hace | Dónde corre |
|---|---|---|
| `make check` | `scripts/check-core.sh`: compila `:core` y corre sus tests con guardas | Moto |
| `make check-ci` | el paso que sí se puede correr acá de lo que hace CI (`:core:test`), e imprime los tres que no | Moto (parcial) |
| `make build` | no compila acá: imprime el `gh` exacto para disparar/leer/bajar el APK de CI | CI |
| `make stop` | `gradle --stop` (libera ~750 MB del daemon) | Moto |

`check-ci` es parcial por necesidad: `:app:lintDebug` y `:app:assembleDebug`
necesitan `aapt2`/D8 para linux-arm64, que no existe. Corre en CI.

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

Guardas del script (todas cortan de verdad; no son avisos):

| Guarda | Umbral | Qué hace |
|---|---|---|
| RAM libre | `< MIN_FREE_MB` (800 MB) | no arranca, `exit 1` |
| Temperatura al arrancar | `>= 55 °C` en la zona más caliente | no arranca, `exit 1` |
| Temperatura sostenida | `>= 58 °C` durante 5 s seguidos | **mata el build** (`SIGTERM` al cliente y al daemon), `exit 4` |
| Techo de tiempo | `> TIME_LIMIT_SECS` (180 s por defecto) | **mata el build**, `exit 3` |
| Tests | `:core:test` en rojo | `exit 2` (re-ejecuta con `--stacktrace` para ver la traza) |

Corre con `taskset -c 0-1`, daemon habilitado y `-Dorg.gradle.jvmargs=-Xmx768m`
(el `gradle.properties` del repo pide `-Xmx3g`, que es para CI). Fuerza
`:core:test --rerun` porque sin eso la segunda corrida sale `UP-TO-DATE` en 3 s
y no verifica nada: sólo se re-ejecuta el test, no el `compileKotlin`.

Medición: no hay `/usr/bin/time` en el equipo. El script muestrea cada segundo
`/proc/<pid>/statm` de los procesos `java` del build (árbol del cliente + daemon
+ worker de tests + daemon de Kotlin) para el RSS pico, y
`/sys/class/thermal/thermal_zone*/temp` para la temperatura. Al final imprime
tiempo total, RSS pico y temperatura (inicio, pico, final), y deja el log en
`/tmp/opencode/check-core-<fecha>-<pid>.log`.

Al terminar hace `gradle --stop` (el daemon se queda con ~750 MB si sigue vivo).
Para medir régimen sin pagar el arranque del daemon en cada corrida:
`KEEP_DAEMON=1 ./scripts/check-core.sh`, y al cerrar la sesión `make stop`.

Una corrida a la vez: si otra sesión tiene trabajo pesado en los núcleos 0-1,
los números no sirven (medido: 105 s en vez de 21 s por una compilación de otro
proyecto). El script no lo detecta; mirá la carga antes de medir.

### Medición sobre el `:core` real (2026-11-17, este equipo)

Equipo: 3.4 GiB RAM (1.8 GiB disponibles), 8 núcleos, `taskset -c 0-1`.

| Operación | Tiempo | RAM pico (procesos `java`) | Temp |
|---|---|---|---|
| `gradle -PcoreOnly wrapper` (primer arranque del daemon, resuelve KGP) | 2 m 25 s | — | 48 °C |
| `gradle -PcoreOnly :core:test` en frío con KGP 2.4.21 (baja kotlinx-datetime, JUnit y kotlin-test) | **1 m 22 s** | **755 MB** | 48 °C |
| `gradle -PcoreOnly :core:test` sin cambios (daemon caliente, KGP 2.4.21) | **9 s** | 755 MB | 49 °C |
| cambio a KGP 2.2.10: primera corrida (baja KGP 2.2.10, recompila todo) | **~6 min** (quedó cortada por tiempo de mi comando, el build terminó igual) | — | hasta 65 °C ⚠️ |
| segunda corrida con KGP 2.2.10, con el daemon ya caliente | 4 m 4 s (con el server de opencode y otro daemon de Kotlin compitiendo por los 8 núcleos) | 736 MB | 65 °C ⚠️ |
| `./scripts/check-core.sh` en HEAD (KGP 2.2.10, daemon arrancando, sin otra carga) | **54 s** | **499 MB** | 52 °C |

Conclusiones:

- El gate local **funciona**: `:core:test` compila y los tests pasan sin SDK. (En
  GIM-1 eran 2 tests; hoy son **112** — el conteo al día está en `README.md`
  §Estado real. Las mediciones de esta sección son de aquel momento, con 2 tests.)
- Las dos últimas corridas son pesadas y **no son el régimen**: se hicieron con
  KGP 2.2.10 entrando por primera vez y con el equipo ya cargado por otra sesión.
  El pico térmico de 65 °C supera el techo de la política local (58 °C) y es la
  razón por la que este ticket se verificó el APK en CI y no acá.
- La corrida final en HEAD (`./scripts/check-core.sh`, daemon arrancando, equipo
  sin otra carga) dio **54 s, 499 MB y 52 °C**: ese es el número que hay que usar
  como referencia hoy. Las corridas de 2m40s–4m4s fueron con el equipo cargado.
- Con KGP 2.2.10 el régimen "sin cambios" con el daemon ya caliente quedó medido
  en GIM-2 (tabla siguiente): **18–21 s** con `--rerun`, no los 3 s de una
  corrida `UP-TO-DATE`.
- El 499 MB de la fila de GIM-1 no es comparable con las 433–442 MB de abajo: se
  midió con un `ps` puntual y el daemon venía con el `-Xmx3g` de
  `gradle.properties`; el script actual suma `/proc/<pid>/statm` cada segundo con
  `-Xmx768m`.
- Pico de RAM de los procesos `java`: ~750 MB. Es más alto que la sonda de
  `docs/analisis-tecnico.md` (400 MB) porque ahí se medía el daemon solo; acá suma
  el worker de tests y el daemon de Kotlin.

### Medición del gate con las guardas puestas (GIM-2, 2026-11-17)

Todas con `./scripts/check-core.sh` (taskset 0-1, `-Xmx768m`, `:core:test --rerun`),
equipo sin otra carga, 2.1–2.5 GB de RAM libre al arrancar. El RSS es la suma de
los procesos `java` del build; la temperatura es la zona más caliente.

| Régimen | Cómo se provoca | Tiempo | RSS pico | Temperatura (inicio → pico → final) |
|---|---|---|---|---|
| **Frío** (compila todo) | `gradle --stop` + `rm -rf core/build` | **77–105 s** | 434–440 MB | 42,8 → **50,3** → 48,0 °C (arrancando a 42,8) |
| Sin cambios, **daemon nuevo** (uso normal del script, que hace `--stop` al final) | correr el script dos veces seguidas | **93–109 s** | 431–436 MB | 48,2 → **49,5** → 48,8 °C |
| Sin cambios, **daemon vivo** (régimen real de trabajo) | `KEEP_DAEMON=1` | **18–21 s** | 441–442 MB | 47,2 → **50,0** → 47,7 °C |
| Arranque de daemon solo (para entender la diferencia) | `gradle -PcoreOnly help` | 78 s en frío, 9,9 s con daemon vivo | — | — |

Las dos primeras filas varían con la temperatura de partida: la corrida fría de
77 s arrancó a 42,8 °C y la de 105 s a 53,4 °C (el equipo ya venía caliente), con
el mismo trabajo. La tercera fila es estable porque son 18 s.

Lo que dicen estos números:

- El **trabajo real** (`:core:test --rerun` con daemon vivo) es de **18–21 s**;
  el resto es arranque de JVM. En este equipo arrancar el daemon de Gradle
  cuesta ~78 s (JDK 21 inicializando Gradle 9.9.1 en 2 núcleos), y por eso el
  uso normal —que apaga el daemon al final— cuesta ~109 s. No es el test: es el
  bootstrap.
- Presupuesto: **régimen ≤ 30 s** (medido 18–21 s, 40 % de margen) y
  **frío ≤ 120 s** (medido 77–105 s; el tope cubre el arranque de daemon + compilación).
  El techo duro del script queda en **180 s** porque tiene que cubrir el peor caso
  legítimo medido (109 s) con margen, y fallar por encima de eso sí indica algo
  roto (resolución de dependencias, swap, equipo cargado).
- Para exigir el presupuesto de régimen en una corrida concreta:
  `KEEP_DAEMON=1 TIME_LIMIT_SECS=30 ./scripts/check-core.sh` → `exit 3` si el
  test en caliente pasa de 30 s. No se puede poner 30 s por defecto porque el
  uso normal (sin daemon) tarda 109 s y el gate fallaría siempre.
- Térmico: el peor pico medido en régimen limpio fue **50,3 °C** (frío). Con el
  equipo ya cargado por otra sesión se vieron **60,4 °C** y, en el cierre de
  GIM-1, 65 °C; por eso el aborto a 58 °C sostenidos existe y mata el build.

Las guardas se probaron forzando los umbrales, no sólo leyendo el código:

| Prueba | Resultado |
|---|---|
| `MIN_FREE_MB=999999 ./scripts/check-core.sh` | `ABORTA — RAM libre 2133 MB < 999999 MB`, `exit 1` |
| `START_MAX_TEMP_C=1 ./scripts/check-core.sh` | `ABORTA — 49,0 °C >= 1 °C`, `exit 1` |
| guarda real, sin forzar: arranque a **55,0 °C** | `ABORTA — 55,0 °C >= 55 °C`, `exit 1` (y no quedó daemon) |
| `ABORT_TEMP_C=1 TEMP_SUSTAIN_SECS=2` | mata el build a los 3 s, `exit 4`, cero procesos `java` después |
| `TIME_LIMIT_SECS=3` | mata el build a los 6 s, `exit 3` |
| test rojo inyectado (`assertTrue(false)`) | `BUILD FAILED`, re-ejecuta con `--stacktrace`, `exit 2` |

## CI (GitHub Actions)

`.github/workflows/build.yml`, disparadores `push` a `master`, `pull_request` y
`workflow_dispatch`:

1. `actions/checkout@v7`
2. `actions/setup-java@v6` — temurin **21**
3. `gradle/actions/setup-gradle@v6` — caché de `~/.gradle`
4. `android-actions/setup-android@v4` — `platforms;android-37.0` + `build-tools;36.0.0` y `37.0.0`
5. `./gradlew :core:test`
6. `./gradlew :app:testDebugUnitTest` — tests JVM de `:app`, **sin emulador**
7. `./gradlew :app:lintDebug`
8. `./gradlew :app:assembleDebug`
9. `actions/upload-artifact@v7` — **name `gimcheck-debug.apk`**,
   `path: app/build/outputs/apk/debug/app-debug.apk`, retención 30 días
10. `actions/upload-artifact@v7` con `if: failure()` — **name `gimcheck-test-reports`**
    (`core/build/reports/tests/` y `app/build/reports/tests/`, 7 días). Sólo se
    crea cuando el run falla; es la forma de ver qué test rompió sin bajar el log.

Y un **segundo job** (`instrumented`, GIM-44) que corre en un emulador real:

1. `actions/checkout@v7`, JDK 21, caché de Gradle, `android-actions/setup-android@v4`.
2. Habilita `/dev/kvm` (el emulador x86_64 necesita aceleración; sin KVM arranca en
   modo software y el timeout de 40 min no alcanza).
3. `reactivecircus/android-emulator-runner@v2` — API 34, `google_apis`, `x86_64`,
   `pixel_5`, sin ventana/audio, `swiftshader_indirect`.
4. `./gradlew :app:connectedDebugAndroidTest` — tests de `app/src/androidTest/`.
5. Reportes `app/build/reports/androidTests/` si falla.

El job instrumentado **no es opcional**: es la única verificación de que el overlay
`TYPE_APPLICATION_OVERLAY` se dibuja encima de otra app y de que la alarma exacta
despierta el receptor sin UI. Si ese job está rojo, el run está rojo.

### Tests unitarios de `:app`

`:app:testDebugUnitTest` corre en JVM (sin emulador; los instrumentados son el job
`instrumented` aparte) y **es un paso obligatorio del run**: un test rojo en `:app` pone
el run en rojo y se saltea el resto. Antes de GIM-35 no existía ningún test de `:app`,
así que todo lo que venga de DATOS/UI (GIM-9, 10, 11, 20, 21, 24, 25…) se habría
cerrado con evidencia que no se ejecutaba.

| Pieza | Estado hoy |
|---|---|
| Runner | JUnit4 (`testImplementation(libs.junit4)` en `app/build.gradle.kts`) |
| Tests | `app/src/test/java/` — `MainActivitySkeletonTest` (3, esqueleto) + los de Room (GIM-9, ver abajo) |
| Robolectric | **activo** (4.18): Room necesita `Context`/`Instrumentation` reales y no hay emulador |
| Recursos Android en tests | `testOptions.unitTests.isIncludeAndroidResources = true` (Robolectric carga recursos y assets de la variante) |
| `BuildConfig` | activado (`buildFeatures.buildConfig = true`) porque el test verifica `BuildConfig.APPLICATION_ID` |

### Tests instrumentados (GIM-44)

`app/src/androidTest/java/app/gimcheck/rest/RestAlertDeviceTest` corre en el **emulador
API 34 del job `instrumented`**, no en el Moto. Verifica lo que Robolectric no puede:

| Test | Qué prueba |
|---|---|
| `canalDeFinDeDescansoConImportanciaAlta` | el canal `rest_done_v2` efectivo queda en `IMPORTANCE_HIGH` (una importancia menor degrada el aviso a heads-up) |
| `overlayApareceEncimaDeOtraAppConElTelefonoDesbloqueado` | con `SYSTEM_ALERT_WINDOW` concedido por `appops`, Ajustes del sistema en primer plano y el teléfono desbloqueado: `dumpsys window` muestra una ventana `APPLICATION_OVERLAY` de `app.gimcheck` y el diagnóstico registra `overlay` |
| `laAlarmaExactaDespiertaElReceptorYDejaLaNotificacion` | `scheduleSelfTest` programa la alarma, dispara `RestAlarmReceiver` sin UI y deja la notificación del canal (`dumpsys notification`) |

El permiso de overlay y el de alarmas exactas se conceden con `appops set … allow` desde
el propio test (`UiAutomation`), que es la misma operación que el usuario hace en
Ajustes: así la prueba no depende de tocar la pantalla ni del dueño. La prueba de un
toque de Ajustes (`RestAlertCenter.scheduleSelfTest`) programa el mismo camino a 10 s.

Los tests de `:app` con Robolectric siguen sin emulador: el emulador se usa sólo para
este job.

### Tests de Room (GIM-9)

Van en `app/src/test/java/app/gimcheck/data/db/` y corren en `:app:testDebugUnitTest`
con **Robolectric 4.18**, la misma JVM del runner. No usan emulador: los tests
instrumentados de este repo son sólo los del aviso de descanso (sección anterior).

| Test | Qué fija |
|---|---|
| `SchemaCreationTest` | la base se crea **desde cero (v1)** con `MigrationTestHelper.createDatabase(1)`; tablas, columnas, FK con CASCADE e índices comparados contra el JSON exportado; el JSON está en `app/schemas/` y en los assets de debug |
| `IntegrityRulesTest` | reglas de §2: borrar ejercicio limpia `day_item` y recalcula `superset_next` del último, `superset_next` nunca en el último, `sets ∈ 1..20`, `factor ∈ [0,1]`, una sola sesión `ACTIVE`, borrar rutina no borra sesiones |
| `DaoContractTest` | lectura con `Flow` (se actualiza con cada escritura), orden por `position`, `moveItem`, ajustes key/value, métrica con fecha natural |
| `MainThreadQueryTest` | una consulta bloqueante en el hilo principal lanza `IllegalStateException`; las escrituras `suspend` sí corren desde el hilo principal |

Dos piezas de build que sostienen lo anterior (en `app/build.gradle.kts`):

- `alias(libs.plugins.androidx.room)` + `room { schemaDirectory("$projectDir/schemas") }` +
  `exportSchema = true`: **el plugin oficial de Room** es el que deja el schema versionado
  en `app/schemas/<clase>/<version>.json` cuando el procesador corre por KSP (Room 2.8
  escribe primero en `build/` y el plugin copia). Sin el plugin el JSON no aparece y
  `MigrationTestHelper` falla con `FileNotFoundException`. El JSON **se commitea**, y el
  workflow falla si el generado difiere del commiteado (paso *Schema de Room commiteado*).
- `sourceSets["debug"].assets.srcDir("$projectDir/schemas")`: `MigrationTestHelper`
  lee el JSON con `context.assets.open(...)`; sólo la variante `debug` (y los tests)
  lo llevan, el APK de release no.

Al subir la versión del esquema hay que agregar `Migration(n, n+1)`, su test
(`createDatabase(n)` + `runMigrationsAndValidate(n+1, listOf(...))`) y el JSON nuevo.
El detalle está en `docs/adr/0003-persistencia-room.md`.

El primer test de esqueleto es de verdad, no un `assertTrue(true)`: valida que el
`applicationId` generado por el build es `app.gimcheck`, que `MainActivity` vive
en ese package, que extiende `ComponentActivity`, que es pública, concreta y
declara `onCreate(Bundle)`.

Los tests van en `app/src/test/java/` (y no `src/test/kotlin/`) a propósito: es
el directorio de fuentes que AGP siempre registra; Kotlin compila `.kt` ahí sin
configuración extra.

Verificación de que el paso está cableado (2026-11-18, GIM-35),
con la prueba de rojo y verde hecha en `master`:

| Run | Commit | Resultado | Qué pasó |
|---|---|---|---|
| [44229165259](https://github.com/joacomedel/gimcheck/actions/runs/44229165259) | `997d591` | **verde**, 2 m 25 s | ejecutó `:app:testDebugUnitTest` de verdad: **3 tests, 0 fallos** en 18 s. El total se estiró porque `setup-android` tardó 60 s en esa instancia del runner |
| [44229535632](https://github.com/joacomedel/gimcheck/actions/runs/44229535632) | `ed9b9b2` (temporal) | **rojo**, 58 s | `RojoTemporalTest > rojo FAILED` (`AssertionError`): el job corta en el paso *Tests unitarios de :app*, `:app:lintDebug` y `:app:assembleDebug` quedan **skipped** y se sube `gimcheck-test-reports` |
| [44229838339](https://github.com/joacomedel/gimcheck/actions/runs/44229838339) | `13e9aa7` (revert) | **verde**, 1 m 2 s | el revert deja el árbol igual al de `997d591`, así que Gradle resuelve la tarea `FROM-CACHE` (no re-ejecuta) y publica el APK igual que antes |
| [44230183427](https://github.com/joacomedel/gimcheck/actions/runs/44230183427) | `e7c6a7c` | **verde**, 1 m 1 s | total de referencia con este documento: `:core:test` 18 s, `:app:testDebugUnitTest` 5 s (`FROM-CACHE`), lint 16 s, APK 3 s |

El commit rojo **no quedó en `master`**: se revirtió con `git revert` (`13e9aa7`).
Ojo con la caché: `gradle/actions/setup-gradle` cachea los resultados de tareas,
así que un run que no cambia las entradas de `:app` muestra
`:app:testDebugUnitTest FROM-CACHE` en vez de re-ejecutar los tests. Eso no es un
hueco —la clave de caché incluye fuentes, classpath y versión—, pero al citar
evidencia hay que usar un run que **ejecutó** la tarea (como el `997d591`), no uno
que la sacó de caché. Costo del paso nuevo medido en el primer run: **18 s**
(compila `:app` + los tests y los corre). El total del run queda en **54 s – 1 m 2 s**
con caché caliente, contra ~50 s antes de GIM-35: el paso agrega del orden de
**10–18 s**, muy por debajo de los ~4 minutos que habrían hecho falta mirar.

Detalle que muerde al escribir tests de `:app`: el jar que AGP entrega al test
JVM (`app/build/intermediates/runtime_app_classes_jar/...`) marca `onCreate`
como **protected** aunque en el APK sea `public`. `Class.getMethod("onCreate",
Bundle)` lanza `NoSuchMethodException`; hay que usar `getDeclaredMethod`. No usar
`Modifier.isPublic` sobre métodos de `ComponentActivity` en tests JVM.

Run de referencia de GIM-35 (2026-11-18): [44229838339](https://github.com/joacomedel/gimcheck/actions/runs/44229838339)
en `success` sobre `master` en `13e9aa7` (1 m 2 s), con `:core:test` ✓,
`:app:testDebugUnitTest` ✓ (3 tests), `:app:lintDebug` ✓, `:app:assembleDebug` ✓ y el artifact
`gimcheck-debug.apk` publicado.

Verificación anterior, sin tests de `:app` (2026-11-17): run
[44203803099](https://github.com/joacomedel/gimcheck/actions/runs/44203803099) en `success` sobre
`master` en `965bcdb` (1 m 19 s), con `:core:test` ✓, `:app:lintDebug` ✓, `:app:assembleDebug` ✓
y artifact `gimcheck-debug.apk` de **11.359.283 B** (11,36 MB decimales / 10,83 MiB), que contiene
`app-debug.apk` de 11.826.237 B (120 entradas, multidex: `classes.dex` 18,6 MB + `classes6.dex`
10,8 MB).

El run de referencia es **siempre el último verde del HEAD**, no el de esta cita:
`gh run list --repo joacomedel/gimcheck --workflow build.yml --limit 5`. Corridas anteriores que
no hay que citar como estado actual: `44203129493` (`89120ce`), `44183688530` (`2eb25a7`),
`44181579353` (`c0286cd`).

Cómo se genera el APK: `assembleDebug` compila `:core`, compila `:app` (Kotlin
built-in de AGP + Compose compiler 2.2.10), procesa recursos con `aapt2`,
dexea con D8 y firma con `ci/gimcheck-debug.keystore` (clave fija commiteada;
ver `app/build.gradle.kts`). La clave fija es a propósito: la debug key
autogenerada por cada runner es distinta y Android rechaza instalar un APK
sobre otro firmado con otra clave. Es una clave de **prueba**, no la de
release (GIM-30). El archivo queda en `app/build/outputs/apk/debug/app-debug.apk`.

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
  ticket el dueño autorizó el push (ver el ticket GIM-1) y se hizo con
  `git -C <repo> push`.
