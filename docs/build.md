# Build y verificación — GimCheck

Dos niveles, porque este equipo no puede compilar Android (ver `docs/analisis-tecnico.md` §3):

| Nivel | Dónde | Comando | Qué cubre |
|---|---|---|---|
| L0/L1 (lógica) | Moto | `scripts/check-core.sh` | `:core` (dominio + máquina de estados + JSON), sin Android SDK |
| L2 (APK) | GitHub Actions | push a `master` / PR | `:core:test` + `lint` + `assembleDebug` + artifact `gimcheck-debug.apk` |
| L3 (dispositivo) | teléfono del dueño | checklist `docs/qa.md` | instalación, alertas de descanso, vibración, sonido, cronómetro con app cerrada |

## Toolchain

| Pieza | Versión | Nota |
|---|---|---|
| JDK | OpenJDK 21.0.13 (arm64, Debian) | instalado 2026-11-17, 287 MB en disco |
| Gradle | 9.9.1 | wrapper en el repo |
| AGP | 9.5.1 | fallback documentado: 8.13.2 |
| Kotlin | 2.4.21 | |
| KSP | 2.3.14 | Room |
| Android SDK | API 36 (platform + build-tools) | **solo en CI**; no se instala acá |
| minSdk / targetSdk | 26 / 36 | |

## Medición del costo local (2026-11-17)

Contexto del equipo al medir: 3.4 GiB RAM (2.1 GiB disponibles), `soc-thmzone` 33–36 °C,
8 núcleos, `taskset -c 0-1`.

Sonda real: proyecto `kotlin("jvm")` 2.4.21 + Gradle 9.9.1 con 3 tests (port de `groups()` y
`dayStats()` del HTML). Medido con `taskset -c 0-1` y muestreo de `/proc/*/statm`.

| Operación | Tiempo | RAM pico | Temp |
|---|---|---|---|
| `apt-get install openjdk-21-jdk-headless` | 20,3 s | — | 36,2 → 36,2 °C |
| `gradle test` **en frío** (descarga plugin Kotlin + JUnit, sin caché) | **2 m 31 s** | — | 35,1 °C |
| `gradle test` recompilando tests, `--no-daemon` | **1 m 03 s** | 398 MB (java+gradle) | 35,7 °C |
| `gradle test` con daemon, primer run tras compilar | **25,4 s** | 325 MB (daemon idle) | 36,2 °C |
| `gradle test` con daemon, sin cambios | **3,3 s** | 325 MB | 36,2 °C |

Conclusiones medidas:

- El gate local **es barato**: pico 400 MB (no 1,5 GB como se estimó) y la temperatura no se movió
  (33–36 °C en todo el proceso). Cabe en el presupuesto del equipo con margen.
- El régimen útil es **con daemon** (3–25 s). `--no-daemon` cuesta ~1 min por corrida y no libera
  casi nada porque igual forkea una JVM de un solo uso.
- Recomendación: dejar el daemon prendido durante una sesión de trabajo y liberarlo con
  `gradle --stop` al terminar (deja 325 MB tomados si queda vivo).
- Gradle 9.9.1 quedó instalado en `/root/tools/gradle-9.9.1` (fuera de tmpfs: `/tmp` es RAM).
- La caché de dependencias vive en `~/.gradle` (213 MB tras la sonda): el primer build del repo
  real ya no vuelve a descargar el plugin de Kotlin.

> El ticket GIM-2 repite esta medición sobre el `:core` real del repo y ajusta el presupuesto del
> script (techo sugerido: fallar si `:core:test` en caliente pasa de 30 s).

## Gate local

```bash
./scripts/check-core.sh          # taskset 0-1, daemon, -Xmx768m, aborta si temp > 58 °C
```

Presupuesto medido en la sonda (2026-11-17): **3,3 s sin cambios / 25 s recompilando / 1 min en
frío**, pico **~400 MB**, temperatura estable en 33–36 °C. Guardas: no arranca con menos de 800 MB
de RAM libre ni con la zona térmica más caliente ≥ 55 °C; aborta si se sostiene ≥ 58 °C. Una
corrida a la vez (nada de builds en paralelo con otras sesiones). Al cerrar la sesión de trabajo:
`gradle --stop`.

## CI (GitHub Actions)

- Runner `ubuntu-latest` (x86_64, 4 vCPU, 16 GB) — el único lugar donde existe `aapt2`.
- Caché de Gradle con `gradle/actions/setup-gradle`; el SDK de Android se instala con
  `android-actions/setup-android`.
- Artifact `gimcheck-debug.apk` en cada push a `master` (retención 30 días).
- Release con APK adjunto en cada tag `v*`; `versionName` = tag, `versionCode` = run number.
- Repositorio **público** → minutos ilimitados (decisión del dueño 2026-11-17, ADR 0002).

## Trampas del entorno

- No hay `unzip` (usar `python3 -c "import zipfile; zipfile.ZipFile(...).extractall('.')"`).
- No hay `/usr/bin/time` (medir RSS con `python3 -c "import resource; ..."` sobre `RUSAGE_CHILDREN`).
- `/tmp` es tmpfs: no descomprimir el SDK ahí.
- El primer `gradle test` de un módulo nuevo descarga el plugin de Kotlin (~90 MB) y tarda
  minutos; no confundir eso con el régimen (medir frío y caliente).
