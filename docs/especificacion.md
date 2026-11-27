# GimCheck — Especificación funcional v1

> Fuente de verdad del producto. Deriva de `Rutinas de Gimnasio.html` (app web local, esquema
> `rutinas-gym-v1`, versión de datos **v21**) más los requerimientos nuevos del dueño
> (2026-11-17). Todo lo que dice la web tiene que existir en la app: **no se pierde nada**.

## 1. Alcance

### 1.1 Lo que la web ya hace (paridad obligatoria)

- **Planes (rutinas)**: varios planes por usuario, crear vacío, duplicar, renombrar, eliminar
  (nunca el último), elegir plan activo.
- **Días** dentro de un plan: agregar, duplicar, renombrar, eliminar, reordenar implícitamente
  por posición; chips de día con estado activo.
- **Catálogo de ejercicios**: nombre, músculo principal (peso 1), músculos secundarios con
  factor 0–1, segundos por serie, segundos de descanso, flag *multiarticular*. Crear, editar,
  eliminar (con impacto en los días), agregar/quitar secundarios.
- **Músculos**: lista editable (agregar músculo nuevo).
- **Ítems de día**: ejercicio, cantidad de series (1–20), *drop set*, *superserie con el
  siguiente*, subir/bajar, quitar, agregar.
- **Advertencias en el editor**: dos multiarticulares en la misma superserie; superserie cuyos
  ejercicios comparten músculo principal o secundario.
- **Métricas del día**: duración estimada (trabajo + descanso + calentamiento y traslados),
  series totales, minutos de trabajo/descanso.
- **Análisis semanal**: series directas e indirectas por músculo, banda de referencia
  (`optMin`–`optMax`), estado por músculo (muy bajo / bajo / en rango / alto), hallazgos
  (músculos fuera de rango, entrenados un solo día, día más largo/corto, ahorro por
  superseries, aporte de los drop sets, conflictos).
- **Comparar** dos planes: tiempo semanal, series, multiarticulares, % multiarticular, series
  de brazo, drop sets, superseries, conflictos, duración por día, series por músculo.
- **Ajustes**: calentamiento min/día, traslado entre ejercicios, bajadas de drop set, segundos
  por bajada, series que suma cada bajada, rango mínimo/máximo semanal, volumen indirecto
  on/off.
- **Exportar plan a JSON** y **backup/restore completo** (texto JSON, copiar/pegar).

### 1.2 Lo nuevo (pedido del dueño)

1. **Hoy**: elegir rutina → elegir día → anotar la sesión del día actual.
2. **Anotar series**: peso × repeticiones (o peso corporal / lastre), marcar hecha, editar y
   corregir.
3. **Superserie guiada**: al anotar una serie de un ejercicio con `superserieConSiguiente`, la
   app me lleva directo a anotar el ejercicio siguiente de la superserie (sin descanso).
4. **Descanso automático**: al terminar la última serie de la ronda (o el último ejercicio del
   bloque) arranca un contador. Cuando termina, **toma control de la pantalla** y me manda a la
   app con "descanso terminado, arrancá a entrenar". Configurable entre **pantalla / vibración /
   sonido / combinaciones**.
5. **Gráficos**: PR estimado (e1RM) por ejercicio, volumen total, peso corporal por día.
6. **Requerimientos cambiantes**: catálogo y configuración siempre editables; esquema migrable;
   sin datos hardcodeados en la UI.
7. **Diseño**: simple pero cuidado, sin apariencia de "app generada por IA".

### 1.3 No-goals de v1

- Cuentas, nube, sync multi-dispositivo (export/import por archivo es suficiente).
- Integración con wearables / Health Connect.
- Videos o fotos de ejercicios.
- Multi-usuario o perfiles.
- iOS.

## 2. Modelo de datos

Persistencia local **Room** (SQLite). Todas las tablas con `id TEXT` (UUID v4) para poder
exportar/importar sin colisiones, salvo métricas diarias que usan fecha como clave natural.

