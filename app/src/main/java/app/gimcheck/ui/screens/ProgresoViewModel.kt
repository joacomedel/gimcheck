package app.gimcheck.ui.screens

import app.gimcheck.AppContainer
import app.gimcheck.core.BodyMetric
import app.gimcheck.core.ChartRange
import app.gimcheck.core.E1rmFormula
import app.gimcheck.core.Exercise
import app.gimcheck.core.Muscle
import app.gimcheck.core.Session
import app.gimcheck.core.Settings
import app.gimcheck.core.VolumeBand
import app.gimcheck.core.IsoWeek
import app.gimcheck.core.currentPr
import app.gimcheck.core.e1rm
import app.gimcheck.core.e1rmBySession
import app.gimcheck.core.estado
import app.gimcheck.core.formatNumber
import app.gimcheck.core.isoWeek
import app.gimcheck.core.sessionVolume
import app.gimcheck.core.sessionVolumeByMuscle
import app.gimcheck.core.volumeByIsoWeek
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.MetricsRepository
import app.gimcheck.data.repository.SessionRepository
import app.gimcheck.data.repository.SettingsRepository
import app.gimcheck.data.settings.AppSettings
import app.gimcheck.ui.monthYear
import app.gimcheck.ui.shortDate
import app.gimcheck.ui.shortDateYear
import app.gimcheck.ui.weekRangeLabel
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toKotlinLocalDate

/** Las tres vistas de Progreso (§6 fila 6): PR, volumen y peso corporal. */
enum class ProgresoTab { PR, VOLUMEN, PESO }

/** El volumen se mira por semana ISO o por sesión. */
enum class VolumeMode { SEMANA, SESION }

/** Un ejercicio con datos para el selector de PR. */
data class ProgresoExerciseUi(val id: String, val name: String)

/** Tarjeta del PR actual. */
data class ProgresoPrUi(
    val valueLabel: String,
    val dateLabel: String,
    val setLabel: String,
    val deltaLabel: String?,
    val formulaLabel: String,
)

/** Una fila de series por músculo, con la banda de referencia. */
data class ProgresoMuscleUi(
    val id: String,
    val name: String,
    val totalLabel: String,
    val detailLabel: String,
    val value: Float,
    val max: Float,
    val bandMin: Float,
    val bandMax: Float,
    val band: VolumeBand,
)

/** Un registro de peso corporal editable. */
data class ProgresoBodyRecordUi(
    val date: LocalDate,
    val dateLabel: String,
    val weight: Double,
    val weightLabel: String,
)

/** Todo lo que dibuja **Progreso**. */
data class ProgresoUiState(
    val loading: Boolean = true,
    val tab: ProgresoTab = ProgresoTab.PR,
    val range: ChartRange = ChartRange.SIX_MONTHS,

    // PR
    val exercises: List<ProgresoExerciseUi> = emptyList(),
    val selectedExerciseId: String? = null,
    val prLabels: List<String> = emptyList(),
    val prValues: List<Double> = emptyList(),
    val prMarkIndices: Set<Int> = emptySet(),
    val pr: ProgresoPrUi? = null,

    // Volumen
    val volumeMode: VolumeMode = VolumeMode.SEMANA,
    val volumeLabels: List<String> = emptyList(),
    val volumeValues: List<Double> = emptyList(),
    val currentWeekLabel: String = "—",
    val currentWeekDelta: String? = null,
    val volumeHasData: Boolean = false,
    val includeIndirect: Boolean = false,
    val muscles: List<ProgresoMuscleUi> = emptyList(),

    // Peso corporal
    val bodyLabels: List<String> = emptyList(),
    val bodyValues: List<Double> = emptyList(),
    val bodyAverage: List<Double?> = emptyList(),
    val bodyLatestLabel: String = "—",
    val bodyDeltaLabel: String? = null,
    val bodyDraft: Double = 80.0,
    val bodyDate: LocalDate,
    val bodyEditing: Boolean = false,
    val bodyRecords: List<ProgresoBodyRecordUi> = emptyList(),
    val bodyError: Boolean = false,
)

