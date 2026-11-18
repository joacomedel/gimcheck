package app.gimcheck.ui.screens

import app.gimcheck.AppContainer
import app.gimcheck.core.AbandonSession
import app.gimcheck.core.DayItem
import app.gimcheck.core.ExtendRest
import app.gimcheck.core.GuidedSession
import app.gimcheck.core.LoadType
import app.gimcheck.core.LogSet
import app.gimcheck.core.SessionEffect
import app.gimcheck.core.SessionSet
import app.gimcheck.core.SessionStatus
import app.gimcheck.core.SessionTransition
import app.gimcheck.core.SkipRest
import app.gimcheck.core.Tick
import app.gimcheck.core.formatDuration
import app.gimcheck.core.formatNumber
import app.gimcheck.core.historicalBestByExercise
import app.gimcheck.core.sessionE1rm
import app.gimcheck.core.sessionVolume
import app.gimcheck.core.sessionVolumeByMuscle
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.SessionRepository
import app.gimcheck.data.repository.SettingsRepository
import app.gimcheck.data.settings.AppSettings
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
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Gancho del **cronómetro de descanso** (`docs/especificacion.md` §4, GIM-22).
 *
 * La pantalla muestra la cuenta regresiva desde `rest_ends_at`, pero el servicio en
 * primer plano y la alarma exacta de respaldo viven fuera de la pantalla (paquete
 * `app.gimcheck.rest`, ADR 0004). El [EntrenarViewModel] sabe **cuándo** hay que
 * arrancar, cortar o dar por terminado un descanso —son los efectos de la máquina de
 * `:core`— y los publica acá; nunca decide el tiempo.
 *
 * Con [Noop] (el default) nadie escucha: el cronómetro vive dentro de la pantalla y
 * muere con ella. La pantalla real inyecta `AndroidRestGateway`.
 */
interface RestGateway {