```
muscle(id, name, position, archived)
exercise(id, name, primary_muscle_id, seconds_per_set, rest_seconds, compound,
         load_type, notes, position, archived)
        load_type ∈ { EXTERNAL, BODYWEIGHT }   // BODYWEIGHT = peso corporal + lastre opcional
exercise_secondary(exercise_id, muscle_id, factor)   PK(exercise_id, muscle_id)

routine(id, name, notes, position, created_at, archived)
routine_day(id, routine_id, name, position)
day_item(id, day_id, exercise_id, position, sets, drop_set, superset_next,
         rest_override_seconds?, target_reps_min?, target_reps_max?)

session(id, routine_id, routine_day_id, started_at, ended_at?, status, notes,
        body_weight_kg?)
        status ∈ { ACTIVE, FINISHED, ABANDONED }
session_set(id, session_id, day_item_id, exercise_id, round_index, set_index,
            weight_kg, reps, rpe?, is_drop_set, is_warmup, completed_at, note?)
session_state(session_id PK, block_index, round_index, item_index, rest_ends_at?,
              rest_seconds?, updated_at)      // supervivencia a procesos muertos

body_metric(date PK 'YYYY-MM-DD', weight_kg, body_fat_pct?, note?, updated_at)
app_setting(key PK, value)                       // JSON laxo, tipado en Kotlin
```

Reglas de integridad:

- `exercise_secondary.factor ∈ [0,1]`; el principal siempre suma 1.
- `day_item.superset_next` en el último ítem de un día siempre `false`.
- Borrar un ejercicio lo quita de todos los `day_item` y recalcula `superset_next` del último.
- Borrar una rutina no borra sesiones históricas (`routine_id` queda huérfano, se muestra
  "rutina eliminada").
- `session.status = ACTIVE` único por dispositivo (no se pueden tener dos sesiones abiertas;
  la segunda pregunta si descartar la primera o continuarla).

## 3. Reglas de cálculo (idénticas a la web)

Notación: `S` = ajustes, `items` = ítems del día en orden.

```
grupos(items):  agrupa ítems consecutivos donde item.superset_next = true
                (un ítem sin superserie forma un grupo de 1)

dayStats(day):
  por grupo con miembros m:
    rondas = max(m[i].sets)
    para r en 0..rondas-1:
      trabajo += Σ (m[i].seconds_per_set si m[i].sets > r)
                 + (S.drops * S.dropSec si m[i].drop_set y r == m[i].sets-1)
      descansoRonda = max(rest_seconds de los que participan en r)
      descanso += descansoRonda ; ultimoDescanso = descansoRonda
    series += Σ m[i].sets
  descanso -= ultimoDescanso                      // el último descanso no se cuenta
  otro = (nGrupos - 1) * S.trans + S.warm * 60
  total = trabajo + descanso + otro

seriesEfectivas(item) = item.sets + (item.drop_set ? S.drops * S.dropVol : 0)

volumen(días):
  para cada día di y cada ítem:
    e = seriesEfectivas(item)
    principal: d += e ; díaConVolumen[di] = true
    cada secundario: indirectas += e * factor ;
                     si e*factor >= 1 → díaConVolumen[di] = true
  freq(músculo) = cantidad de días con díaConVolumen

estado(valor):
  valor < 0.5*S.optMin → "▼ Muy bajo" (bad)
  valor < S.optMin     → "▼ Bajo" (warn)
  valor ≤ S.optMax     → "✓ En rango" (good)
  si no                → "▲ Alto" (warn)
```

`includeIndirect` decide si el estado y los totales usan `d` o `d + i`.

### 3.1 PR estimado (nuevo)

- e1RM por serie (solo series de trabajo, `weight_kg > 0` y `1 ≤ reps ≤ 12`):
  - Epley (default): `w * (1 + reps/30)`
  - Brzycki: `w * 36 / (37 - reps)`
  - Fórmula elegida en Ajustes (`e1rmFormula = EPLEY | BRZYCKI`).
- PR de un ejercicio = máximo e1RM histórico.
- PR de la sesión = máximo e1RM de esa sesión; se marca con badge "PR" cuando supera el
  máximo previo a esa serie (comparación con el histórico anterior al set).