/**
 * ViewModel de **Progreso** (GIM-14/26/27/28).
 *
 * Todo cálculo es de `:core`: e1RM por sesión ([e1rmBySession]), PR actual
 * ([currentPr]), volumen por semana ISO ([volumeByIsoWeek]) y series por músculo
 * ([sessionVolumeByMuscle]). Acá sólo se recorta al rango elegido y se formatea para
 * la pantalla. Cambiar la fórmula en Ajustes repinta la curva y la tarjeta sin tocar
 * un solo dato guardado, porque la fórmula se aplica al leer.
 */
class ProgresoViewModel internal constructor(
    private val exercises: ExerciseRepository,
    private val sessions: SessionRepository,
    private val metrics: MetricsRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val today: () -> LocalDate = { java.time.LocalDate.now().toKotlinLocalDate() },
    private val now: () -> LocalDateTime = { java.time.LocalDateTime.now().toKotlinLocalDateTime() },
) {

    constructor(container: AppContainer, scope: CoroutineScope) : this(
        exercises = container.exercises,
        sessions = container.sessions,
        metrics = container.metrics,
        settings = container.settings,
        scope = scope,
    )

    private val _state = MutableStateFlow(
        ProgresoUiState(
            bodyDate = today(),
            bodyDraft = 80.0,
        ),
    )
    val state: StateFlow<ProgresoUiState> = _state.asStateFlow()

    private var observation: Job? = null
    private var lastData: Data? = null
    private var selectedExerciseId: String? = null
    private var range: ChartRange = ChartRange.SIX_MONTHS
    private var volumeMode: VolumeMode = VolumeMode.SEMANA
    private var includeIndirect: Boolean? = null
    private var bodyDate: LocalDate = today()
    private var bodyDraft: Double = 80.0
    private var bodyEditing: Boolean = false

    fun start() {
        if (observation != null) return
        observation = scope.launch {
            combine(
                exercises.observeExercises(),
                exercises.observeMuscles(),
                settings.settings,
                sessions.observeFinishedHistory(),
                metrics.observeAll(),
            ) { catalog, muscles, prefs, history, body ->
                Data(catalog, muscles, prefs, history, body)
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

    fun selectTab(tab: ProgresoTab) {
        _state.value = _state.value.copy(tab = tab)
    }

    fun selectRange(value: ChartRange) {
        range = value
        lastData?.let(::publish)
    }

    fun selectExercise(id: String) {
        selectedExerciseId = id
        lastData?.let(::publish)
    }

    fun selectVolumeMode(mode: VolumeMode) {
        volumeMode = mode
        lastData?.let(::publish)
    }

    fun toggleIndirect() {
        includeIndirect = !(_state.value.includeIndirect)
        lastData?.let(::publish)
    }

    fun setBodyWeight(value: Double) {
        bodyDraft = roundTenth(value)
        bodyEditing = true
        lastData?.let(::publish)
    }

    /**
     * Carga un registro existente en el formulario. La fecha no se elige a mano: se
     * toca el registro y se corrige; "Anotar hoy" vuelve al día de hoy.
     */
    fun editBodyRecord(date: LocalDate, weight: Double) {
        bodyDate = date
        bodyDraft = roundTenth(weight)
        bodyEditing = true
        lastData?.let(::publish)
    }

    fun newBodyRecordToday() {
        bodyDate = today()
        val latest = lastData?.body?.firstOrNull()?.weightKg ?: 80.0
        bodyDraft = roundTenth(latest)
        bodyEditing = false
        lastData?.let(::publish)
    }

    fun saveBody() {
        val date = bodyDate
        val weight = bodyDraft
        scope.launch {
            metrics.save(
                BodyMetric(
                    date = date,
                    weightKg = roundTenth(weight),
                    updatedAt = now().toString(),
                ),
            )
            bodyEditing = false
        }
    }

    fun deleteBodyRecord(date: LocalDate) {
        scope.launch { metrics.delete(date) }
    }

    // --- proyección -------------------------------------------------------------

    private fun publish(data: Data) {
        val core = data.settings.toCoreSettings()
        val start = range.start(today())
        val inRange = data.history.filter { start == null || it.startedAt.date >= start }
        val body = data.body.sortedBy { it.date }

        val withE1rm = data.exercises
            .filter { exercise -> data.history.any { session -> session.sets.any { it.exerciseId == exercise.id && e1rm(it, core.e1rmFormula) != null } } }
            .sortedBy { it.name.lowercase() }
            .map { ProgresoExerciseUi(it.id, it.name) }
        val selected = selectedExerciseId
            ?.takeIf { id -> withE1rm.any { it.id == id } }
            ?: withE1rm.firstOrNull()?.id
        selectedExerciseId = selected

        val prPoints = selected?.let { id -> e1rmBySession(inRange, id, core.e1rmFormula) }.orEmpty()
        val allPoints = selected?.let { id -> e1rmBySession(data.history, id, core.e1rmFormula) }.orEmpty()
        val prMarks = runningPrIndices(allPoints)
        val filteredIds = prPoints.map { it.sessionId }.toSet()
        val marksInRange = allPoints.mapIndexedNotNull { index, point ->
            if (point.sessionId in filteredIds && index in prMarks) {
                prPoints.indexOfFirst { it.sessionId == point.sessionId }.takeIf { it >= 0 }
            } else {
                null
            }
        }.toSet()

        val record = selected?.let { id -> currentPr(data.history, id, core.e1rmFormula) }
        val previousWeek = isoWeek(today().minus(DatePeriod(days = 7)))
        val weeklyAll = volumeByIsoWeek(data.history, data.exercises, core)
        val thisWeek = isoWeek(today())
        val currentVolume = weeklyAll[thisWeek] ?: 0.0
        val previousVolume = weeklyAll[previousWeek]
        val includeIndirectNow = includeIndirect ?: data.settings.includeIndirect

        val weeklyRows = inRange
            .groupBy { isoWeek(it) }
            .toSortedMap(compareBy<IsoWeek>({ it.year }, { it.week }))
            .map { (week, list) ->
                WeekRow(
                    key = "${week.year}-${week.week}",
                    label = shortDate(list.minOf { it.startedAt.date }),
                    date = list.minOf { it.startedAt.date },
                    volume = list.sumOf { sessionVolume(it, data.exercises, core) },
                )
            }
        val sessionRows = inRange
            .sortedBy { it.startedAt }
            .map { session ->
                WeekRow(
                    key = session.id,
                    label = shortDate(session.startedAt.date),
                    date = session.startedAt.date,
                    volume = sessionVolume(session, data.exercises, core),
                )
            }
        val volumeRows = (if (volumeMode == VolumeMode.SEMANA) weeklyRows else sessionRows).takeLast(MAX_BARS)

        val weekSessions = data.history.filter { isoWeek(it) == thisWeek }
        val muscleVolumes = sessionVolumeByMuscle(weekSessions, data.exercises, core)
        val musclesById = data.muscles.associateBy { it.id }
        val rowsWithVolume = muscleVolumes.values
            .filter { it.direct + it.indirect > 0.0 }
            .sortedByDescending { it.total(includeIndirectNow) }
        val maxMuscle = rowsWithVolume.maxOfOrNull { it.total(includeIndirectNow) } ?: 0.0
        val meterMax = maxOf(core.optMax * 1.4, maxMuscle * 1.1).coerceAtLeast(1.0)

        val bodyLabels = body.map { shortDate(it.date) }
        val bodyValues = body.map { it.weightKg }
        val averages = body.map { metric ->
            val from = metric.date.minus(DatePeriod(days = 6))
            val window = body.filter { it.date >= from && it.date <= metric.date }
            if (window.isEmpty()) null else window.sumOf { it.weightKg } / window.size
        }
        val firstBody = body.firstOrNull()?.weightKg
        val lastBody = body.lastOrNull()?.weightKg
        if (!bodyEditing && lastBody != null) bodyDraft = roundTenth(lastBody)

        _state.value = ProgresoUiState(
            loading = false,
            tab = _state.value.tab,
            range = range,
            exercises = withE1rm,
            selectedExerciseId = selected,
            prLabels = prPoints.map { shortDate(it.date) },
            prValues = prPoints.map { it.value },
            prMarkIndices = marksInRange,
            pr = record?.let {
                ProgresoPrUi(
                    valueLabel = formatNumber(it.value) + " kg",
                    dateLabel = shortDate(it.achievedAt.date),
                    setLabel = "${formatNumber(it.set.weightKg)} kg × ${it.set.reps}",
                    deltaLabel = it.delta?.let { delta -> signedKg(delta) + " vs " + formatNumber(it.previousBest ?: 0.0) + " kg" },
                    formulaLabel = "Fórmula ${core.e1rmFormula.label}",
                )
            },
            volumeMode = volumeMode,
            volumeLabels = volumeRows.map { it.label },
            volumeValues = volumeRows.map { it.volume },
            currentWeekLabel = formatNumber(currentVolume) + " kg",
            currentWeekDelta = if (currentVolume == 0.0 && previousVolume == null) {
                null
            } else {
                val delta = currentVolume - (previousVolume ?: 0.0)
                signedKg(delta) + (if (previousVolume == null) " (sin semana previa)" else " vs semana pasada")
            },
            volumeHasData = data.history.isNotEmpty(),
            includeIndirect = includeIndirectNow,
            muscles = rowsWithVolume.map { row ->
                val total = row.total(includeIndirectNow)
                val name = musclesById[row.muscleId]?.name ?: row.muscleId
                ProgresoMuscleUi(
                    id = row.muscleId,
                    name = name,
                    totalLabel = formatNumber(total),
                    detailLabel = "directas ${formatNumber(row.direct)} · indirectas ${formatNumber(row.indirect)}",
                    value = total.toFloat(),
                    max = meterMax.toFloat(),
                    bandMin = core.optMin.toFloat(),
                    bandMax = core.optMax.toFloat(),
                    band = estado(total, core),
                )
            },
            bodyLabels = bodyLabels,
            bodyValues = bodyValues,
            bodyAverage = averages,
            bodyLatestLabel = lastBody?.let { formatNumber(it) + " kg" } ?: "—",
            bodyDeltaLabel = if (firstBody != null && lastBody != null && body.size > 1) {
                signedKg(lastBody - firstBody) + " desde " + shortDateYear(body.first().date)
            } else {
                null
            },
            bodyDraft = bodyDraft,
            bodyDate = bodyDate,
            bodyEditing = bodyEditing,
            bodyRecords = body.reversed().map {
                ProgresoBodyRecordUi(
                    date = it.date,
                    dateLabel = shortDateYear(it.date),
                    weight = it.weightKg,
                    weightLabel = formatNumber(it.weightKg) + " kg",
                )
            },
        )
    }

    /** Índices de [points] que establecieron un récord en orden cronológico. */
    private fun runningPrIndices(points: List<app.gimcheck.core.E1rmPoint>): Set<Int> {
        val out = mutableSetOf<Int>()
        var best: Double? = null
        points.forEachIndexed { index, point ->
            if (best == null || point.value > best!!) {
                best = point.value
                out += index
            }
        }
        return out
    }

    private fun signedKg(value: Double): String {
        val sign = if (value > 0.0) "+" else if (value < 0.0) "−" else ""
        return sign + formatNumber(abs(value)) + " kg"
    }

    private data class WeekRow(val key: String, val label: String, val date: LocalDate, val volume: Double)

    private data class Data(
        val exercises: List<Exercise>,
        val muscles: List<Muscle>,
        val settings: AppSettings,
        val history: List<Session>,
        val body: List<BodyMetric>,
    )

    companion object {
        /** Barras visibles: más de 12 semanas no se leen en un teléfono. */
        const val MAX_BARS: Int = 12
        fun roundTenth(value: Double): Double = kotlin.math.round(value * 10.0) / 10.0
    }
}
