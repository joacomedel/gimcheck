# ADR 0003 — Persistencia local con Room y export/import por JSON

- **Estado**: vigente (revisado en GIM-9: esquema v1, DAOs y decisión Room vs DataStore)
- **Contexto**: la web guarda todo en `localStorage` con esquema `rutinas-gym-v1` v21 y una
  función de migración hecha a mano. Los requerimientos van a cambiar (más métricas, más ajustes).
- **Decisión**: Room (SQLite) con entidades normalizadas, migraciones versionadas y **tests de
  migración** desde cada versión previa. Backup y restauración por JSON
  (`schema: "gimcheck", version: N`), con reemplazo o fusión por id. Se mantiene además un
  importador del JSON de la web (v21) para no perder los datos actuales del dueño.
- **Consecuencias**: no hay nube ni sync. Cambiar el modelo implica migración + test, no borrar
  la base. El archivo de backup es la vía de escape y de mudanza de teléfono.

## Detalle de la implementación (GIM-9)

### Todo va a Room; no hay DataStore

Los ajustes de §1.1 (calentamiento, traslado, bajadas de drop set, rango semanal,
`includeIndirect`, `e1rmFormula`, `bodyweightVolume`) viven en **`app_setting(key, value)`**, con
el valor en JSON laxo y el tipado en Kotlin (repositorio, GIM-10).

Por qué no DataStore:

- **Una sola fuente de verdad y una sola transacción.** El backup/restore necesita leer el
  catálogo, las rutinas y los ajustes en un mismo instante consistente; con dos motores hay dos
  fotos distintas y hay que coordinarlas a mano.
- **El modelo cambia seguido.** Un ajuste nuevo es una fila (`INSERT` de una clave) o una clave
  más dentro del JSON del grupo; en DataStore Preferences implica tocar el esquema tipado y, en
  Proto DataStore, regenerar y migrar.
- **El export ya es JSON.** El backup se arma con `kotlinx.serialization` sobre `:core`; los
  ajustes entran como un objeto más, sin conversión extra.
- **Costo**: un DataStore agrega otro motor (archivo propio, corrutinas propias, otro ciclo de
  vida) para guardar ~10 valores que ya tienen lugar en la base.

Lo único que podría pedir DataStore es una lectura sincrónica antes del primer frame; no la
necesitamos: los ajustes se leen una vez al arrancar y se mantienen en un `StateFlow` del
repositorio.

### Esquema v1

Once tablas de `docs/especificacion.md` §2: `muscle`, `exercise`, `exercise_secondary`,
`routine`, `routine_day`, `day_item`, `session`, `session_set`, `session_state`, `body_metric`,
`app_setting`. `id TEXT` (UUID) salvo las claves naturales `body_metric.date` y `app_setting.key`.
Los campos salen del dominio de `:core` (`Models.kt`, `Routine.kt`, `Sessions.kt`) — es la fuente
de verdad, no se inventaron campos; `exercise.notes` y `routine.notes` son los dos que §2 declara
y el dominio todavía no usa.

Decisiones de tipos:

- **Instantes en `INTEGER` epoch millis UTC** (`session.started_at`/`ended_at`,
  `session_set.completed_at`, `session_state.rest_ends_at`/`updated_at`,
  `body_metric.updated_at`). `:core` trabaja con `LocalDateTime` local; el repositorio convierte
  (ya lo documenta `core/Sessions.kt`). Consultar por semana/día es aritmética de enteros.
- **`routine.created_at` en `TEXT` ISO-8601**, porque es procedencia del import de la web y puede
  venir vacío (no es un instante válido).
- **Enums como `TEXT` con el nombre** (`load_type`, `status`): reordenar o renombrar un enum no
  cambia el significado de las filas.

### Claves foráneas: dónde sí y dónde no

- `exercise.primary_muscle_id → muscle` **RESTRICT**: no se borra un músculo en uso.
- `exercise_secondary → exercise`/`muscle` **CASCADE**.
- `routine_day → routine`, `day_item → routine_day`, `day_item → exercise`,
  `session_set → session`, `session_state → session` **CASCADE**.