- Sesiones con `reps > 12` no generan e1RM (poco fiable) pero sí volumen.

### 3.2 Volumen total (nuevo)

- Volumen de sesión (kg) = `Σ weight_kg * reps` de las series no warmup.
- Si `load_type = BODYWEIGHT` y el ajuste `bodyweightVolume = true`, se suma el peso corporal
  del día (`session.body_weight_kg` o el `body_metric` de la fecha) × reps.
- Agregados: por sesión, por semana ISO, por día calendario, por músculo (series efectivas,
  misma fórmula que la web).

## 4. Flujo de la sesión guiada (núcleo)

La sesión se arma con los ítems del día. Los ítems se agrupan en **bloques** (superseries).
Cada bloque tiene `rondas = max(sets de sus ítems)`.

Estado persistido: `session_state(block_index, round_index, item_index, rest_ends_at)`.

```
máquina de estados (core, pura y testeable):

SET_LOGGED(weight, reps)
  guarda session_set(round, item)
  si item_index < bloque.size-1:
      item_index++                       → pantalla del siguiente de la superserie, SIN descanso
  si no:
      descansoRonda = max(rest de los ítems que participan en esta ronda)
      si round_index+1 < rondas:
          item_index = 0 ; round_index++  ; EMPIEZA_DESCANSO(descansoRonda)
      si no:
          bloque terminado                ; el descanso es el de la última ronda cerrada
          si hay bloque siguiente:        ; EMPIEZA_DESCANSO(descansoRonda)
              block_index++ ; round = 0 ; item = 0
          si no:                          → FIN_DE_SESION

  "participa en esta ronda" = item.sets > round_index (la misma regla que `dayStats`);
  nunca es el máximo de todo el bloque. Ej.: A = 3 series / 90 s y B = 2 series / 120 s:
  la ronda 1 descansa 120 (participan A y B) y la ronda 3, que cierra el bloque con A
  solo, descansa 90. Con un bloque siguiente, ese descanso de la última ronda es el
  que se usa para pasar al bloque nuevo.

EMPEZA_DESCANSO(segundos)
  rest_ends_at = now + segundos (o rest_override_seconds del ítem si existe)
  arranca el servicio de cronómetro + alarma exacta de respaldo
  pantalla muestra cuenta regresiva (skip, +15 s, -15 s, "empezar ya")

DESCANSO_TERMINADO
  → aplica el modo de alerta configurado (§5) y deja el cursor en el próximo ítem
  → nada se pierde si la app estaba cerrada: al abrir, si rest_ends_at < now, entra
    directamente al estado DESCANSO_TERMINADO
```

Reglas finas:

- Un ítem con `sets` menor que las rondas del bloque no participa en las rondas extra
  (coherente con `dayStats`).
- Series de calentamiento (`is_warmup`) no cuentan para volumen ni PR, y no disparan descanso
  largo (usan `rest * 0.5`, redondeado a 15 s).
- Si el usuario cierra la app, el cronómetro sigue (servicio en primer plano) y la notificación
  muestra el tiempo restante.
- Corregir una serie ya anotada no cambia el cursor (solo los datos).

### 4.1 Máquina de estados implementada en `:core`

`core/src/main/kotlin/app/gimcheck/core/SessionMachine.kt`:
`reduce(state, event) -> SessionTransition(state, effects)`. Función **pura**: no lee
el reloj (el `now` entra en el evento), no toca disco ni notificaciones; lo que hay
que hacer sale como lista de efectos. Tests: `SessionMachineTest.kt`.

Estado (`GuidedSession`): `day` (el plan; los bloques se derivan con `groups`, la misma
regla que `dayStats`), `sessionId`, `startedAt`, `status`, `blockIndex`, `roundIndex`,
`itemIndex`, `restEndsAt`, `restSeconds` y `sets` (`LoggedSet` = ítem + ronda +
`SessionSet`). `status`: `NOT_STARTED → RUNNING ⇄ RESTING → FINISHED | ABANDONED`.

