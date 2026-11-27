package app.gimcheck.ui.screens

import app.gimcheck.AppContainer
import app.gimcheck.core.Muscle
import app.gimcheck.core.Routine
import app.gimcheck.core.Settings
import app.gimcheck.core.VolumeBand
import app.gimcheck.core.dayStats
import app.gimcheck.core.estado
import app.gimcheck.core.formatDuration
import app.gimcheck.core.formatNumber
import app.gimcheck.core.heavyPairs
import app.gimcheck.core.supersetConflicts
import app.gimcheck.core.volume
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.RoutineRepository
import app.gimcheck.data.repository.SettingsRepository
import app.gimcheck.data.settings.AppSettings
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Un plan elegible en los dos selectores de Comparar. */
data class CompararRoutineUi(val id: String, val name: String)

/** Una fila de la tabla resumen: métrica, valor del plan A y del B, y el delta. */
data class CompararMetricUi(
    val label: String,
    val a: String,
    val b: String,
    val delta: String? = null,
    val deltaSign: Int = 0,
    val lowerIsBetter: Boolean = false,
)

/** Duración de un día en los dos planes. */
data class CompararDayUi(
    val label: String,
    val a: String,
    val b: String,
    val delta: String,
)

/** Series por músculo en A y en B, con la banda del rango semanal. */
data class CompararMuscleUi(
    val id: String,
    val name: String,
    val aLabel: String,
    val bLabel: String,
    val aValue: Float,
    val bValue: Float,
    val max: Float,
    val bandMin: Float,
    val bandMax: Float,
    val bandA: VolumeBand,
    val bandB: VolumeBand,
)

data class CompararUiState(
    val loading: Boolean = true,
    val routines: List<CompararRoutineUi> = emptyList(),
    val aId: String? = null,
    val bId: String? = null,
    val metrics: List<CompararMetricUi> = emptyList(),
    val days: List<CompararDayUi> = emptyList(),
    val muscles: List<CompararMuscleUi> = emptyList(),
    val includeIndirect: Boolean = false,
)

/**
 * ViewModel de **Comparar** (GIM-19): paridad con la pestaña Análisis de la web.
 *
 * Todo sale de `:core`: `dayStats` (tiempo/series), `volume` (series por músculo con
 * banda), `supersetConflicts` y `heavyPairs` (calidad de las superseries). La pantalla
 * sólo elige A y B y dibuja; no hay una segunda fórmula de series ni de duración.
 *
 * El delta se muestra como B − A: positivo significa que el plan nuevo suma, negativo
 * que resta. Para "conflictos" y "superseries pesadas" el signo se invierte en la
 * pantalla (menos es mejor).
 */