- `session.routine_id` / `session.routine_day_id` **sin FK**: §2 pide que borrar una rutina no
  borre el histórico ("rutina eliminada"); un `SET NULL` perdería el id y con él la referencia.
- `session_set.day_item_id` / `exercise_id` **sin FK y nullable**: el histórico de series tiene
  que sobrevivir al borrado del ítem o del ejercicio.

Las FK llevan índice (`primary_muscle_id`, `routine_id`, `day_id`, `exercise_id`, `muscle_id`,
`session_id`); la PK compuesta `(exercise_id, muscle_id)` ya cubre su prefijo.

### Integridad que no puede expresar el esquema

Room no declara `CHECK` desde anotaciones y SQLite no tiene índices únicos parciales portables
(`status = 'ACTIVE'` único). Las tres reglas viven en los DAO, dentro de transacciones, y tienen
test:

| Regla (§2) | Dónde |
|---|---|
| `exercise_secondary.factor ∈ [0,1]` y el principal no es secundario | `ExerciseDao.upsertWithSecondaries` |
| `day_item.sets ∈ 1..20` | `DayDao.addItem` / `updateItem` |
| `superset_next` nunca `true` en el último ítem | `DayDao.addItem`/`removeItem`/`moveItem`/`setSupersetNext` + `ExerciseDao.deleteExerciseCascade` |
| borrar un ejercicio lo saca de `day_item` y recalcula el último | `ExerciseDao.deleteExerciseCascade` (una transacción) |
| una sola `session` `ACTIVE` | `SessionDao.startSession` |

### Escrituras y concurrencia

- Lecturas: `Flow` (o `suspend` puntual). Escrituras: `suspend`. **Ningún DAO bloqueante**, y la
  base nunca se construye con `allowMainThreadQueries`: una consulta bloqueante en el hilo
  principal lanza `IllegalStateException` (test `MainThreadQueryTest`).
- Las escrituras usan `@Upsert`, **no** `@Insert(onConflict = REPLACE)`: REPLACE borra e inserta la
  fila y, con `ON DELETE CASCADE`, se llevaría los hijos (un ejercicio re-guardado perdería sus
  `day_item`).
- `position` es sólo el orden de ejecución y **no se renumera al borrar**: los huecos no afectan a
  `ORDER BY position` y evitan pisarse entre escrituras. El "último ítem" es `MAX(position)`.

### Versionado y migraciones

- `exportSchema = true`; los JSON quedan versionados en `app/schemas/<clase>/<version>.json` y se
  commitean. Se exportan con el **plugin oficial de Room** (`androidx.room`), que es el que copia
  el schema que KSP escribe en `build/`; `app/build.gradle.kts` lo declara con
  `room { schemaDirectory(...) }`. Los JSON también se copian a los assets de `debug` para que
  `MigrationTestHelper` los lea en tests JVM.
- **v1 es la creación**: Room no ejecuta una `Migration(0, 1)`. Una instalación nueva abre la base
  vacía y llama `onCreate` con el esquema compilado; el test de creación es
  `MigrationTestHelper.createDatabase(1)` + verificación de tablas, columnas, FK e índices contra
  el JSON exportado (`SchemaCreationTest`).
- **Desde la v2**, cada bump del esquema obliga a: `Migration(n, n+1)` en `AppDatabase`, test
  `createDatabase(n)` + `runMigrationsAndValidate(n+1, listOf(MIGRATION_N_N1))` y el JSON nuevo
  commiteado. Sin eso el build de tests falla y el auditor rechaza. No hay migración destructiva
  (`fallbackToDestructiveMigration` prohibido): el teléfono del dueño tiene los datos reales.

### Tests

Todo es JVM con **Robolectric 4.18** (`:app:testDebugUnitTest`): no hay emulador ni en este equipo
ni en CI, y no hay `connectedAndroidTest`. Ver `docs/build.md`.
