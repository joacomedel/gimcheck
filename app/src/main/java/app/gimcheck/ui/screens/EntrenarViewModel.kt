package app.gimcheck.ui.screens

import app.gimcheck.AppContainer
import app.gimcheck.core.DayItem
import app.gimcheck.core.Exercise
import app.gimcheck.core.LoadType
import app.gimcheck.core.Routine
import app.gimcheck.core.Session
import app.gimcheck.core.SessionSet
import app.gimcheck.core.formatDuration
import app.gimcheck.core.formatNumber
import app.gimcheck.core.historicalBestByExercise
import app.gimcheck.core.restSecondsFor
import app.gimcheck.core.sessionE1rm
import app.gimcheck.core.sessionVolume
import app.gimcheck.core.sessionVolumeByMuscle
import app.gimcheck.core.supersetNextExercise
import app.gimcheck.core.warmupRestSeconds
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.RoutineRepository
import app.gimcheck.data.repository.SessionRepository
import app.gimcheck.data.repository.SettingsRepository
import app.gimcheck.data.settings.AppSettings
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime

/**
 * Gancho del **cronómetro de descanso** (`docs/especificacion.md` §4, GIM-22).
 *
 * La pantalla muestra la cuenta regresiva desde el deadline, pero el servicio en primer
 * plano y la alarma exacta de respaldo viven fuera de la pantalla (paquete
 * `app.gimcheck.rest`, ADR 0004). El [EntrenarViewModel] sabe **cuándo** hay que arrancar,
 * cortar o dar por terminado un descanso y los publica acá; nunca decide el tiempo.
 */
interface RestGateway {

    /**
     * Hay que (re)programar el cronómetro hasta [endsAt] con un total de [seconds] (para
     * el progreso de la notificación).
     */
    fun onStartRest(seconds: Int, endsAt: Instant)

    /** Hay que detener el cronómetro y la alarma de respaldo. */
    fun onCancelRest()

    /** El descanso llegó a cero: avisar según `alertMode` (§5). */
    fun onRestFinished()

    /** Nadie escucha: el cronómetro es sólo la cuenta regresiva de la pantalla. */
    object Noop : RestGateway {
        override fun onStartRest(seconds: Int, endsAt: Instant) = Unit
        override fun onCancelRest() = Unit
        override fun onRestFinished() = Unit
    }
}

/** Fase de la pantalla: entrenando, terminado (resumen) o sin catálogo. */
enum class EntrenarPhase { NO_SESSION, ACTIVE, FINISHED }

/** Cuenta regresiva del descanso, ya en segundos (desde el deadline absoluto). */
data class EntrenarRestUi(
    val remainingSeconds: Int,
    val totalSeconds: Int,
)

/** Una serie anotada, lista para `SetRow`. */
data class EntrenarSetUi(
    val id: String,
    val number: Int,
    val weightLabel: String,
    val repsLabel: String,
    val isWarmup: Boolean,
)

/** Las series anotadas de un ejercicio, en orden de ejecución. */
data class EntrenarSetsUi(
    val itemId: String,
    val exerciseName: String,
    val sets: List<EntrenarSetUi>,
)

/** Un ejercicio elegible en el selector del nombre (GIM-49). */
data class EntrenarPickerItemUi(
    val exerciseId: String,
    val name: String,
)

/** Un día de una rutina con sus ejercicios distintos: agrupación del selector. */
data class EntrenarPickerGroupUi(
    val routineId: String,
    val routineName: String,
    val dayName: String,
    val items: List<EntrenarPickerItemUi>,
)

/**
 * Estado del selector de ejercicio. [showingAll] distingue la lista corta (rutina activa)
 * de la larga (todas las rutinas, la acción `+`).
 */
data class EntrenarPickerUi(
    val showingAll: Boolean,
    val groups: List<EntrenarPickerGroupUi>,
)

/** Un PR del día: la mejor serie del ejercicio que superó su máximo previo. */
data class EntrenarPrUi(
    val exerciseId: String,
    val exerciseName: String,
    val setLabel: String,
    val e1rmLabel: String,
)

/** Una barra de volumen por músculo del día (`sessionVolumeByMuscle` de `:core`). */
data class EntrenarMuscleUi(
    val muscleId: String,
    val name: String,
    val setsLabel: String,
    val fraction: Float,
)