class CompararViewModel internal constructor(
    private val routines: RoutineRepository,
    private val exercises: ExerciseRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {

    constructor(container: AppContainer, scope: CoroutineScope) : this(
        routines = container.routines,
        exercises = container.exercises,
        settings = container.settings,
        scope = scope,
    )

    private val _state = MutableStateFlow(CompararUiState())
    val state: StateFlow<CompararUiState> = _state.asStateFlow()

    private var observation: Job? = null
    private var lastData: Data? = null
    private var aId: String? = null
    private var bId: String? = null
    private var includeIndirectOverride: Boolean? = null

    fun start() {
        if (observation != null) return
        observation = scope.launch {
            combine(
                routines.observeRoutines(),
                exercises.observeMuscles(),
                settings.settings,
            ) { plans, muscles, prefs -> Data(plans, muscles, prefs) }
                .collect { data ->
                    lastData = data
                    publish(data)
                }
        }
    }

    fun stop() {
        observation?.cancel()
        observation = null
    }

    fun selectA(id: String) {
        aId = id
        lastData?.let(::publish)
    }

    fun selectB(id: String) {
        bId = id
        lastData?.let(::publish)
    }

    fun toggleIndirect() {
        includeIndirectOverride = !(_state.value.includeIndirect)
        lastData?.let(::publish)
    }

    private fun publish(data: Data) {
        val plans = data.routines.filterNot { it.archived }
        if (plans.size < 2) {
            _state.value = CompararUiState(
                loading = false,
                routines = plans.map { CompararRoutineUi(it.id, it.name) },
            )
            return
        }
        val a = plans.firstOrNull { it.id == aId } ?: plans[0]
        val b = plans.firstOrNull { it.id == bId } ?: plans[1]
        aId = a.id
        bId = b.id

        val core = data.settings.toCoreSettings()
        val metricsA = metricsOf(a, data, core)
        val metricsB = metricsOf(b, data, core)
        val includeIndirect = includeIndirectOverride ?: data.settings.includeIndirect

        val rows = listOf(
            metric("Tiempo semanal", metricsA.totalSeconds, metricsB.totalSeconds, format = ::formatDuration),
            metric("Series", metricsA.sets, metricsB.sets) { it.toString() },
            metric("Multiarticulares", metricsA.compounds, metricsB.compounds) { it.toString() },
            metric("Multiarticular", metricsA.compoundPct, metricsB.compoundPct) { "$it %" },
            metric("Series de brazo", metricsA.armSets, metricsB.armSets) { it.toString() },
            metric("Drop sets", metricsA.dropSets, metricsB.dropSets) { it.toString() },
            metric("Superseries", metricsA.supersets, metricsB.supersets) { it.toString() },
            metric("Conflictos de músculo", metricsA.conflicts, metricsB.conflicts, lowerIsBetter = true) { it.toString() },
            metric("Superseries con dos multiarticulares", metricsA.heavyPairs, metricsB.heavyPairs, lowerIsBetter = true) { it.toString() },
        )

        val days = (0 until maxOf(a.days.size, b.days.size)).map { index ->
            val dayA = a.days.getOrNull(index)
            val dayB = b.days.getOrNull(index)
            val secondsA = dayA?.let { dayStats(it.items, core).totalSeconds } ?: 0
            val secondsB = dayB?.let { dayStats(it.items, core).totalSeconds } ?: 0
            CompararDayUi(
                label = when {
                    dayA != null && dayB != null && dayA.name == dayB.name -> dayA.name
                    dayA != null && dayB != null -> "${dayA.name} / ${dayB.name}"
                    dayA != null -> "${dayA.name} / —"
                    else -> "— / ${dayB?.name ?: "—"}"
                },
                a = if (dayA != null) formatDuration(secondsA) else "—",
                b = if (dayB != null) formatDuration(secondsB) else "—",
                delta = signedDuration(secondsB - secondsA),
            )
        }

        val volumeA = volume(a.days, core, data.muscles)
        val volumeB = volume(b.days, core, data.muscles)
        val muscleNames = data.muscles.associateBy { it.id }
        val muscleRows = (volumeA.keys + volumeB.keys)
            .distinct()
            .map { muscleId ->
                val rowA = volumeA[muscleId]
                val rowB = volumeB[muscleId]
                val totalA = rowA?.total(includeIndirect) ?: 0.0
                val totalB = rowB?.total(includeIndirect) ?: 0.0
                Triple(muscleId, totalA, totalB)
            }
            .filter { it.second > 0.0 || it.third > 0.0 }
            .sortedByDescending { maxOf(it.second, it.third) }
        val maxMuscle = maxOf(
            muscleRows.maxOfOrNull { maxOf(it.second, it.third) } ?: 0.0,
            core.optMax.toDouble(),
        )

        _state.value = CompararUiState(
            loading = false,
            routines = plans.map { CompararRoutineUi(it.id, it.name) },
            aId = a.id,
            bId = b.id,
            metrics = rows,
            days = days,
            muscles = muscleRows.map { (muscleId, totalA, totalB) ->
                CompararMuscleUi(
                    id = muscleId,
                    name = muscleNames[muscleId]?.name ?: muscleId,
                    aLabel = formatNumber(totalA),
                    bLabel = formatNumber(totalB),
                    aValue = totalA.toFloat(),
                    bValue = totalB.toFloat(),
                    max = (maxMuscle * 1.1).toFloat().coerceAtLeast(1f),
                    bandMin = core.optMin.toFloat(),
                    bandMax = core.optMax.toFloat(),
                    bandA = estado(totalA, core),
                    bandB = estado(totalB, core),
                )
            },
            includeIndirect = includeIndirect,
        )
    }

    /** Métricas de un plan, todas juntas para no recorrer los días cinco veces. */
    private fun metricsOf(routine: Routine, data: Data, core: Settings): PlanMetrics {
        var seconds = 0
        var sets = 0
        var compounds = 0
        var items = 0
        var armSets = 0
        var drops = 0
        var supersets = 0
        var conflicts = 0
        var heavy = 0
        val muscleNames = data.muscles.associateBy { it.id }
        for (day in routine.days) {
            seconds += dayStats(day.items, core).totalSeconds
            for (item in day.items) {
                items += 1
                sets += item.sets
                if (item.exercise.compound) compounds += 1
                if (item.dropSet) drops += 1
                if (item.supersetNext) supersets += 1
                val primary = muscleNames[item.exercise.primaryMuscleId]?.name?.lowercase().orEmpty()
                if (primary.contains("bícep") || primary.contains("trícep") || primary.contains("antebrazo")) {
                    armSets += item.sets
                }
            }
            conflicts += supersetConflicts(day).size
            heavy += heavyPairs(day).size
        }
        return PlanMetrics(
            totalSeconds = seconds,
            sets = sets,
            compounds = compounds,
            compoundPct = if (items == 0) 0 else (compounds * 100.0 / items).roundToInt(),
            armSets = armSets,
            dropSets = drops,
            supersets = supersets,
            conflicts = conflicts,
            heavyPairs = heavy,
        )
    }

    private fun <T> metric(
        label: String,
        a: T,
        b: T,
        lowerIsBetter: Boolean = false,
        format: (T) -> String,
    ): CompararMetricUi {
        val delta = when {
            a is Int && b is Int -> b - a
            else -> null
        }
        return CompararMetricUi(
            label = label,
            a = format(a),
            b = format(b),
            delta = delta?.let { signedInt(it) },
            deltaSign = delta?.compareTo(0) ?: 0,
            lowerIsBetter = lowerIsBetter,
        )
    }

    private fun signedInt(value: Int): String {
        val sign = if (value > 0) "+" else if (value < 0) "−" else ""
        return sign + abs(value)
    }

    private fun signedDuration(seconds: Int): String {
        if (seconds == 0) return "—"
        val sign = if (seconds > 0) "+" else "−"
        return sign + formatDuration(abs(seconds))
    }

    private data class PlanMetrics(
        val totalSeconds: Int,
        val sets: Int,
        val compounds: Int,
        val compoundPct: Int,
        val armSets: Int,
        val dropSets: Int,
        val supersets: Int,
        val conflicts: Int,
        val heavyPairs: Int,
    )

    private data class Data(
        val routines: List<Routine>,
        val muscles: List<Muscle>,
        val settings: AppSettings,
    )
}