    /**
     * Hay que (re)programar el cronómetro hasta [endsAt] —el `rest_ends_at` absoluto de
     * `:core`— con un total de [seconds] (para el progreso de la notificación).
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

/** Fase de la pantalla: sin sesión, sesión viva o sesión terminada (GIM-24). */
enum class EntrenarPhase { NO_SESSION, ACTIVE, FINISHED }

/** Cuenta regresiva del descanso, ya en segundos ([remainingSeconds] desde el deadline). */
data class EntrenarRestUi(
    val remainingSeconds: Int,
    val totalSeconds: Int,
)

/** Una serie anotada de un ítem del bloque, lista para `SetRow`. */
data class EntrenarSetUi(
    val id: String,
    val number: Int,
    val weightLabel: String,
    val repsLabel: String,
    val isWarmup: Boolean,
)

/** Las series anotadas de un ítem del bloque actual, en orden de ejecución. */
data class EntrenarSetsUi(
    val itemId: String,
    val exerciseName: String,
    val sets: List<EntrenarSetUi>,
)

/**
 * Un **PR del día**: la mejor serie del ejercicio que superó su máximo previo.
 *
 * @param setLabel `82,5 kg × 5`, ya formateado (coma decimal).
 * @param e1rmLabel e1RM de esa serie con la fórmula del ajuste (`e1rmFormula`).
 */
data class EntrenarPrUi(
    val exerciseId: String,
    val exerciseName: String,
    val setLabel: String,
    val e1rmLabel: String,
)

/**
 * Una barra de **volumen por músculo del día**: series efectivas de `:core`
 * (`sessionVolumeByMuscle`, la misma unidad que el análisis semanal).
 *
 * @param fraction valor sobre el músculo que más series recibió, para el `Meter`.
 */
data class EntrenarMuscleUi(
    val muscleId: String,
    val name: String,
    val setsLabel: String,
    val fraction: Float,
)

/**
 * Resumen de **fin de sesión** (GIM-24): todo calculado con `:core` sobre las series que
 * quedaron guardadas, no con una segunda fórmula en la UI.
 *
 * @param durationLabel duración real (`started_at` → `ended_at`) en el formato del contrato.
 * @param volumeLabel volumen total del día en kg (`sessionVolume`).
 * @param setsLabel series de trabajo anotadas (sin calentamientos).
 * @param prs un PR por ejercicio, o vacío si el día no rompió nada.
 * @param muscles top 5 de series efectivas por músculo.
 */
data class EntrenarSummaryUi(
    val dayName: String,
    val durationLabel: String,
    val volumeLabel: String,
    val setsLabel: String,
    val prs: List<EntrenarPrUi>,
    val muscles: List<EntrenarMuscleUi>,
)

/**
 * Todo lo que dibuja **Entrenar** (§6 fila 2, `docs/diseno.md` §8 #2).
 *
 * Los números ya vienen formateados del ViewModel (coma decimal, `kg`), igual que en
 * [HoyUiState]: el composable compone, no calcula. [rest] es `null` cuando no hay
 * descanso vivo; el cronómetro sólo compara el deadline absoluto.
 */
data class EntrenarUiState(
    val loading: Boolean = true,
    val phase: EntrenarPhase = EntrenarPhase.NO_SESSION,
    val sessionId: String = "",
    val dayName: String = "",
    /** `1` en el primer bloque del día; para "Bloque 1 de 4". */
    val blockNumber: Int = 0,
    val blockCount: Int = 0,
    /** Nombres de los ítems del bloque unidos con ` + `; es el `SectionHeader` de abajo. */
    val blockName: String = "",
    /** Posición del ítem dentro del bloque (1..[supersetSize]); `0` si el bloque es de uno. */
    val supersetPosition: Int = 0,
    /** Ítems del bloque; `<= 1` = no es superserie y no se dibuja el indicador. */
    val supersetSize: Int = 0,
    val exerciseName: String = "",
    /** Ronda actual, 1..[rounds]. */
    val round: Int = 0,
    val rounds: Int = 0,
    val weightDraft: Double = 0.0,
    val repsDraft: Double = 0.0,
    /** Paso del peso: ajuste `weightStep` de §8 (2,5 kg por defecto). */
    val weightStep: Double = 2.5,
    val isWarmup: Boolean = false,
    /** Series de trabajo anotadas (sin calentamientos) y planificadas del día. */
    val setsDone: Int = 0,
    val setsTotal: Int = 0,
    val rest: EntrenarRestUi? = null,
    /** El descanso acaba de llegar a cero: aviso inline (la alerta real es GIM-23). */
    val restDone: Boolean = false,
    /** Nombre del ítem al que saltó la superserie: "Seguí con …". */
    val jumpTo: String? = null,
    val canLogSet: Boolean = false,
    val sets: List<EntrenarSetsUi> = emptyList(),
    /** Serie en corrección (`null` = ninguna). Corregir **no** mueve el cursor (§4). */
    val editingSetId: String? = null,
    val editingWeight: Double = 0.0,
    val editingReps: Double = 0.0,
    val confirmingDelete: Boolean = false,
    /** Ajuste `keepScreenOn` de §8: la pantalla mantiene el equipo despierto. */
    val keepScreenOn: Boolean = true,
    /** Series de trabajo anotadas, para el fin de sesión mientras carga el resumen. */
    val finishedSets: Int = 0,
    /** Resumen de fin de sesión (GIM-24); `null` mientras se calcula. */
    val summary: EntrenarSummaryUi? = null,
)

/**
 * ViewModel de **Entrenar**: anotar series, saltar solo al siguiente de la superserie,
 * descanso al cerrar la ronda y corrección de series.
 *
 * **Cero lógica de la máquina acá.** Cada acción arma un evento de `:core`
 * (`LogSet`, `SkipRest`, `ExtendRest`, `Tick`) y lo pasa por
 * [SessionRepository.dispatch], que llama a `reduce`. El estado que se dibuja es el que
 * devuelve la máquina (más lo que la base reconstruye en `observeGuided`): este archivo
 * no decide cuándo hay descanso ni a qué ítem se salta, sólo obedece los efectos
 * (`ShowNextInSuperset`, `StartRest`, `SessionFinished`, …).
 *
 * **Dos fuentes, con motivo.** Las acciones se aplican con el estado que devuelve
 * `dispatch` (respuesta inmediata al toque) y además se observa `observeGuided` (el
 * cursor que otra corrutina movió: la rehidratación de `ON_START`, otra pantalla). El
 * caso terminal se retiene en [finishedState] porque al cerrar la sesión `observeActive`
 * deja de emitir y la vista de fin se perdería.
 *
 * **Corregir y borrar** no son eventos de la máquina: son `UPDATE`/`DELETE` de
 * `session_set` (§4 "corregir una serie ya anotada no cambia el cursor"). Además se
 * reflejan en el estado en memoria para que un `LogSet` inmediatamente después no
 * reescriba la serie borrada (los ids son deterministas).
 *
 * **Precarga (`autoFillLast`)**: al cambiar el ítem del cursor se usan las series de ese
 * ítem en la sesión; si no hay, la última serie de trabajo del ejercicio en el
 * histórico (sólo con `autoFillLast = true`); si tampoco, el peso por defecto o las reps
 * objetivo del ítem.
 *
 * **Fin de sesión (GIM-24)**: cuando la máquina emite `SessionFinished`, el repositorio ya
 * cerró la fila con `ended_at` y estado `FINISHED`. Acá se arma [EntrenarSummaryUi] leyendo
 * esa sesión de la base y pasándola por `:core` (`sessionVolume`, `sessionE1rm` contra el
 * histórico anterior, `sessionVolumeByMuscle`). No hay números propios: si el resumen dice
 * 1.240 kg, es lo que devuelve `sessionVolume` para las mismas series.
 *
 * **Abandonar** ([abandon]) dispatcha `AbandonSession`: la máquina no emite
 * `SessionFinished`, así que no hay resumen ni PRs; el repositorio deja la sesión
 * `ABANDONED` con sus series guardadas.
 */
class EntrenarViewModel internal constructor(
    private val sessions: SessionRepository,
    private val settings: SettingsRepository,
    private val exercises: ExerciseRepository,
    private val scope: CoroutineScope,
    private val now: () -> LocalDateTime = { java.time.LocalDateTime.now().toKotlinLocalDateTime() },
    private val nowInstant: () -> Instant = Instant::now,
    private val restGateway: RestGateway = RestGateway.Noop,
) {

    constructor(container: AppContainer, scope: CoroutineScope) : this(
        sessions = container.sessions,
        settings = container.settings,
        exercises = container.exercises,
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
        scope = scope,
        restGateway = restGateway,
    )

    private val _state = MutableStateFlow(EntrenarUiState())

    /** Lo que compone la pantalla. */
    val state: StateFlow<EntrenarUiState> = _state.asStateFlow()

    private var observation: Job? = null

    /** Serializa los `dispatch`: dos toques rápidos no pueden leer el mismo cursor. */
    private val gate = Mutex()

    /** Último estado de la máquina: el que se le pasa a `dispatch`. */
    private var session: GuidedSession? = null

    /** Estado terminal retenido: al cerrar la sesión, `observeGuided` deja de emitir. */
    private var finishedState: GuidedSession? = null

    /** Resumen de fin de sesión ya calculado (se arma una sola vez por sesión). */
    private var summary: EntrenarSummaryUi? = null

    /** Sesión a la que pertenece [summary]; si cambia la sesión viva, se descarta. */
    private var summarySessionId: String? = null

    private var prefs: AppSettings = AppSettings.DEFAULT

    /** Ítem para el que ya se precargaron los borradores (no se pisan al editar). */
    private var draftItemId: String? = null
    private var draftWeight: Double? = null
    private var draftReps: Double? = null
    private var warmup: Boolean = false

    private var editingSetId: String? = null
    private var editingWeight: Double? = null
    private var editingReps: Double? = null
    private var confirmingDelete: Boolean = false

    private var restDone: Boolean = false
    private var jumpTo: String? = null

    /**
     * Deadline que el gateway tiene programado ahora mismo (`null` = no hay nada
     * corriendo). Sirve para no rearrancar el servicio en cada emisión de la base: la
     * pantalla observa `observeGuided`, que emite cada vez que cambia cualquier serie.
     */
    private var gatewayDeadline: Instant? = null

    /** Empieza a observar repositorios. Idempotente: la pantalla la llama una vez. */
    fun start() {
        if (observation != null) return
        observation = scope.launch {
            // `observeGuided` acá es sólo el aviso de que algo cambió: el estado se lee
            // con `guidedNow()` **dentro del `gate`**, igual que los `dispatch`. Si se
            // pintara el valor emitido, una emisión que arrancó antes de un `LogSet`
            // podía publicarse después y retroceder el cursor (GIM-21).
            combine(sessions.observeGuided(), settings.settings) { _, readPrefs -> readPrefs }
                .collect { readPrefs ->
                    gate.withLock { sync(sessions.guidedNow(), readPrefs) }
                }
        }
    }

    fun stop() {
        observation?.cancel()
        observation = null
    }

    // --- acciones de la pantalla -------------------------------------------------

    /** Borrador del peso (paso `weightStep`). No escribe nada hasta [logSet]. */
    fun setWeight(value: Double) {
        draftWeight = value
        publish()
    }

    /** Borrador de las reps (paso 1). */
    fun setReps(value: Double) {
        draftReps = value
        publish()
    }

    /** Chip "Calentamiento": la serie se anota sin mover el cursor (§4). */
    fun toggleWarmup() {
        warmup = !warmup
        publish()
    }

    /** "Serie hecha": `LogSet` y a obedecer los efectos (salto o descanso). */
    fun logSet() {
        scope.launch { logSetNow() }
    }

    /** "Saltar": corta el descanso y deja el cursor donde está. */
    fun skipRest() {
        scope.launch { skipRestNow() }
    }

    /** `+15 s` / `−15 s` (delta negativo). */
    fun extendRest(deltaSecs: Int) {
        scope.launch { extendRestNow(deltaSecs) }
    }

    /** Pulso de la pantalla: la máquina decide si el descanso ya venció. */
    fun tick() {
        scope.launch { tickNow() }
    }

    fun editSet(setId: String) {
        val logged = session?.sets?.firstOrNull { it.set.id == setId } ?: return
        editingSetId = setId
        editingWeight = logged.set.weightKg
        editingReps = logged.set.reps.toDouble()
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

    /** Guarda la corrección de peso/reps. **No** dispatcha: el cursor no se mueve (§4). */
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

    /** Borra la serie en corrección (sólo los datos: el cursor no vuelve atrás). */
    fun deleteSet() {
        scope.launch { deleteSetNow(editingSetId) }
    }

    /**
     * Abandona la sesión desde Entrenar: `AbandonSession` sin `SessionFinished`, así que no
     * hay resumen ni PRs. Las series quedan guardadas en la base (la sesión queda
     * `ABANDONED`) y [onDone] sale de la pantalla.
     */
    fun abandon(onDone: () -> Unit = {}) {
        scope.launch {
            abandonNow()
            onDone()
        }
    }

    // --- implementación (suspend: los usan los tests sin esperar corrutinas) ------

    internal suspend fun logSetNow() {
        // La sesión ya cerró: un toque tardío no puede finalizarla dos veces (§4: los
        // eventos fuera de estado devuelven el mismo estado y `persistGuided` no escribe).
        if (finishedState != null) return
        val guided = session ?: return
        val item = guided.currentItem ?: return
        val reps = (draftReps ?: 0.0).roundToInt()
        if (reps <= 0) return
        val at = now()
        val event = LogSet(
            weightKg = draftWeight ?: 0.0,
            reps = reps,
            isWarmup = warmup,
            at = at,
            now = nowInstant(),
        )
        val transition = gate.withLock { sessions.dispatch(guided, event, nowInstant()) }
        apply(transition)
        if (event.isWarmup) {
            // El calentamiento no mueve el cursor: el chip vuelve a apagado.
            warmup = false
        } else {
            restDone = false
            jumpTo = transition.state.currentItem
                ?.takeIf { SessionEffect.ShowNextInSuperset in transition.effects }
                ?.exercise?.name
        }
        // La precarga del próximo ítem la dispara la reemisión de `observeGuided`.
        publish()
        if (item.id != transition.state.currentItem?.id) {
            preloadIfNeeded(transition.state)
            publish()
        }
        // Última serie del último bloque: el repositorio ya cerró la fila con `ended_at`.
        // El resumen se calcula sobre lo guardado y se publica cuando está.
        if (finishedState != null) loadSummary()
    }

    internal suspend fun skipRestNow() {
        val guided = session ?: return
        val transition = gate.withLock { sessions.dispatch(guided, SkipRest, nowInstant()) }
        apply(transition)
        jumpTo = null
        publish()
    }

    internal suspend fun extendRestNow(deltaSecs: Int) {
        val guided = session ?: return
        val transition = gate.withLock {
            sessions.dispatch(guided, ExtendRest(deltaSecs), nowInstant())
        }
        apply(transition)
        publish()
    }

    internal suspend fun tickNow() {
        val guided = session ?: return
        if (guided.restEndsAt == null) return
        val transition = gate.withLock { sessions.dispatch(guided, Tick(nowInstant()), nowInstant()) }
        if (transition.effects.isNotEmpty()) apply(transition)
        publish()
    }

    /**
     * Guarda la corrección en el `gate`: así la reemisión de la base que dispara el
     * `UPDATE` no pinta la serie corregida antes de que se limpie la edición.
     */
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
        val current = session
        session = current?.copy(sets = current.sets.filterNot { it.set.id == id })
        clearEditing()
        publish()
    }

    internal suspend fun abandonNow() = gate.withLock {
        val guided = session ?: return@withLock
        val transition = sessions.dispatch(guided, AbandonSession, nowInstant())
        apply(transition)
        summary = null
        publish()
    }

    /**
     * Arma [EntrenarSummaryUi] desde la sesión **persistida** y `:core`.
     *
     * Todo sale de las mismas series que guardó la máquina: volumen (`sessionVolume` con el
     * `loadType` del catálogo y el peso corporal de la sesión), series de trabajo, PRs
     * (`sessionE1rm` contra el histórico terminado **anterior**, un PR por ejercicio) y
     * series efectivas por músculo (`sessionVolumeByMuscle`, top [MUSCLES_TOP]).
     */
    private suspend fun loadSummary() {
        val finished = finishedState ?: return
        if (summary != null) return
        val stored = sessions.session(finished.sessionId) ?: return
        val core = prefs.toCoreSettings()
        val catalog = exercises.all()
        val exerciseById = catalog.associateBy { it.id }
        val muscleNames = exercises.muscles().associate { it.id to it.name }

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

        val byMuscle = sessionVolumeByMuscle(listOf(stored), catalog, core).values
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
            dayName = finished.day.name,
            durationLabel = formatDuration(durationSeconds),
            volumeLabel = formatNumber(sessionVolume(stored, catalog, core)) + " kg",
            setsLabel = stored.sets.count { !it.isWarmup }.toString(),
            prs = prs,
            muscles = muscles,
        )
        summarySessionId = stored.id
        publish()
    }