/** Resumen de fin de entrenamiento, calculado con `:core` sobre lo guardado. */
data class EntrenarSummaryUi(
    val dayName: String,
    val durationLabel: String,
    val volumeLabel: String,
    val setsLabel: String,
    val prs: List<EntrenarPrUi>,
    val muscles: List<EntrenarMuscleUi>,
)

/**
 * Todo lo que dibuja **Entrenar** después del cambio de modelo (GIM-49).
 *
 * La sesión como concepto desapareció de la UI: hay **un entrenamiento abierto** donde se
 * cargan series del ejercicio elegido. El nombre del ejercicio es un botón que abre
 * [picker]; la superserie se avisa con [supersetNextName] y salta una sola vez, sin
 * rotación por rondas. Los números vienen formateados del ViewModel, igual que en [HoyUiState].
 */
data class EntrenarUiState(
    val loading: Boolean = true,
    val phase: EntrenarPhase = EntrenarPhase.NO_SESSION,
    val sessionId: String = "",
    /** Nombre de la rutina activa, o vacío si no hay plan. */
    val routineName: String = "",
    val exerciseId: String = "",
    val exerciseName: String = "",
    val muscleName: String = "",
    /** Siguiente ejercicio si el actual es el primero de una superserie (`null` si no). */
    val supersetNextName: String? = null,
    val weightDraft: Double = 0.0,
    val repsDraft: Double = 0.0,
    /** Paso del peso: ajuste `weightStep` de §8 (2,5 kg por defecto). */
    val weightStep: Double = 2.5,
    val isWarmup: Boolean = false,
    /** Series de trabajo anotadas en todo el entrenamiento (sin calentamientos). */
    val setsDone: Int = 0,
    val rest: EntrenarRestUi? = null,
    /** El descanso acaba de llegar a cero: aviso inline (la alerta real es GIM-23). */
    val restDone: Boolean = false,
    /** Nombre del ejercicio al que saltó la superserie: "Seguí con …". */
    val jumpTo: String? = null,
    val canLogSet: Boolean = false,
    /** Series del ejercicio actual (un solo grupo: la pantalla ya no muestra bloques). */
    val sets: List<EntrenarSetsUi> = emptyList(),
    /** Selector abierto por el nombre del ejercicio; `null` = no está abierto. */
    val picker: EntrenarPickerUi? = null,
    /** Serie en corrección (`null` = ninguna). Corregir **no** mueve el ejercicio. */
    val editingSetId: String? = null,
    val editingWeight: Double = 0.0,
    val editingReps: Double = 0.0,
    val confirmingDelete: Boolean = false,
    /** Ajuste `keepScreenOn` de §8: la pantalla mantiene el equipo despierto. */
    val keepScreenOn: Boolean = true,
    /** Series de trabajo anotadas, para el fin de entrenamiento mientras carga el resumen. */
    val finishedSets: Int = 0,
    /** Resumen de fin de entrenamiento; `null` mientras se calcula. */
    val summary: EntrenarSummaryUi? = null,
)

/**
 * ViewModel de **Entrenar** (GIM-49): un entrenamiento abierto, sin "empezar sesión".
 *
 * Reglas del dueño implementadas acá:
 *
 * 1. **Siempre hay un entrenamiento**: al abrir la pantalla, si no existe una sesión
 *    `ACTIVE` se crea sola ([SessionRepository.openOrCreate]); salir no la cierra. La
 *    sesión de la base sigue siendo la misma tabla de siempre (historial, volumen, PR),
 *    lo que desapareció es el paso de "arrancar/terminar el día planificado".
 * 2. **El ejercicio se elige**: el nombre es un botón con el selector de la rutina activa
 *    y `+` para ver todas las rutinas. Elegir uno no depende del día ni del plan.
 * 3. **La superserie es un salto puntual**: si el ejercicio es el primero de una cadena
 *    (`supersetNext` en la rutina activa), anotar una serie pasa una vez al siguiente sin
 *    descanso, como si se lo hubiera elegido; después descansa y se queda ahí. No hay
 *    rotación por rondas.
 * 4. **El descanso es el del ejercicio** (`restOverrideSeconds` del ítem o el del
 *    catálogo) y se persiste en `app_setting` para sobrevivir a que se cierre la app; el
 *    servicio y la alarma siguen siendo los de GIM-22.
 *
 * La corrección y el borrado de series siguen siendo `UPDATE`/`DELETE` de `session_set`:
 * no mueven el ejercicio actual.
 *
 * **Fin**: `finish` cierra la sesión (`FINISHED`) y muestra el resumen calculado con
 * `:core`; `abandon` la deja `ABANDONED` (no cuenta como entrenamiento) y sale.
 */