Tipos de tiempo: `restEndsAt` es un instante **absoluto** (`Instant`; Room lo guarda
como epoch millis) para que `Tick` y `Restore` comparen sin zona ni DST.
`SessionSet.completedAt` sigue siendo hora local (`LocalDateTime`), que es como
trabaja el resto del dominio; el llamador la pasa en `LogSet.at`.

Eventos: `StartSession(sessionId, now)`, `LogSet(weightKg, reps, isWarmup, at, now)`,
`SkipRest`, `ExtendRest(deltaSecs)`, `Tick(now)`, `Restore(now)`, `FinishSession`,
`AbandonSession`.

Efectos posibles: `PersistState`; `CancelRest` / `StartRest(seconds)`; `RestFinished` /
`SessionFinished` / `ShowRestDone`; `ShowNextInSuperset` / `ShowNextExercise`. `PersistState`
va siempre primero y la tabla da el orden exacto del resto en cada transición.

| Estado | Evento | Estado nuevo | Efectos |
|---|---|---|---|
| `NOT_STARTED` | `StartSession`, día con ítems | `RUNNING` en (bloque 0, ronda 0, primer ítem) | PersistState |
| `NOT_STARTED` | `StartSession`, día sin ítems | `FINISHED` | PersistState, SessionFinished |
| `NOT_STARTED` | cualquier otro | sin cambio | — |
| `RUNNING` | `LogSet` y quedan ítems del bloque en la ronda | `RUNNING`, `itemIndex++` | PersistState, ShowNextInSuperset |
| `RUNNING` | `LogSet` que cierra la ronda y hay ronda siguiente | `RESTING`, `roundIndex++`, `itemIndex = 0`, descanso de la ronda cerrada | PersistState, ShowNextExercise, StartRest |
| `RUNNING` | `LogSet` que cierra el bloque y hay bloque siguiente | `RESTING`, `blockIndex++`, ronda 0, ítem 0 | PersistState, ShowNextExercise, StartRest |
| `RUNNING` | `LogSet` que cierra el último bloque | `FINISHED`, sin descanso | PersistState, SessionFinished |
| `RUNNING` | `LogSet` de calentamiento | `RUNNING` (cursor igual) o `RESTING` si el descanso > 0 | PersistState [, StartRest(mitad)] |
| `RUNNING` | `Tick`, `SkipRest`, `ExtendRest`, `Restore` | sin cambio | — |
| `RESTING` | `LogSet` (anotar sin esperar) | igual que en `RUNNING`, cancelando antes el descanso | CancelRest, PersistState, … |
| `RESTING` | `Tick(now)` con `now < restEndsAt` | sin cambio | — |
| `RESTING` | `Tick(now)` con `now ≥ restEndsAt` | `RUNNING`, limpia el descanso | PersistState, RestFinished |
| `RESTING` | `SkipRest` | `RUNNING`, limpia el descanso | PersistState, CancelRest |
| `RESTING` | `ExtendRest(±s)` | `RESTING`, `restSeconds+s`, `restEndsAt+s` | PersistState, StartRest(nuevo) |
| `RESTING` | `ExtendRest` que deja ≤ 0 s | `RUNNING`, limpia el descanso | PersistState, CancelRest |
| `RESTING` | `ExtendRest(0)` | sin cambio | — |
| `RESTING` | `Restore(now)` con `now < restEndsAt` | sin cambio (se reanuda) | StartRest(lo que falta) |
| `RESTING` | `Restore(now)` con `now ≥ restEndsAt` | `RUNNING`, limpia el descanso, cursor intacto | PersistState, RestFinished, ShowRestDone |
| `RUNNING` o `RESTING` | `FinishSession` | `FINISHED` | PersistState [, CancelRest], SessionFinished |
| `RUNNING` o `RESTING` | `AbandonSession` | `ABANDONED` | PersistState [, CancelRest] |
| `FINISHED` o `ABANDONED` | cualquier evento | sin cambio | — |

Reglas de implementación que la tabla no muestra:

- **Descanso de la ronda** = máximo de `restOverrideSeconds ?: exercise.rest_seconds`
  entre los ítems que **participaron** en la ronda cerrada (`sets > roundIndex`). Al
  cerrar el bloque se usa el de su última ronda: en un bloque 3+2 descansa sólo el ítem
  de 3, igual que en `dayStats`. Un ítem que ya terminó sus series no aporta descanso.
- **Dentro del bloque no hay descanso**: sólo cambia el cursor (`ShowNextInSuperset`).
  El cursor salta los ítems que no participan en la ronda (`firstParticipant`), así una
  ronda extra con un solo ítem se cierra en cuanto ese ítem anota.
- **Calentamiento**: `warmupRestSeconds(rest)` = `rest / 2` redondeado a 15 s con
  `Math.round` (`120 → 60`, `90 → 45`, `100 → 45`, `20 → 15`); con 0 no abre descanso.
  No mueve el cursor ni cuenta para volumen/PR (eso lo resuelve `:core` con
  `SessionSet.isWarmup`).
- **Anotar durante el descanso es "empezar ya"**: cancela el cronómetro y sigue desde
  el cursor que ya apuntaba al próximo ítem.
- **Eventos fuera de estado son no-op** (mismo estado, sin efectos): el servicio de
  cronómetro puede mandar `Tick` cada segundo sin mirar el estado.
- **Ids deterministas**: `"<sessionId>/<itemId>/<n>"` con `n` = series previas de ese
  ítem. Un replay del mismo evento no inventa ids.
- **`Restore` no cambia el cursor**: sólo ajusta el descanso. Si venció mientras el
  proceso estaba muerto, entra a `RUNNING` emitiendo `ShowRestDone` para que la pantalla
  muestre el aviso.

## 5. Alerta de fin de descanso (configurable)

`alertMode ∈ { SCREEN, VIBRATE, SOUND, SCREEN_VIBRATE, SCREEN_SOUND, VIBRATE_SOUND,
SCREEN_VIBRATE_SOUND, NONE }`, default `SCREEN_VIBRATE`.

- **SCREEN**: notificación con *full-screen intent* + actividad de pantalla completa
  `RestDoneActivity` ("Descanso terminado / Empezar serie"). Pide los permisos necesarios y
  explica por qué; si el sistema los niega, cae a overlay (`SYSTEM_ALERT_WINDOW`) y, si tampoco
  hay, a notificación heads-up de alta prioridad. Nunca falla en silencio: siempre hay
  notificación con sonido/vibración del canal.
- **VIBRATE**: patrón configurable (corto / largo / doble), default doble.
- **SOUND**: tono del canal de notificación (o `RingtoneManager` si el canal no suena),
  seleccionable entre 3 opciones del sistema.
- Si la app está **en primer plano**, no se roba la pantalla: suena/vibra y la pantalla de
  sesión muestra el aviso inline (evita el efecto "me secuestró el teléfono").
- `restAlertRepeat`: repetir la alerta cada N segundos hasta interactuar (default 0 = una vez).

Restricciones de Android que hay que respetar (API 34+/35+/36):

- `USE_FULL_SCREEN_INTENT` solo se concede automáticamente a apps de llamadas/alarma; en el
  resto se pide con `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`.
- `SCHEDULE_EXACT_ALARM`: no se asume; se chequea `canScheduleExactAlarms()` y se ofrece
  habilitarlo; si no está, el servicio en primer plano mantiene el cronómetro.