    // --- proyección a la UI ------------------------------------------------------

    /**
     * Aplica lo que emitió la base. No se toca después de terminar: la vista de fin se
     * queda hasta que la pantalla se va (GIM-24 la reemplaza por el resumen real).
     */
    private suspend fun sync(guided: GuidedSession?, readPrefs: AppSettings) {
        prefs = readPrefs
        if (finishedState != null) {
            publish()
            return
        }
        if (guided != null && summarySessionId != null && guided.sessionId != summarySessionId) {
            // Otra sesión (p. ej. se descartó la anterior y se arrancó de nuevo): el
            // resumen de la anterior no se puede reusar.
            summary = null
            summarySessionId = null
        }
        session = guided
        // El cronómetro de afuera sigue al `rest_ends_at` de la base, no a la pantalla:
        // un descanso que venía corriendo con la app cerrada se retoma en el mismo
        // deadline (y si ya venció, `Restore` lo dio por terminado y se cancela).
        updateRestGateway(guided)
        if (guided == null) {
            draftItemId = null
            summary = null
            summarySessionId = null
            clearEditing()
            _state.value = EntrenarUiState(
                loading = false,
                phase = EntrenarPhase.NO_SESSION,
                keepScreenOn = readPrefs.keepScreenOn,
            )
            return
        }
        preloadIfNeeded(guided)
        publish()
    }

