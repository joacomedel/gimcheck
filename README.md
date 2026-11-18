# GimCheck

App Android de rutinas de gimnasio. Port nativo de `Rutinas de Gimnasio.html` (SPA vanilla de
54 KB, esquema `rutinas-gym-v1` v21) a Kotlin + Jetpack Compose, con sesión guiada, cronómetro de
descanso que toma la pantalla y gráficos propios.

Repo: [`joacomedel/gimcheck`](https://github.com/joacomedel/gimcheck) (público), rama `master`.

## Estado real (2026-11-18)

**El dominio está completo y verificado contra la web. La app todavía no tiene UI: el APK
que se publica es un cascarón que muestra el texto "GimCheck".**

| Pieza | Estado |
|---|---|
| `:core` (dominio Kotlin puro) | **completo para v1**: modelo y migrador de la web (`v1..v21`), `dayStats` con series efectivas y volumen por músculo, conflictos de superserie y pares multiarticulares, e1RM (Epley/Brzycki) + PR por serie, máquina de estados de la sesión guiada, validadores del editor, export/import JSON (backup v1 y plan de la web). **112 tests**, paridad con la web verificada en navegador real (`docs/paridad-web.md`) |
| `:app` (Android) | `MainActivity` que pinta el texto "GimCheck". **Cero pantallas, cero ViewModel, cero Room** |
| Las 9 pantallas | **0 de 9** (ver tabla abajo) |
| Room / persistencia | no aplicado (dependencia en el catálogo, `KSP` sin usar). El único I/O es el JSON de `:core`, todavía sin cablear a la UI |
| Alertas de descanso, servicio en primer plano, alarmas | no existen |
| Gráficos | no existen |
| CI | verde: `:core:test` (112 tests) + `:app:lintDebug` + `:app:assembleDebug` + artifact `gimcheck-debug.apk` |
| Gate local (`:core`) | verde, con guardas de RAM/temperatura/tiempo |

En una frase: lo que se puede probar hoy es **la lógica**, con `make check`; lo que todavía
no se puede probar es **usar la app**, porque no hay nada que tocar más allá del texto
inicial. Ninguna de las 9 pantallas está empezada.

Lo que hay que saber antes de tocar código: este equipo **no compila Android** (no existe
`aapt2` para linux-arm64). El APK se compila en CI; localmente sólo corre el `:core`.

## Stack (el que compila hoy, no el que se propuso)

| Pieza | Versión |
|---|---|
| Gradle | 9.9.1 |
| Android Gradle Plugin | 9.5.1 |
| Kotlin (KGP) | 2.2.10 — la que trae AGP 9.5.1 built-in; **no** se overridea |
| Compose compiler | 2.2.10 |
| Compose BOM | 2026.09.00 |
| compileSdk | **37.0** (`compileSdk = 37` + `compileSdkMinor = 0`) |
| targetSdk / minSdk | 36 / 26 |
| JDK de build | 21 (host y CI); bytecode 17 |
| KSP / Room | 2.3.14 / 2.8.5, declarados, **sin aplicar** |
| kotlinx-serialization-json | **1.9.0**, no 1.13.0: la 1.13 trae metadata de Kotlin 2.4 y el compilador es 2.2.10 (razón en [`docs/build.md`](docs/build.md)) |
| kotlinx-datetime | 0.8.0 |

Tabla completa con el porqué de cada pin y las trampas de matriz: [`docs/build.md`](docs/build.md).

## Cómo se compila (sólo en CI)

Un push a `master` (o `workflow_dispatch`) dispara `.github/workflows/build.yml` en
`ubuntu-latest`: `:core:test` → `:app:lintDebug` → `:app:assembleDebug` → artifact
`gimcheck-debug.apk` con retención de 30 días.

```bash
make build        # no compila acá: imprime los comandos gh exactos
gh workflow run build.yml --repo joacomedel/gimcheck --ref master
gh run list --repo joacomedel/gimcheck --workflow build.yml --limit 5
```

Última corrida verde al escribir esto: run
[44203803099](https://github.com/joacomedel/gimcheck/actions/runs/44203803099), `success`, 1 m 19 s,
artifact de 11.359.283 B (11,36 MB / 10,83 MiB). Para el estado actual, siempre
`gh run list --repo joacomedel/gimcheck --workflow build.yml --limit 5`.

No hay `connectedAndroidTest`, no hay emulador (ni acá ni en CI) y no se puede correr
`:app:assembleDebug` en el Moto. Cualquier instrucción que diga lo contrario está vieja.

## Cómo se instala el APK

Tres caminos, de más nuevo a más viejo:

1. **Desde el navegador del teléfono**: abrir el run verde, sección *Artifacts*, descargar
   `gimcheck-debug.apk` e instalar (Android pide permitir "instalar apps desconocidas" para el
   navegador o el gestor de archivos).
2. **Desde este equipo**:

   ```bash
   gh run download $(gh run list --repo joacomedel/gimcheck --workflow build.yml \
     --limit 1 --json databaseId -q '.[0].databaseId') \
     --repo joacomedel/gimcheck -n gimcheck-debug.apk -D /tmp/opencode/apk
   ```

   El artifact contiene un único archivo `app-debug.apk` (~11,8 MB). Se copia al teléfono por
   `adb`/MTP/lo que haya y se instala a mano.
3. **Copia local ya bajada**: `/root/dist/gimcheck/gimcheck-debug.apk` (build del 2026-11-17
   23:01, el mismo APK del run citado). Sirve para reinstalar sin red.

El APK está firmado con el **debug keystore** autogenerado por AGP: se sideloadea, no se publica.
Reinstalar sobre otra copia con distinta firma exige desinstalar antes.

## Gate local (sin Android SDK)

```bash
make check                  # = ./scripts/check-core.sh
./scripts/check-core.sh     # compila :core y corre :core:test con guardas
```

Lo que corre de verdad es:

```bash
taskset -c 0-1 gradle --daemon -Dorg.gradle.jvmargs=-Xmx768m -PcoreOnly :core:test --rerun
```

`-PcoreOnly` hace que `settings.gradle.kts` aplique `settings-core.gradle.kts`, que incluye
**sólo `:core`** y no evalúa `app/build.gradle.kts` (por eso no hace falta el SDK ni AGP).
**No existe `-c settings-core.gradle.kts`**: Gradle 9 eliminó `-c`/`--settings-file` y
`-b`/`--build-file`.

Presupuesto medido en este equipo (Moto G20, arm64, 3.4 GiB RAM):

| Régimen | Tiempo | RSS pico | Temperatura |
|---|---|---|---|
| sin cambios, daemon vivo (uso normal con `KEEP_DAEMON=1`) | 18–21 s | ~440 MB | ~50 °C |
| frío (daemon apagado + `rm -rf core/build`) | 77–105 s | 434–440 MB | hasta 50,3 °C |

Guardas del script (cortan, no avisan): RAM libre < 800 MB, arranque ≥ 55 °C, 58 °C sostenidos
5 s, techo de 180 s, test en rojo. Al final hace `gradle --stop`: el daemon se queda con ~750 MB.

Otros atajos: `make check-ci` (el paso de CI que sí corre acá, sin guardas), `make build`
(imprime los `gh` para disparar y bajar el APK), `make stop` (`gradle --stop`).

Detalle de todo esto, mediciones y pruebas de cada guarda: [`docs/build.md`](docs/build.md).

## Las 9 pantallas

Ninguna está implementada. El contrato visual (tokens, componentes, jerarquía) está en
[`docs/diseno.md`](docs/diseno.md); el contenido funcional en
[`docs/especificacion.md`](docs/especificacion.md) §6.

| # | Pantalla | Qué hace | Estado |
|---|---|---|---|
| 1 | Hoy | rutina y día elegidos, peso corporal del día, empezar/continuar sesión, últimos 3 entrenamientos | sin empezar |
| 2 | Entrenar | bloque actual, superserie guiada, peso × reps, "Serie hecha", cuenta regresiva de descanso | sin empezar |
| 3 | Descanso terminado | pantalla completa que aparece sola: próximo ejercicio + "Empezar" | sin empezar |
| 4 | Rutinas | planes, días, ítems del día, advertencias de superserie, duplicar/eliminar, exportar | sin empezar |
| 5 | Ejercicios | catálogo con filtro por músculo, ficha editable, lista de músculos | sin empezar |
| 6 | Progreso | PR/e1RM, volumen, peso corporal, series por músculo (Canvas propio) | sin empezar |
| 7 | Historial | sesiones por fecha con volumen/duración/series, detalle corregible | sin empezar |
| 8 | Comparar | dos planes lado a lado (paridad con la web) | sin empezar |
| 9 | Ajustes | tiempos, rango semanal, alertas, fórmula e1RM, unidades, backup/restore, tema | sin empezar |

Navegación: barra inferior con 5 destinos (Hoy, Rutinas, Progreso, Historial, Ajustes);
Entrenar es modal y pide confirmación al salir.

## Mapa de docs

| Archivo | Para qué |
|---|---|
| [`docs/especificacion.md`](docs/especificacion.md) | fuente de verdad del producto: alcance, modelo de datos, reglas de cálculo, flujo de sesión, ajustes |
| [`docs/diseno.md`](docs/diseno.md) | contrato visual: tokens, tipografía, componentes, reglas de composición y tabla de las 9 pantallas |
| [`docs/build.md`](docs/build.md) | matriz de versiones, gate local con mediciones, CI, cómo se genera el APK, trampas del entorno |
| [`docs/analisis-tecnico.md`](docs/analisis-tecnico.md) | por qué nativo y por qué CI (mediciones de `aapt2`, RAM y temperatura). §5 (dependencias) está **superado por `docs/build.md`** |
| [`docs/adr/0001-stack-kotlin-compose.md`](docs/adr/0001-stack-kotlin-compose.md) | stack: Kotlin + Compose nativo |
| [`docs/adr/0002-build-en-ci.md`](docs/adr/0002-build-en-ci.md) | el APK se compila en CI (repo público → minutos ilimitados) |
| [`docs/adr/0003-persistencia-room.md`](docs/adr/0003-persistencia-room.md) | Room + export/import JSON |
| [`docs/adr/0004-alerta-descanso.md`](docs/adr/0004-alerta-descanso.md) | alerta de fin de descanso en tres capas |
| [`docs/adr/0005-graficos-canvas.md`](docs/adr/0005-graficos-canvas.md) | gráficos propios con Canvas |
| [`docs/adr/0006-dominio-puro-core.md`](docs/adr/0006-dominio-puro-core.md) | dominio puro en `:core`, testeable sin SDK |

Orden de lectura para arrancar: este README → `docs/especificacion.md` → `docs/diseno.md` →
`docs/build.md`.

## Estructura

```text
core/                        dominio Kotlin puro (:core), sin Android
app/                         aplicación Android (:app)
scripts/check-core.sh        gate local con guardas
Makefile                     check / check-ci / build / stop
settings.gradle.kts          incluye :core y :app; con -PcoreOnly aplica settings-core.gradle.kts
settings-core.gradle.kts     settings reducido: sólo :core
gradle/libs.versions.toml    catálogo de versiones (única fuente)
docs/                        especificación, diseño, build, análisis técnico y ADRs
.github/workflows/build.yml  CI: tests + lint + APK + artifact
Rutinas de Gimnasio.html     la web original, como referencia de paridad
```

## Lo que no existe (y no hay que prometer)

- Emulador: no hay, ni acá ni en CI (sin KVM, emulador x86 sin aceleración es inusable).
- Tests instrumentados / `connectedAndroidTest`: no hay y no son el gate.
- Tests de UI Compose: Robolectric está en el catálogo, sin usar.
- Release firmado, Play Store, tags/releases automáticos: fuera de v1.
- Build de Android en este equipo: imposible (`aapt2` arm64 no existe).
- Alarmas de descanso, vibración y sonido: **sólo se pueden probar en el teléfono del dueño**.
  Cualquier ticket que las toque se cierra con `evidence_kind: manual` y dice qué mirar.

## Licencia

Proyecto personal. **No hay licencia otorgada**: el código queda con todos los derechos
reservados por defecto (se puede leer porque el repo es público, no se puede redistribuir ni
reutilizar). **No hay archivo `LICENSE`** a propósito.

Estado: pendiente de ratificación por el dueño. Hay una solicitud abierta en AgentBank
(`4455c8e9-6261-4050-b318-05b2f8abbe22`, asunto "Licencia del repo público
joacomedel/gimcheck", default = sin licencia) con las opciones sin licencia / MIT / MIT con el
nombre reservado. Si el dueño elige MIT, es agregar `LICENSE` y cambiar esta sección; nada del
código depende de eso.
