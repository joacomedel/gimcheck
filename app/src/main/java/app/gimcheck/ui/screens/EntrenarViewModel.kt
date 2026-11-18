package app.gimcheck.ui.screens

import app.gimcheck.AppContainer
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
import app.gimcheck.core.formatNumber
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
import kotlinx.datetime.toKotlinLocalDateTime
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Gancho del **cronómetro de descanso** (`docs/especificacion.md` §4, GIM-22).
 *
 * La pantalla de este ticket muestra la cuenta regresiva desde `rest_ends_at` y deja el
 * aviso inline, pero el servicio en primer plano y la alarma exacta de respaldo son
 * GIM-22. El [EntrenarViewModel] ya sabe **cuándo** hay que arrancar, cortar o dar por
 * terminado un descanso —son los efectos de la máquina de `:core`— y los publica acá.
 *
 * Con [Noop] (el default y lo que usa este ticket) nadie escucha: el cronómetro vive
 * dentro de la pantalla y muere con ella, que es exactamente lo que GIM-22 viene a
 * arreglar. No hay lógica de alarma en este archivo a propósito.
 */
interface RestGateway {

    /** Hay que (re)programar el cronómetro con [seconds]. */
    fun onStartRest(seconds: Int)

    /** Hay que detener el cronómetro y la alarma de respaldo. */
    fun onCancelRest()

    /** El descanso llegó a cero: avisar según `alertMode` (§5). */
    fun onRestFinished()

    /** Nadie escucha: el cronómetro es sólo la cuenta regresiva de la pantalla. */
    object Noop : RestGateway {
        override fun onStartRest(seconds: Int) = Unit
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
    /** Series anotadas en total, para el placeholder de fin (el resumen real es GIM-24). */
    val finishedSets: Int = 0,
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
 */
class EntrenarViewModel internal constructor(
    private val sessions: SessionRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val now: () -> LocalDateTime = { java.time.LocalDateTime.now().toKotlinLocalDateTime() },
    private val nowInstant: () -> Instant = Instant::now,
    private val restGateway: RestGateway = RestGateway.Noop,
) {

    constructor(container: AppContainer, scope: CoroutineScope) : this(
        sessions = container.sessions,
        settings = container.settings,
        scope = scope,
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

    // --- implementación (suspend: los usan los tests sin esperar corrutinas) ------

    internal suspend fun logSetNow() {
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
        session = guided
        if (guided == null) {
            draftItemId = null
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
                is SessionEffect.StartRest -> restGateway.onStartRest(effect.seconds)
                SessionEffect.CancelRest -> restGateway.onCancelRest()
                SessionEffect.RestFinished -> {
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
        if (transition.state.status == SessionStatus.FINISHED) finishedState = transition.state
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
            )
            return
        }
        val guided = session
        if (guided == null) {
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
    }
}