    private suspend fun preloadIfNeeded(guided: GuidedSession) {
        val item = guided.currentItem ?: run {
            draftItemId = null
            return
        }
        if (draftItemId == item.id) return
        draftItemId = item.id
        warmup = false
        clearEditing()
        val own = guided.setsFor(item.id).lastOrNull { !it.isWarmup }
        val history = if (own == null && prefs.autoFillLast) {
            sessions.lastWorkingSet(item.exerciseId)
        } else {
            null
        }
        val source: SessionSet? = own ?: history
        draftWeight = source?.weightKg ?: defaultWeight(item)
        draftReps = source?.reps?.toDouble() ?: item.targetRepsMin?.toDouble() ?: DEFAULT_REPS
    }

    private fun defaultWeight(item: DayItem): Double =
        if (item.exercise.loadType == LoadType.BODYWEIGHT) 0.0 else DEFAULT_WEIGHT_KG

    private fun apply(transition: SessionTransition) {
        session = transition.state
        transition.effects.forEach { effect ->
            when (effect) {
                // El cronómetro de afuera se sincroniza al final, con el estado nuevo
                // (`updateRestGateway`): así el servicio y la alarma usan el mismo
                // `restEndsAt` que va a persistir la máquina.
                is SessionEffect.StartRest -> Unit
                SessionEffect.CancelRest -> Unit
                SessionEffect.RestFinished -> {
                    // El gateway para lo suyo en `onRestFinished`; se limpia acá para
                    // que `updateRestGateway` no lo pare dos veces.
                    gatewayDeadline = null
                    restGateway.onRestFinished()
                    restDone = true
                }
                SessionEffect.ShowRestDone -> restDone = true
                SessionEffect.SessionFinished -> finishedState = transition.state
                SessionEffect.PersistState,
                SessionEffect.ShowNextInSuperset,
                SessionEffect.ShowNextExercise -> Unit
            }
        }
        updateRestGateway(transition.state)
        if (transition.state.status == SessionStatus.FINISHED) finishedState = transition.state
    }