class EntrenarViewModel internal constructor(
    private val sessions: SessionRepository,
    private val settings: SettingsRepository,
    private val exercises: ExerciseRepository,
    private val routines: RoutineRepository,
    private val scope: CoroutineScope,
    private val now: () -> LocalDateTime = { java.time.LocalDateTime.now().toKotlinLocalDateTime() },
    private val nowInstant: () -> Instant = Instant::now,
    private val restGateway: RestGateway = RestGateway.Noop,
) {

    constructor(container: AppContainer, scope: CoroutineScope) : this(
        sessions = container.sessions,
        settings = container.settings,
        exercises = container.exercises,
        routines = container.routines,
        scope = scope,
    )

    /** La pantalla real pasa el gateway que arranca el servicio y la alarma (GIM-22). */
    constructor(
        container: AppContainer,
        scope: CoroutineScope,
        restGateway: RestGateway,
    ) : this(
        sessions = container.sessions,
        settings = container.settings,
        exercises = container.exercises,
        routines = container.routines,
        scope = scope,
        restGateway = restGateway,
    )

    private val _state = MutableStateFlow(EntrenarUiState())

    /** Lo que compone la pantalla. */
    val state: StateFlow<EntrenarUiState> = _state.asStateFlow()

    private var observation: Job? = null

    /** Serializa escrituras y lecturas: dos toques rápidos no pueden pisarse. */
    private val gate = Mutex()

    private var session: Session? = null
    private var catalog: List<Exercise> = emptyList()
    private var routinesData: List<Routine> = emptyList()
    private var activeRoutineId: String? = null
    private var muscleNames: Map<String, String> = emptyMap()
    private var prefs: AppSettings = AppSettings.DEFAULT

    /** Ejercicio que se está cargando. Se persiste en `app_setting` al elegirlo. */
    private var currentExerciseId: String? = null

    /** Ejercicio para el que ya se precargaron los borradores (no se pisan al editar). */
    private var draftExerciseId: String? = null
    private var draftWeight: Double? = null
    private var draftReps: Double? = null
    private var warmup: Boolean = false

    private var restDeadline: Instant? = null
    private var restTotal: Int = 0
    private var restDone: Boolean = false
    private var jumpTo: String? = null

    private var editingSetId: String? = null
    private var editingWeight: Double? = null
    private var editingReps: Double? = null
    private var confirmingDelete: Boolean = false

    private var pickerOpen: Boolean = false
    private var pickerAll: Boolean = false

    /** El entrenamiento se terminó y se muestra el resumen: no se auto-crea otro. */
    private var finished: Boolean = false

    /** El entrenamiento se descartó: no se auto-crea otro hasta reabrir la pantalla. */
    private var discarded: Boolean = false

    private var summary: EntrenarSummaryUi? = null
    private var creating: Boolean = false

    /** Empieza a observar repositorios. Idempotente: la pantalla la llama una vez. */
    fun start() {
        if (observation != null) return
        observation = scope.launch {
            muscleNames = exercises.muscles().associate { it.id to it.name }
            currentExerciseId = settings.trainingExercise()
            restoreRest()
            combine(
                sessions.observeActive(),
                routines.observeRoutines(),
                exercises.observeExercises(),
                settings.settings,
                settings.activeRoutineId,
            ) { active, allRoutines, allExercises, readPrefs, activeId ->
                Snapshot(active, allRoutines, allExercises, readPrefs, activeId)
            }.collect { snapshot ->
                gate.withLock { sync(snapshot) }
            }
        }
    }

    fun stop() {
        observation?.cancel()
        observation = null
    }

    // --- acciones de la pantalla -------------------------------------------------

    /** Borrador del peso. No escribe nada hasta [logSet]. */
    fun setWeight(value: Double) {
        draftWeight = value
        publish()
    }

    /** Borrador de las reps. */
    fun setReps(value: Double) {
        draftReps = value
        publish()
    }

    /** Chip "Calentamiento": la serie se anota sin mover el ejercicio. */
    fun toggleWarmup() {
        warmup = !warmup
        publish()
    }

    /** "Serie hecha": guarda la serie y aplica el salto de superserie o el descanso. */
    fun logSet() {
        scope.launch { logSetNow() }
    }

    /** "Saltar": corta el descanso y deja el ejercicio actual. */
    fun skipRest() {
        scope.launch { skipRestNow() }
    }

    /** `+15 s` / `−15 s` (delta negativo). */
    fun extendRest(deltaSecs: Int) {
        scope.launch { extendRestNow(deltaSecs) }
    }

    /** Pulso de la pantalla: el ViewModel decide si el descanso ya venció. */
    fun tick() {
        scope.launch { tickNow() }
    }

    fun editSet(setId: String) {
        val logged = session?.sets?.firstOrNull { it.id == setId } ?: return
        editingSetId = setId
        editingWeight = logged.weightKg
        editingReps = logged.reps.toDouble()
        confirmingDelete = false
        publish()
    }

    fun cancelEdit() {
        clearEditing()
        publish()
    }

    fun setEditingWeight(value: Double) {
        editingWeight = value
        publish()
    }

    fun setEditingReps(value: Double) {
        editingReps = value
        publish()
    }

    /** Guarda la corrección de peso/reps. **No** mueve el ejercicio actual. */
    fun saveCorrection() {
        scope.launch { saveCorrectionNow() }
    }

    fun askDelete() {
        if (editingSetId == null) return
        confirmingDelete = true
        publish()
    }

    fun cancelDelete() {
        confirmingDelete = false
        publish()
    }

    /** Borra la serie en corrección (sólo los datos). */
    fun deleteSet() {
        scope.launch { deleteSetNow(editingSetId) }
    }

    /** Abre el selector de ejercicio con el nombre tocado. */
    fun openPicker() {
        if (pickerOpen) return
        pickerOpen = true
        pickerAll = false
        publish()
    }

    fun closePicker() {
        if (!pickerOpen) return
        pickerOpen = false
        publish()
    }

    /** `+ Ver todas las rutinas`: agranda el selector a todas. */
    fun showAllRoutines() {
        pickerAll = true
        publish()
    }

    /** "Volver a mi rutina": vuelve a la lista corta. */
    fun showActiveRoutine() {
        pickerAll = false
        publish()
    }

    /**
     * Elige el ejercicio actual desde el selector. No exige que esté en el día: el
     * entrenamiento es libre y el ejercicio puede venir de cualquier rutina.
     */
    fun pickExercise(exerciseId: String) {
        scope.launch {
            gate.withLock {
                useExercise(exerciseId)
                // Elegir a mano corta el descanso que estaba corriendo: el usuario ya
                // decidió con qué sigue.
                clearRest()
                restDone = false
                jumpTo = null
            }
            pickerOpen = false
            publish()
        }
    }

    /** Termina el entrenamiento: cierra la sesión y muestra el resumen. */
    fun finish(onDone: () -> Unit = {}) {
        scope.launch {
            finishNow()
            onDone()
        }
    }

    /**
     * Descarta el entrenamiento desde Entrenar: la sesión queda `ABANDONED` con sus series
     * y no hay resumen. [onDone] sale de la pantalla.
     */
    fun abandon(onDone: () -> Unit = {}) {
        scope.launch {
            abandonNow()
            onDone()
        }
    }

    // --- implementación (suspend: los usan los tests sin esperar corrutinas) ------

    internal suspend fun logSetNow() {
        if (finished || discarded) return
        val active = session ?: return
        val exerciseId = currentExerciseId ?: return
        val exercise = catalog.firstOrNull { it.id == exerciseId } ?: return
        val reps = (draftReps ?: 0.0).roundToInt()
        if (reps <= 0) return

        val isWarmup = warmup
        val set = SessionSet(
            id = "",
            exerciseId = exerciseId,
            weightKg = draftWeight ?: 0.0,
            reps = reps,
            completedAt = now(),
            isWarmup = isWarmup,
        )
        // Toda la serie (dato, salto de superserie y descanso) va bajo el mismo `gate`:
        // una emisión de la base en el medio no puede pintar media operación.
        gate.withLock {
            sessions.recordSet(
                sessionId = active.id,
                set = set,
                dayItemId = itemFor(exerciseId)?.id,
                roundIndex = 0,
                setIndex = active.sets.count { it.exerciseId == exerciseId },
            )
            session = sessions.session(active.id) ?: active
            restDone = false
            if (isWarmup) {
                warmup = false
                jumpTo = null
                startRest(warmupRestSeconds(restSecondsFor(activeRoutine(), exercise)))
            } else {
                val next = activeRoutine()?.let { supersetNextExercise(it, exerciseId) }
                if (next != null) {
                    // Superserie: salto puntual al siguiente ejercicio, sin descanso.
                    clearRest()
                    useExercise(next.id, preload = true)
                    jumpTo = next.name
                } else {
                    jumpTo = null
                    startRest(restSecondsFor(activeRoutine(), exercise))
                }
            }
        }
        publish()
    }

    internal suspend fun skipRestNow() {
        if (restDeadline == null) return
        clearRest()
        restDone = false
        publish()
    }

    internal suspend fun extendRestNow(deltaSecs: Int) {
        val deadline = restDeadline ?: return
        val seconds = (restTotal + deltaSecs).coerceAtLeast(0)
        if (seconds <= 0) {
            clearRest()
            publish()
            return
        }
        val end = deadline.plusSeconds(deltaSecs.toLong())
        restDeadline = end
        restTotal = seconds
        settings.setTrainingRest(end.toEpochMilli(), seconds)
        restGateway.onStartRest(seconds, end)
        publish()
    }

    internal suspend fun tickNow() {
        val deadline = restDeadline ?: return
        if (nowInstant().isBefore(deadline)) return
        clearRest(cancelGateway = false)
        restGateway.onRestFinished()
        restDone = true
        publish()
    }

    /** Guarda la corrección en el `gate`: la emisión de la base no pisa la edición. */
    internal suspend fun saveCorrectionNow() = gate.withLock {
        val id = editingSetId ?: return@withLock
        val weight = editingWeight ?: return@withLock
        val reps = (editingReps ?: 0.0).roundToInt()
        if (reps <= 0) return@withLock
        sessions.updateSet(id, weightKg = weight, reps = reps, rpe = null)
        session = session?.replaceSet(id, weight, reps)
        clearEditing()
        publish()
    }

    internal suspend fun deleteSetNow(setId: String?) = gate.withLock {
        val id = setId ?: return@withLock
        sessions.deleteSet(id)
        session = session?.let { current -> current.copy(sets = current.sets.filterNot { it.id == id }) }
        clearEditing()
        publish()
    }

    /** Cierra la sesión y arma el resumen con `:core`. */
    internal suspend fun finishNow() = gate.withLock {
        if (finished) return@withLock
        val active = session ?: return@withLock
        finished = true
        clearRest()
        sessions.finish(active.id, now())
        session = sessions.session(active.id) ?: active
        summary = null
        loadSummary()
        publish()
    }

    internal suspend fun abandonNow() = gate.withLock {
        val active = session ?: return@withLock
        discarded = true
        finished = false
        clearRest()
        sessions.abandon(active.id, now())
        session = null
        summary = null
        publish()
    }

    // --- estado ------------------------------------------------------------------

    private data class Snapshot(
        val session: Session?,
        val routines: List<Routine>,
        val exercises: List<Exercise>,
        val prefs: AppSettings,
        val activeRoutineId: String?,
    )

    private suspend fun sync(snapshot: Snapshot) {
        prefs = snapshot.prefs
        routinesData = snapshot.routines
        catalog = snapshot.exercises
        activeRoutineId = snapshot.activeRoutineId
        // El seed carga en segundo plano: si la pantalla abrió antes, los nombres de
        // músculo llegan vacíos. Se completan en la primera emisión con catálogo.
        if (muscleNames.isEmpty() && catalog.isNotEmpty()) {
            muscleNames = exercises.muscles().associate { it.id to it.name }
        }

        if (finished || discarded) {
            publish()
            return
        }

        var active = snapshot.session
        // Un entrenamiento que quedó abierto de otro día se cierra solo: el dueño pidió
        // una única cosa donde va cargando ejercicios, pero esa cosa es **del día**. Sin
        // esto, olvidarse de "Terminar" hacía que el entrenamiento de ayer y el de hoy
        // fueran la misma sesión (duración y volumen mezclados).
        if (active != null && active.startedAt.date != now().date) {
            val last = active.sets.maxByOrNull { it.completedAt }
            if (last == null) {
                sessions.abandon(active.id, active.startedAt)
            } else {
                sessions.finish(active.id, last.completedAt)
            }
            active = null
        }
        if (active == null && !creating) {
            creating = true
            val id = sessions.openOrCreate(
                routineId = activeRoutine()?.id,
                startedAt = now(),
            )
            active = sessions.session(id)
            creating = false
        }
        if (active == null) {
            session = null
            publish()
            return
        }
        session = active
        ensureExercise(active)
        publish()
    }

    /**
     * Resuelve el ejercicio actual: el elegido si sigue existiendo, si no la última serie
     * del entrenamiento, si no el primero de la rutina activa y si no el primero del
     * catálogo. Al cambiarlo precarga peso/reps.
     */
    private suspend fun ensureExercise(active: Session) {
        val valid = catalog.mapTo(HashSet()) { it.id }
        val candidate = currentExerciseId?.takeIf { it in valid }
            ?: active.sets.lastOrNull()?.exerciseId?.takeIf { it in valid }
            ?: activeRoutine()?.days?.firstNotNullOfOrNull { day ->
                day.items.firstOrNull()?.exerciseId?.takeIf { it in valid }
            }
            ?: catalog.firstOrNull { !it.archived }?.id
        if (candidate == null) {
            draftExerciseId = null
            publish()
            return
        }
        if (candidate != currentExerciseId) {
            currentExerciseId = candidate
            preloadDrafts(candidate, force = true)
        } else if (draftExerciseId == null) {
            preloadDrafts(candidate, force = true)
        }
    }

    /**
     * Precarga peso y reps del ejercicio: la última serie de ese ejercicio en este
     * entrenamiento, o el histórico si `autoFillLast`, o el objetivo del ítem, o los
     * defaults. [force] se usa al cambiar de ejercicio; al editar los borradores no se
     * pisan porque [draftExerciseId] ya coincide.
     */
    private suspend fun preloadDrafts(exerciseId: String, force: Boolean = false) {
        if (!force && draftExerciseId == exerciseId) return
        val exercise = catalog.firstOrNull { it.id == exerciseId }
        if (exercise == null) {
            draftExerciseId = null
            return
        }
        draftExerciseId = exerciseId
        warmup = false
        clearEditing()
        val own = session?.sets?.lastOrNull { it.exerciseId == exerciseId && !it.isWarmup }
        val history = if (own == null && prefs.autoFillLast) sessions.lastWorkingSet(exerciseId) else null
        val source = own ?: history
        draftWeight = source?.weightKg ?: defaultWeight(exercise)
        draftReps = source?.reps?.toDouble()
            ?: itemFor(exerciseId)?.targetRepsMin?.toDouble()
            ?: DEFAULT_REPS
    }

    /** Cambia el ejercicio actual y lo recuerda para la próxima apertura. */
    private suspend fun useExercise(exerciseId: String, preload: Boolean = true) {
        if (catalog.none { it.id == exerciseId }) return
        currentExerciseId = exerciseId
        settings.setTrainingExercise(exerciseId)
        if (preload) preloadDrafts(exerciseId, force = true)
    }

    private suspend fun startRest(seconds: Int) {
        if (seconds <= 0) {
            clearRest()
            return
        }
        val deadline = nowInstant().plusSeconds(seconds.toLong())
        restDeadline = deadline
        restTotal = seconds
        settings.setTrainingRest(deadline.toEpochMilli(), seconds)
        restGateway.onStartRest(seconds, deadline)
    }

    private suspend fun clearRest(cancelGateway: Boolean = true) {
        val hadRest = restDeadline != null
        restDeadline = null
        restTotal = 0
        if (hadRest) settings.setTrainingRest(null, null)
        if (cancelGateway && hadRest) restGateway.onCancelRest()
    }

    /** Recupera el descanso persistido al abrir la app (GIM-49/GIM-22). */
    private suspend fun restoreRest() {
        val (endsAt, seconds) = settings.trainingRest()
        if (endsAt == null || seconds == null || seconds <= 0) return
        val deadline = Instant.ofEpochMilli(endsAt)
        if (!deadline.isAfter(nowInstant())) {
            settings.setTrainingRest(null, null)
            return
        }
        restDeadline = deadline
        restTotal = seconds
        restGateway.onStartRest(remainingSeconds(deadline), deadline)
    }

    private fun publish() {
        if (finished) {
            _state.value = _state.value.copy(
                loading = false,
                phase = EntrenarPhase.FINISHED,
                rest = null,
                restDone = false,
                jumpTo = null,
                canLogSet = false,
                picker = null,
                finishedSets = session?.sets?.count { !it.isWarmup } ?: 0,
                keepScreenOn = prefs.keepScreenOn,
                summary = summary,
            )
            return
        }

        val active = session
        val exercise = catalog.firstOrNull { it.id == currentExerciseId }
        if (active == null || exercise == null) {
            _state.value = EntrenarUiState(
                loading = false,
                phase = EntrenarPhase.NO_SESSION,
                keepScreenOn = prefs.keepScreenOn,
            )
            return
        }

        val routine = activeRoutine()
        val logged = active.sets.filter { it.exerciseId == exercise.id }
        _state.value = EntrenarUiState(
            loading = false,
            phase = EntrenarPhase.ACTIVE,
            sessionId = active.id,
            routineName = routine?.name.orEmpty(),
            exerciseId = exercise.id,
            exerciseName = exercise.name,
            muscleName = muscleNames[exercise.primaryMuscleId].orEmpty(),
            supersetNextName = routine?.let { supersetNextExercise(it, exercise.id)?.name },
            weightDraft = draftWeight ?: defaultWeight(exercise),
            repsDraft = draftReps ?: DEFAULT_REPS,
            weightStep = prefs.weightStep.takeIf { it > 0.0 } ?: AppSettings.DEFAULT.weightStep,
            isWarmup = warmup,
            setsDone = active.sets.count { !it.isWarmup },
            rest = restUi(),
            restDone = restDone,
            jumpTo = jumpTo,
            canLogSet = true,
            sets = if (logged.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    EntrenarSetsUi(
                        itemId = exercise.id,
                        exerciseName = exercise.name,
                        sets = logged.mapIndexed { index, set -> set.toUi(exercise, index + 1) },
                    ),
                )
            },
            picker = if (pickerOpen) pickerUi() else null,
            editingSetId = editingSetId,
            editingWeight = editingWeight ?: 0.0,
            editingReps = editingReps ?: 0.0,
            confirmingDelete = confirmingDelete,
            keepScreenOn = prefs.keepScreenOn,
        )
    }

    private fun pickerUi(): EntrenarPickerUi {
        val source = if (pickerAll) routinesData else listOfNotNull(activeRoutine())
        val groups = source.flatMap { routine ->
            routine.days.mapNotNull { day ->
                val items = day.items
                    .map { it.exerciseId }
                    .distinct()
                    .mapNotNull { id ->
                        catalog.firstOrNull { it.id == id }?.let { exercise ->
                            EntrenarPickerItemUi(exerciseId = exercise.id, name = exercise.name)
                        }
                    }
                if (items.isEmpty()) {
                    null
                } else {
                    EntrenarPickerGroupUi(
                        routineId = routine.id,
                        routineName = routine.name,
                        dayName = day.name,
                        items = items,
                    )
                }
            }
        }
        return EntrenarPickerUi(showingAll = pickerAll, groups = groups)
    }

    private fun restUi(): EntrenarRestUi? {
        val end = restDeadline ?: return null
        val remaining = remainingSeconds(end)
        return EntrenarRestUi(
            remainingSeconds = remaining,
            totalSeconds = restTotal.coerceAtLeast(remaining),
        )
    }

    private fun remainingSeconds(deadline: Instant): Int {
        val millis = Duration.between(nowInstant(), deadline).toMillis()
        return ((millis + 999) / 1000).toInt().coerceAtLeast(0)
    }

    private fun activeRoutine(): Routine? =
        routinesData.firstOrNull { it.id == activeRoutineId && !it.archived }
            ?: routinesData.firstOrNull { !it.archived }
            ?: routinesData.firstOrNull()

    private fun itemFor(exerciseId: String): DayItem? =
        activeRoutine()?.days
            ?.asSequence()
            ?.flatMap { it.items.asSequence() }
            ?.firstOrNull { it.exerciseId == exerciseId }

    private fun defaultWeight(exercise: Exercise): Double =
        if (exercise.loadType == LoadType.BODYWEIGHT) 0.0 else DEFAULT_WEIGHT_KG

    private fun SessionSet.toUi(exercise: Exercise, number: Int): EntrenarSetUi {
        val bodyweight = exercise.loadType == LoadType.BODYWEIGHT && weightKg <= 0.0
        return EntrenarSetUi(
            id = id,
            number = number,
            weightLabel = if (bodyweight) BODYWEIGHT_LABEL else formatNumber(weightKg) + " kg",
            repsLabel = reps.toString(),
            isWarmup = isWarmup,
        )
    }

    private fun clearEditing() {
        editingSetId = null
        editingWeight = null
        editingReps = null
        confirmingDelete = false
    }

    private fun Session.replaceSet(id: String, weightKg: Double, reps: Int): Session =
        copy(
            sets = sets.map { set ->
                if (set.id == id) set.copy(weightKg = weightKg, reps = reps) else set
            },
        )

    /**
     * Arma [EntrenarSummaryUi] desde la sesión **persistida** y `:core`.
     *
     * Volumen, series de trabajo, PRs (contra el histórico terminado anterior) y series
     * efectivas por músculo salen de las mismas funciones que el resto del producto. El
     * nombre del día es el de la rutina activa (el entrenamiento ya no pertenece a un día).
     */
    private suspend fun loadSummary() {
        if (summary != null) return
        val stored = session?.let { sessions.session(it.id) } ?: return
        val core = prefs.toCoreSettings()
        val fullCatalog = exercises.all()
        val exerciseById = fullCatalog.associateBy { it.id }

        val priorBest = historicalBestByExercise(
            sessions.finishedHistory().filter { it.id != stored.id },
            core.e1rmFormula,
        )
        val prs = sessionE1rm(stored, priorBest, core)
            .filter { it.isPr }
            .groupBy { it.set.exerciseId }
            .values
            .mapNotNull { sets -> sets.maxByOrNull { it.value } }
            .map { pr ->
                EntrenarPrUi(
                    exerciseId = pr.set.exerciseId,
                    exerciseName = exerciseById[pr.set.exerciseId]?.name ?: pr.set.exerciseId,
                    setLabel = formatNumber(pr.set.weightKg) + " kg × " + pr.set.reps,
                    e1rmLabel = formatNumber(pr.value) + " kg",
                )
            }

        val byMuscle = sessionVolumeByMuscle(listOf(stored), fullCatalog, core).values
            .sortedByDescending { it.total(core.includeIndirect) }
            .take(MUSCLES_TOP)
        val top = byMuscle.firstOrNull()?.total(core.includeIndirect)?.takeIf { it > 0.0 } ?: 1.0
        val muscles = byMuscle.map { row ->
            val value = row.total(core.includeIndirect)
            EntrenarMuscleUi(
                muscleId = row.muscleId,
                name = muscleNames[row.muscleId] ?: row.muscleId,
                setsLabel = formatNumber(value),
                fraction = (value / top).toFloat(),
            )
        }

        val durationSeconds = stored.endedAt?.let { ended ->
            Duration.between(stored.startedAt.toJavaLocalDateTime(), ended.toJavaLocalDateTime()).seconds
        } ?: 0L

        summary = EntrenarSummaryUi(
            dayName = activeRoutine()?.name.orEmpty(),
            durationLabel = formatDuration(durationSeconds),
            volumeLabel = formatNumber(sessionVolume(stored, fullCatalog, core)) + " kg",
            setsLabel = stored.sets.count { !it.isWarmup }.toString(),
            prs = prs,
            muscles = muscles,
        )
        publish()
    }

    companion object {
        /** Punto de partida de los steppers cuando no hay histórico ni reps objetivo. */
        const val DEFAULT_WEIGHT_KG: Double = 20.0

        /** Reps de arranque cuando no hay histórico ni reps objetivo. */
        const val DEFAULT_REPS: Double = 10.0

        /** Etiqueta del peso en un ejercicio de peso corporal sin lastre. */
        const val BODYWEIGHT_LABEL: String = "corporal"

        /** Músculos que muestra el resumen: el top 5 del entrenamiento. */
        const val MUSCLES_TOP: Int = 5
    }
}
