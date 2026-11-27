package app.gimcheck.ui.screens

import app.gimcheck.AppContainer
import app.gimcheck.core.E1rmFormula
import app.gimcheck.core.Exercise
import app.gimcheck.core.Routine
import app.gimcheck.core.Session
import app.gimcheck.core.SessionSet
import app.gimcheck.core.Settings
import app.gimcheck.core.formatDuration
import app.gimcheck.core.formatNumber
import app.gimcheck.core.isoWeek
import app.gimcheck.core.sessionPrCounts
import app.gimcheck.core.sessionVolume
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.RoutineRepository
import app.gimcheck.data.repository.SessionRepository
import app.gimcheck.data.repository.SettingsRepository
import app.gimcheck.data.settings.AppSettings
import app.gimcheck.ui.longDate
import app.gimcheck.ui.weekdayDate
import app.gimcheck.ui.weekRangeLabel
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toJavaLocalDateTime

/**
 * Una sesión en la lista del Historial.
 *
 * [prCount] sale de `:core` ([sessionPrCounts]): la fila no decide si hubo PR, sólo
 * muestra el número que ya calculó el dominio contra el histórico anterior.
 */
data class HistorialSessionUi(
    val id: String,
    val dateLabel: String,
    val title: String,
    val subtitle: String,
    val volumeLabel: String,
    val prCount: Int,
)

/** Una semana ISO con sus sesiones, más reciente primero. */
data class HistorialWeekUi(
    val key: String,
    val label: String,
    val volumeLabel: String,
    val sessions: List<HistorialSessionUi>,
)

/** Una serie del detalle, ya formateada para `SetRow`. */
data class HistorialSetUi(
    val id: String,
    val number: Int,
    val weightLabel: String,
    val repsLabel: String,
    val isWarmup: Boolean,
)

/** Un ejercicio del detalle con sus series. */
data class HistorialExerciseUi(
    val exerciseId: String,
    val name: String,
    val setsLabel: String,
    val sets: List<HistorialSetUi>,
)

/** Detalle de una sesión: lo que se abre al tocar una fila. */
data class HistorialDetailUi(
    val sessionId: String,
    val dateLabel: String,
    val title: String,
    val durationLabel: String,
    val setsLabel: String,
    val volumeLabel: String,
    val prCount: Int,
    val exercises: List<HistorialExerciseUi>,
    val editingSetId: String? = null,
    val editWeight: Double = 0.0,
    val editReps: Double = 0.0,
    /** `true` después de tocar "Borrar": reemplaza el botón por [ConfirmInline]. */
    val confirmingDeleteSet: Boolean = false,
    /** `true` después de tocar "Borrar sesión": confirmación en el lugar. */
    val confirmingDeleteSession: Boolean = false,
)

/** Todo lo que dibuja **Historial** (§6 fila 5, GIM-25). */
data class HistorialUiState(
    val loading: Boolean = true,
    val weeks: List<HistorialWeekUi> = emptyList(),
    val detail: HistorialDetailUi? = null,
    val error: String? = null,
)

/**
 * ViewModel de **Historial**: lista por semana ISO y detalle con corrección de series.
 *
 * **Corregir una serie** pasa por [SessionRepository.updateSet], no por SQL de la UI.
 * Después de guardar, la `Flow` del histórico vuelve a emitir y el detalle se arma de
 * nuevo: el volumen, los PRs y la fila de la semana se recalculan solos. Borrar una
 * sesión cae por CASCADE sobre sus series y también vuelve por la `Flow`.
 *
 * El ViewModel guarda el id de la sesión abierta y el de la serie en edición, no copias
 * de los datos: si algo cambia mientras la pantalla está abierta, el detalle no muestra
 * una foto vieja.
 */