    /**
     * Deja el servicio en primer plano y la alarma exacta apuntando a [guided]`.restEndsAt`
     * (o cancela los dos si no hay descanso). Idempotente por deadline: la base se emite
     * varias veces por serie anotada y no hace falta re-arrancar el servicio cada vez.
     *
     * Si el deadline ya venció con la app cerrada no se arranca un cronómetro en el
     * pasado: `Restore`/`Tick` ya cerraron el descanso y lo que corresponde es cancelar.
     */
    private fun updateRestGateway(guided: GuidedSession?) {
        val endsAt = guided?.restEndsAt
        if (endsAt == null || !endsAt.isAfter(nowInstant())) {
            if (gatewayDeadline != null) {
                gatewayDeadline = null
                restGateway.onCancelRest()
            }
            return
        }
        if (endsAt == gatewayDeadline) return
        gatewayDeadline = endsAt
        val remaining = ((Duration.between(nowInstant(), endsAt).toMillis() + 999) / 1000)
            .toInt()
            .coerceAtLeast(1)
        val total = guided.restSeconds ?: remaining
        restGateway.onStartRest(maxOf(total, remaining), endsAt)
    }

    private fun publish() {
        val finished = finishedState
        if (finished != null) {
            _state.value = _state.value.copy(
                loading = false,
                phase = EntrenarPhase.FINISHED,
                rest = null,
                restDone = false,
                jumpTo = null,
                canLogSet = false,
                finishedSets = finished.sets.count { !it.set.isWarmup },
                keepScreenOn = prefs.keepScreenOn,
                summary = summary,
            )
            return
        }
        val guided = session
        if (guided == null || guided.status == SessionStatus.ABANDONED) {
            // Abandonada: no hay resumen ni PRs; la pantalla vuelve a "sin sesión" y la
            // navegación la saca a Hoy.
            summary = null
            summarySessionId = null
            _state.value = EntrenarUiState(
                loading = false,
                phase = EntrenarPhase.NO_SESSION,
                keepScreenOn = prefs.keepScreenOn,
            )
            return
        }

        val block = guided.currentBlock
        val item = guided.currentItem
        val totalSets = guided.day.items.sumOf { it.sets }
        val plannedRounds = guided.rounds

        _state.value = EntrenarUiState(
            loading = false,
            phase = EntrenarPhase.ACTIVE,
            sessionId = guided.sessionId,
            dayName = guided.day.name,
            blockNumber = guided.blockIndex + 1,
            blockCount = guided.blocks.size,
            blockName = block.joinToString(" + ") { it.exercise.name },
            supersetPosition = if (block.size > 1) guided.itemIndex + 1 else 0,
            supersetSize = if (block.size > 1) block.size else 0,
            exerciseName = item?.exercise?.name.orEmpty(),
            round = (guided.roundIndex + 1).coerceAtMost(plannedRounds.coerceAtLeast(1)),
            rounds = plannedRounds,
            weightDraft = draftWeight ?: defaultWeightOrZero(item),
            repsDraft = draftReps ?: DEFAULT_REPS,
            weightStep = prefs.weightStep.takeIf { it > 0.0 } ?: AppSettings.DEFAULT.weightStep,
            isWarmup = warmup,
            setsDone = guided.sets.count { !it.set.isWarmup },
            setsTotal = totalSets,
            rest = restUi(guided),
            restDone = restDone,
            jumpTo = jumpTo,
            canLogSet = guided.isActive && item != null,
            sets = setsUi(guided, block),
            editingSetId = editingSetId,
            editingWeight = editingWeight ?: 0.0,
            editingReps = editingReps ?: 0.0,
            confirmingDelete = confirmingDelete,
            keepScreenOn = prefs.keepScreenOn,
            finishedSets = 0,
        )
    }