- El servicio en primer plano declara tipo `specialUse` con
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` = "rest timer / workout session".
- `POST_NOTIFICATIONS` se pide en el primer arranque en contexto (al empezar la primera sesión).

## 6. Pantallas

| # | Pantalla | Contenido |
|---|---|---|
| 1 | **Hoy** | Saludo con fecha, peso corporal del día (input rápido), rutina y día elegidos, tarjetas de duración/series, "Entrenar" o "Seguir entrenando (N min)" (ADR 0007), últimos 3 entrenamientos |
| 2 | **Entrenar** | Bloque actual, indicador de superserie (1/2), ejercicio, rondas x/y, peso y reps con steppers grandes, "Serie hecha", progreso de la sesión, descanso con cuenta regresiva, fin de sesión |
| 3 | **Descanso terminado** | Pantalla completa: "Descansá hasta acá" + próximo ejercicio/serie + "Empezar" (cierra y vuelve a Entrenar) |
| 4 | **Rutinas** | Lista de planes, plan activo, días, editor del día con ítems, advertencias, duplicar/eliminar, exportar |
| 5 | **Ejercicios** | Lista del catálogo con filtro por músculo, ficha editable, músculos |
| 6 | **Progreso** | Pestañas: PR/e1RM, Volumen, Peso corporal, Series por músculo; selector de ejercicio y rango |
| 7 | **Historial** | Sesiones por fecha con volumen/duración/series, detalle corregible |
| 8 | **Comparar** | Dos planes lado a lado (paridad web) |
| 9 | **Ajustes** | Tiempos, rango, alertas, fórmula e1RM, unidades, backup/restore, tema, acerca de |

Navegación: barra inferior con 5 destinos (Hoy, Rutinas, Progreso, Historial, Ajustes) y
acceso a Entrenar como destino modal (no se pierde por accidente: pide confirmación al salir).

## 7. Import / export

- `export_backup`: JSON `{schema: "gimcheck", version: 1, exportedAt, settings, muscles,
  exercises, routines, sessions, bodyMetrics}`.
- `import_backup`: valida, migra y ofrece **reemplazar** o **fusionar** (por id, last-write-wins
  con `updated_at`).
- `export_plan`: mismo formato que la web (`app: "rutinas-gimnasio", tipo: "plan", version: 1`)
  para no romper compatibilidad.
- `import_web`: acepta el JSON completo de la web (`rutinas-gym-v1`, hasta v21) y lo convierte
  al modelo nuevo (mantiene ids, migra `secondary` a tabla, `drops/trans/warm` a ajustes).
  **Requisito del dueño: no perder los datos que ya tiene en el navegador.**

## 8. Ajustes (todos persistentes)

| Clave | Tipo | Default | Notas |
|---|---|---|---|
| `warm` | min | 5 | calentamiento por día |
| `trans` | s | 30 | traslado entre ejercicios |
| `drops` | int | 2 | bajadas por drop set |
| `dropSec` | s | 25 | duración de cada bajada |
| `dropVol` | float | 0.5 | series que suma cada bajada |
| `optMin` / `optMax` | int | 10 / 20 | rango semanal por músculo |
| `includeIndirect` | bool | false | volumen indirecto en análisis |
| `e1rmFormula` | enum | EPLEY | o BRZYCKI |
| `weightStep` | kg | 2.5 | paso de los steppers |
| `unit` | enum | KG | KG o LB (solo presentación) |
| `alertMode` | enum | SCREEN_VIBRATE | §5 |
| `restSound` | enum | DEFAULT | 3 opciones |
| `restVibration` | enum | DOUBLE | patrón |
| `restAlertRepeat` | s | 0 | repetición |
| `autoFillLast` | bool | true | precargar peso/reps de la última vez |
| `bodyweightVolume` | bool | false | sumar peso corporal al volumen |
| `theme` | enum | SYSTEM | SYSTEM / LIGHT / DARK |
| `keepScreenOn` | bool | true | pantalla encendida durante la sesión |

## 9. Criterios de aceptación globales de v1

1. Todo lo de la web existe y da los mismos números (tests de paridad sobre los datos de
   ejemplo del HTML: 6 días, 32 ejercicios, duración y volumen por músculo).
2. Se puede completar una sesión real de principio a fin sin tocar la base de datos, con
   superserie guiada y descanso que avisa con la pantalla si está configurado así.
3. Los tres gráficos existen y se alimentan de datos reales anotados.
4. Importar el backup de la web no pierde ni un ejercicio ni un día.
5. APK instalable descargable desde GitHub (artifact o release) con versión visible en Ajustes.