class HistorialViewModel internal constructor(
    private val routines: RoutineRepository,
    private val exercises: ExerciseRepository,
    private val sessions: SessionRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val now: () -> LocalDateTime = { java.time.LocalDateTime.now().toKotlinLocalDateTime() },
) {

    constructor(container: AppContainer, scope: CoroutineScope) : this(
        routines = container.routines,
        exercises = container.exercises,
        sessions = container.sessions,
        settings = container.settings,
        scope = scope,
    )

    private val _state = MutableStateFlow(HistorialUiState())
    val state: StateFlow<HistorialUiState> = _state.asStateFlow()

    private var observation: Job? = null
    private var lastData: Data? = null
    private var openSessionId: String? = null
    private var editingSetId: String? = null
    private var editWeight: Double = 0.0
    private var editReps: Double = 0.0
    private var confirmingDeleteSet: Boolean = false
    private var confirmingDeleteSession: Boolean = false

    fun start() {
        if (observation != null) return
        observation = scope.launch {
            combine(
                routines.observeRoutines(),
                exercises.observeExercises(),
                settings.settings,
                sessions.observeFinishedHistory(),
            ) { plans, catalog, prefs, history ->
                Data(plans, catalog, prefs, history)
            }.collect { data ->
                lastData = data
                publish(data)
            }
        }
    }

    fun stop() {
        observation?.cancel()
        observation = null
    }

    fun openSession(id: String) {
        openSessionId = id
        editingSetId = null
        confirmingDeleteSession = false
        lastData?.let(::publish)
    }

    fun closeDetail() {
        openSessionId = null
        editingSetId = null
        confirmingDeleteSession = false
        confirmingDeleteSet = false
        lastData?.let(::publish)
    }

    fun startEditSet(setId: String) {
        val set = lastData?.setsById()?.get(setId) ?: return
        editingSetId = setId
        editWeight = set.weightKg
        editReps = set.reps.toDouble()
        confirmingDeleteSet = false
        lastData?.let(::publish)
    }

    fun setEditWeight(value: Double) {
        editWeight = roundTenth(value)
        lastData?.let(::publish)
    }

    fun setEditReps(value: Double) {
        editReps = value.coerceIn(0.0, 100.0)
        lastData?.let(::publish)
    }

    fun cancelEditSet() {
        editingSetId = null
        confirmingDeleteSet = false
        lastData?.let(::publish)
    }

    fun saveSetEdit() {
        val id = editingSetId ?: return
        scope.launch {
            sessions.updateSet(id, roundTenth(editWeight), editReps.toInt().coerceAtLeast(0), rpe = null)
            editingSetId = null
        }
    }

    fun askDeleteSet() {
        confirmingDeleteSet = true
        lastData?.let(::publish)
    }

    fun cancelDeleteSet() {
        confirmingDeleteSet = false
        lastData?.let(::publish)
    }

    fun deleteSet() {
        val id = editingSetId ?: return
        confirmingDeleteSet = false
        editingSetId = null
        scope.launch { sessions.deleteSet(id) }
    }

    fun askDeleteSession() {
        confirmingDeleteSession = true
        lastData?.let(::publish)
    }

    fun cancelDeleteSession() {
        confirmingDeleteSession = false
        lastData?.let(::publish)
    }

    fun deleteSession() {
        val id = openSessionId ?: return
        confirmingDeleteSession = false
        openSessionId = null
        scope.launch { sessions.deleteSession(id) }
    }

    // --- proyección -------------------------------------------------------------

    private fun publish(data: Data) {
        val settings = data.settings.toCoreSettings()
        val prs = sessionPrCounts(data.history, settings.e1rmFormula)
        val sessionsById = data.history.associateBy { it.id }

        val detail = openSessionId
            ?.let(sessionsById::get)
            ?.let { session -> detailOf(session, data, settings, prs[session.id].orEmpty().size) }

        val weeks = data.history
            .sortedByDescending { it.startedAt }
            .groupBy { isoWeek(it) }
            .map { (week, sessions) ->
                HistorialWeekUi(
                    key = "${week.year}-W${week.week.toString().padStart(2, '0')}",
                    label = weekRangeLabel(sessions.first().startedAt.date),
                    volumeLabel = formatNumber(
                        sessions.sumOf { session ->
                            sessionVolume(session, data.exercises, settings)
                        },
                    ) + " kg",
                    sessions = sessions.map { session ->
                        rowOf(session, data, settings, prs[session.id].orEmpty().size)
                    },
                )
            }

        _state.value = HistorialUiState(loading = false, weeks = weeks, detail = detail)
    }

    private fun rowOf(
        session: Session,
        data: Data,
        settings: Settings,
        prCount: Int,
    ): HistorialSessionUi {
        val title = titleOf(session, data.routines)
        val minutes = minutesOf(session)
        val workSets = session.sets.count { !it.isWarmup }
        return HistorialSessionUi(
            id = session.id,
            dateLabel = weekdayDate(session.startedAt.date),
            title = title,
            subtitle = buildString {
                append(formatDuration(minutes * 60))
                append(" · ")
                append(if (workSets == 1) "1 serie" else "$workSets series")
                if (prCount > 0) {
                    append(" · ")
                    append(if (prCount == 1) "1 PR" else "$prCount PRs")
                }
            },
            volumeLabel = formatNumber(sessionVolume(session, data.exercises, settings)) + " kg",
            prCount = prCount,
        )
    }

    private fun detailOf(
        session: Session,
        data: Data,
        settings: Settings,
        prCount: Int,
    ): HistorialDetailUi {
        val exercisesById = data.exercises.associateBy { it.id }
        val groups = session.sets
            .sortedBy { it.completedAt }
            .groupBy { it.exerciseId }
        return HistorialDetailUi(
            sessionId = session.id,
            dateLabel = longDate(session.startedAt.date),
            title = titleOf(session, data.routines),
            durationLabel = formatDuration(minutesOf(session) * 60),
            setsLabel = session.sets.count { !it.isWarmup }.toString(),
            volumeLabel = formatNumber(sessionVolume(session, data.exercises, settings)) + " kg",
            prCount = prCount,
            exercises = groups.map { (exerciseId, sets) ->
                HistorialExerciseUi(
                    exerciseId = exerciseId,
                    name = exercisesById[exerciseId]?.name ?: "Ejercicio eliminado",
                    setsLabel = if (sets.size == 1) "1 serie" else "${sets.size} series",
                    sets = sets.mapIndexed { index, set -> setUi(set, index + 1) },
                )
            },
            editingSetId = editingSetId,
            editWeight = editWeight,
            editReps = editReps,
            confirmingDeleteSet = confirmingDeleteSet,
            confirmingDeleteSession = confirmingDeleteSession,
        )
    }

    private fun setUi(set: SessionSet, number: Int): HistorialSetUi = HistorialSetUi(
        id = set.id,
        number = number,
        weightLabel = formatNumber(set.weightKg) + " kg",
        repsLabel = set.reps.toString(),
        isWarmup = set.isWarmup,
    )

    private fun titleOf(session: Session, routines: List<Routine>): String {
        val routine = session.routineId?.let { id -> routines.firstOrNull { it.id == id } }
        if (routine == null && session.routineId != null) return "Rutina eliminada"
        val day = session.routineDayId?.let { id -> routine?.days?.firstOrNull { it.id == id } }
        return day?.name ?: routine?.name ?: "Entrenamiento libre"
    }

    private fun minutesOf(session: Session): Long {
        val end = session.endedAt ?: now()
        val seconds = Duration.between(
            session.startedAt.toJavaLocalDateTime(),
            end.toJavaLocalDateTime(),
        ).seconds
        return (seconds / 60).coerceAtLeast(1)
    }

    private fun Data.setsById(): Map<String, SessionSet> =
        history.flatMap { it.sets }.associateBy { it.id }

    private data class Data(
        val routines: List<Routine>,
        val exercises: List<Exercise>,
        val settings: AppSettings,
        val history: List<Session>,
    )

    companion object {
        fun roundTenth(value: Double): Double = kotlin.math.round(value * 10.0) / 10.0
    }
}

/** Fórmula del PR que usa la pantalla; se expone para tests y para el copy. */
internal fun formulaLabel(formula: E1rmFormula): String = formula.label