    private fun defaultWeightOrZero(item: DayItem?): Double =
        item?.let { defaultWeight(it) } ?: DEFAULT_WEIGHT_KG

    /** Cuenta regresiva desde `rest_ends_at` (deadline absoluto, no un contador). */
    private fun restUi(guided: GuidedSession): EntrenarRestUi? {
        val end = guided.restEndsAt ?: return null
        val millis = Duration.between(nowInstant(), end).toMillis()
        val remaining = ((millis + 999) / 1000).toInt().coerceAtLeast(0)
        val total = guided.restSeconds ?: remaining
        return EntrenarRestUi(
            remainingSeconds = remaining,
            totalSeconds = total.coerceAtLeast(remaining),
        )
    }

    private fun setsUi(guided: GuidedSession, block: List<DayItem>): List<EntrenarSetsUi> =
        block.mapNotNull { blockItem ->
            val logged = guided.sets.filter { it.itemId == blockItem.id }
            if (logged.isEmpty()) return@mapNotNull null
            EntrenarSetsUi(
                itemId = blockItem.id,
                exerciseName = blockItem.exercise.name,
                sets = logged.mapIndexed { position, entry ->
                    entry.set.toUi(blockItem, position + 1)
                },
            )
        }

    private fun SessionSet.toUi(item: DayItem, number: Int): EntrenarSetUi {
        val bodyweight = item.exercise.loadType == LoadType.BODYWEIGHT && weightKg <= 0.0
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

    private fun GuidedSession.replaceSet(id: String, weightKg: Double, reps: Int): GuidedSession =
        copy(
            sets = sets.map { logged ->
                if (logged.set.id == id) {
                    logged.copy(set = logged.set.copy(weightKg = weightKg, reps = reps))
                } else {
                    logged
                }
            },
        )

    companion object {
        /** Punto de partida de los steppers cuando no hay histórico ni reps objetivo. */
        const val DEFAULT_WEIGHT_KG: Double = 20.0

        /** Reps de arranque cuando no hay histórico ni reps objetivo. */
        const val DEFAULT_REPS: Double = 10.0

        /** Etiqueta del peso en un ejercicio de peso corporal sin lastre. */
        const val BODYWEIGHT_LABEL: String = "corporal"

        /** Músculos que muestra el resumen: el top 5 del día (GIM-24). */
        const val MUSCLES_TOP: Int = 5
    }
}
