# GimCheck

App Android de rutinas de gimnasio. Port nativo de `Rutinas de Gimnasio.html` (SPA vanilla de
54 KB, esquema `rutinas-gym-v1` v21) a Kotlin + Jetpack Compose, con sesión guiada, cronómetro de
descanso que toma la pantalla y gráficos propios.

Repo: [`joacomedel/gimcheck`](https://github.com/joacomedel/gimcheck) (público), rama `master`.

## Estado real (2026-11-27)

**La app se usa de punta a punta**: Hoy, Entrenar (libre), Rutinas con editor, Ejercicios,
Historial, Progreso (PR / volumen / peso), Comparar y Ajustes. Falta la verificación en el
teléfono del dueño (QA) y el release por tag.

| Pieza | Estado |
|---|---|
| `:core` (dominio Kotlin puro) | **completo para v1**: modelo y migrador de la web (`v1..v21`), `dayStats`, volumen por músculo, superseries, e1RM (Epley/Brzycki) + PR, sesión guiada histórica, validadores, export/import JSON (backup, plan y **pack de rutinas**) y agregados de Progreso. **137 tests**, paridad con la web verificada (`docs/paridad-web.md`) |
| `:app` (Android) | Room v1 + 5 repositorios, seed con las dos rutinas del dueño, 9 pantallas, 6 ViewModels, cronómetro de descanso en primer plano y alerta en tres capas |
| Pantallas | **9 de 9 implementadas**; la auditoría de diseño formal es GIM-15 |
| Gráficos | Canvas propio (`ui/chart`): línea con área y PR, barras de volumen, barras por músculo con `Meter` |
| Entrenar | **entrenamiento libre** (ADR 0007): sin "empezar sesión", selector de ejercicio, superserie como salto puntual, descanso persistido por deadline |
| Historial | lista por semana ISO, detalle, corrección de series y borrado |
| Rutinas | planes, días, editor, duplicar/eliminar y **transferencia**: exportar todas, exportar un plan e importar un pack, un plan de la web o un backup |
| Ajustes | 19 ajustes de §8 conectados, tema, alertas y **export de backup**; import del backup de la web |
| Release / QA | pendientes: GIM-30 (tag y APK de release), GIM-31 (QA en el teléfono), GIM-32 (guía de instalación) |
| CI | `:core:test` + `:app:testDebugUnitTest` + `:app:lintDebug` + `:app:assembleDebug` y artifact `gimcheck-debug.apk` |
| Gate local (`:core`) | verde, con guardas de RAM/temperatura/tiempo |

En una frase: lo que se puede probar hoy es **la lógica** (`make check`) y **la app**
instalando el APK del último run verde; lo que falta es que el dueño recorra el checklist
de `docs/qa.md` (GIM-31) en su teléfono, sobre todo el aviso de fin de descanso con la
pantalla apagada.

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
| KSP / Room | 2.3.14 / 2.8.5, aplicados (schema v1 en `app/schemas`) |
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

Última corrida del job principal verde al escribir esto: run
[46018755480](https://github.com/joacomedel/gimcheck/actions/runs/46018755480), job
`:core:test + :app:test + lint + assembleDebug` en `success`, artifact de 13.550.308 B
(13,55 MB / 12,92 MiB). El workflow tiene además el job de emulador (GIM-44), que puede
tardar más o fallar por infraestructura sin afectar el APK: para el estado actual, siempre
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

Las 9 están implementadas; lo que falta es el QA visual en el teléfono (GIM-31) y la
auditoría de diseño contra el contrato (GIM-15). El contrato visual (tokens, componentes,
jerarquía) está en [`docs/diseno.md`](docs/diseno.md); el contenido funcional en
[`docs/especificacion.md`](docs/especificacion.md) §6.

| # | Pantalla | Qué hace | Estado |
|---|---|---|---|
| 1 | Hoy | rutina y día, peso corporal del día, entrenamiento abierto, últimos 3 entrenamientos | implementada |
| 2 | Entrenar | entrenamiento libre (ADR 0007): selector de ejercicio, peso × reps, "Serie hecha", descanso con ±15 s | implementada |
| 3 | Descanso terminado | aparece sola al vencer el descanso, con la alerta en las tres capas | implementada; falta QA con pantalla apagada |
| 4 | Rutinas | planes, días, editor, advertencias, duplicar/eliminar y export/import de rutinas | implementada |
| 5 | Ejercicios | catálogo con búsqueda, filtro, alta/edición y músculos secundarios | implementada |
| 6 | Progreso | PR/e1RM, volumen, peso corporal, series por músculo (Canvas propio) | implementada |
| 7 | Historial | sesiones por semana ISO con volumen/duración/series, detalle corregible | implementada |
| 8 | Comparar | dos planes lado a lado (paridad con la web) | implementada |
| 9 | Ajustes | tiempos, rango semanal, alertas, fórmula e1RM, tema, backup | implementada; unidades kg/lb es GIM-50 |

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
| [`docs/adr/0007-entrenamiento-libre.md`](docs/adr/0007-entrenamiento-libre.md) | Entrenar es un entrenamiento libre del día, no una sesión guiada |
| [`docs/revision-ux.md`](docs/revision-ux.md) | revisión de UX 2026-11-27: qué se cerró, qué falta y qué mirar en el APK |

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
