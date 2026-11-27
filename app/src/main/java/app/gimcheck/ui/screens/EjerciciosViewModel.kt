package app.gimcheck.ui.screens

import app.gimcheck.AppContainer
import app.gimcheck.core.Exercise
import app.gimcheck.core.LoadType
import app.gimcheck.core.Muscle
import app.gimcheck.core.Routine
import app.gimcheck.core.Secondary
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.RoutineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

/** Error tipado del editor de ejercicios; la pantalla elige el copy. */
enum class EjercicioError {
    NOMBRE_VACIO,
    NOMBRE_DUPLICADO,
    SIN_MUSCULO,
    TIEMPO_INVALIDO,
    FACTOR_INVALIDO,
    MUSCULO_DUPLICADO,
}

/** Un músculo con su uso actual (para el filtro y el editor). */
data class EjercicioMuscleUi(
    val id: String,
    val name: String,
    val selected: Boolean,
)

/** Una fila del catálogo. */
data class EjercicioRowUi(
    val id: String,
    val name: String,
    val muscleName: String,
    val detail: String,
    val usageLabel: String,
    val compound: Boolean,
    val loadType: LoadType,
)

/** Editor abierto: alta o edición de un ejercicio. */
data class EjercicioEditUi(
    val id: String?,
    val name: String,
    val primaryMuscleId: String,
    val compound: Boolean,
    val loadType: LoadType,
    val secondsPerSet: Double,
    val restSeconds: Double,
    val secondaries: List<Secondary>,
    val pickingSecondary: Boolean = false,
    val error: EjercicioError? = null,
    val confirmingDelete: Boolean = false,
)

data class EjerciciosUiState(
    val loading: Boolean = true,
    val query: String = "",
    val muscles: List<EjercicioMuscleUi> = emptyList(),
    val rows: List<EjercicioRowUi> = emptyList(),
    val allCount: Int = 0,
    val filterMuscleId: String? = null,
    val editing: EjercicioEditUi? = null,
    val newMuscleName: String = "",
    val muscleError: EjercicioError? = null,
)

/**
 * ViewModel del **catálogo de ejercicios** (GIM-18).
 *
 * Reglas de negocio:
 * - El nombre no se repite (sin distinguir mayúsculas ni espacios de los extremos).
 * - El principal siempre existe y no puede ir como secundario (lo valida también el
 *   DAO); los secundarios llevan factor 0,1..1,0.
 * - "En N lugares" se cuenta sobre todas las rutinas: es el aviso antes de borrar.
 * - Agregar un músculo no permite duplicados por nombre (tampoco por id).
 *
 * Toda escritura va por [ExerciseRepository]; la UI sólo muestra el estado.
 */
class EjerciciosViewModel internal constructor(
    private val exercises: ExerciseRepository,
    private val routines: RoutineRepository,
    private val scope: CoroutineScope,
) {

    constructor(container: AppContainer, scope: CoroutineScope) : this(
        exercises = container.exercises,
        routines = container.routines,
        scope = scope,
    )

    private val _state = MutableStateFlow(EjerciciosUiState())
    val state: StateFlow<EjerciciosUiState> = _state.asStateFlow()

    private var observation: Job? = null
    private var lastData: Data? = null
    private var query: String = ""
    private var filterMuscleId: String? = null
    private var editing: EjercicioEditUi? = null
    private var newMuscleName: String = ""
    private var muscleError: EjercicioError? = null

    fun start() {
        if (observation != null) return
        observation = scope.launch {
            combine(
                exercises.observeExercises(),
                exercises.observeMuscles(),
                routines.observeRoutines(),
            ) { catalog, muscles, plans -> Data(catalog, muscles, plans) }
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

    fun setQuery(value: String) {
        query = value
        lastData?.let(::publish)
    }

    fun selectMuscle(id: String?) {
        filterMuscleId = id
        lastData?.let(::publish)
    }

    fun startCreate() {
        val muscle = lastData?.muscles?.firstOrNull()?.id.orEmpty()
        editing = EjercicioEditUi(
            id = null,
            name = "",
            primaryMuscleId = muscle,
            compound = false,
            loadType = LoadType.EXTERNAL,
            secondsPerSet = 40.0,
            restSeconds = 90.0,
            secondaries = emptyList(),
        )
        lastData?.let(::publish)
    }

    fun startEdit(id: String) {
        val exercise = lastData?.exercises?.firstOrNull { it.id == id } ?: return
        editing = EjercicioEditUi(
            id = exercise.id,
            name = exercise.name,
            primaryMuscleId = exercise.primaryMuscleId,
            compound = exercise.compound,
            loadType = exercise.loadType,
            secondsPerSet = exercise.secondsPerSet.toDouble(),
            restSeconds = exercise.restSeconds.toDouble(),
            secondaries = exercise.secondaries,
        )
        lastData?.let(::publish)
    }

    fun cancelEdit() {
        editing = null
        lastData?.let(::publish)
    }

    fun setName(value: String) {
        editing = editing?.copy(name = value, error = null)
        lastData?.let(::publish)
    }

    fun setPrimaryMuscle(id: String) {
        val current = editing ?: return
        editing = current.copy(
            primaryMuscleId = id,
            secondaries = current.secondaries.filterNot { it.muscleId == id },
            error = null,
        )
        lastData?.let(::publish)
    }

    /** Limpia buscador y filtro de músculo: es la acción del estado vacío. */
    fun clearFilters() {
        query = ""
        filterMuscleId = null
        lastData?.let(::publish)
    }

    fun setCompound(value: Boolean) {
        editing = editing?.copy(compound = value)
        lastData?.let(::publish)
    }

    fun setLoadType(value: LoadType) {
        editing = editing?.copy(loadType = value)
        lastData?.let(::publish)
    }

    fun setSecondsPerSet(value: Double) {
        editing = editing?.copy(secondsPerSet = value, error = null)
        lastData?.let(::publish)
    }

    fun setRestSeconds(value: Double) {
        editing = editing?.copy(restSeconds = value, error = null)
        lastData?.let(::publish)
    }

    fun openSecondaryPicker() {
        editing = editing?.copy(pickingSecondary = true)
        lastData?.let(::publish)
    }

    fun closeSecondaryPicker() {
        editing = editing?.copy(pickingSecondary = false)
        lastData?.let(::publish)
    }

    fun addSecondary(muscleId: String) {
        val current = editing ?: return
        if (muscleId == current.primaryMuscleId || current.secondaries.any { it.muscleId == muscleId }) {
            editing = current.copy(pickingSecondary = false, error = EjercicioError.MUSCULO_DUPLICADO)
        } else {
            editing = current.copy(
                secondaries = current.secondaries + Secondary(muscleId, 0.5),
                pickingSecondary = false,
                error = null,
            )
        }
        lastData?.let(::publish)
    }

    fun setSecondaryFactor(muscleId: String, factor: Double) {
        val current = editing ?: return
        editing = current.copy(
            secondaries = current.secondaries.map {
                if (it.muscleId == muscleId) it.copy(factor = roundTenth(factor).coerceIn(0.1, 1.0)) else it
            },
            error = null,
        )
        lastData?.let(::publish)
    }

    fun removeSecondary(muscleId: String) {
        val current = editing ?: return
        editing = current.copy(secondaries = current.secondaries.filterNot { it.muscleId == muscleId })
        lastData?.let(::publish)
    }

    fun save() {
        val current = editing ?: return
        val data = lastData ?: return
        val name = current.name.trim()
        val error = when {
            name.isEmpty() -> EjercicioError.NOMBRE_VACIO
            data.exercises.any { it.id != current.id && it.name.trim().equals(name, ignoreCase = true) } ->
                EjercicioError.NOMBRE_DUPLICADO
            current.primaryMuscleId.isBlank() -> EjercicioError.SIN_MUSCULO
            current.secondsPerSet !in 5.0..600.0 || current.restSeconds !in 0.0..900.0 ->
                EjercicioError.TIEMPO_INVALIDO
            current.secondaries.any { it.factor !in 0.1..1.0 } -> EjercicioError.FACTOR_INVALIDO
            else -> null
        }
        if (error != null) {
            editing = current.copy(error = error)
            publish(data)
            return
        }

        val exercise = Exercise(
            id = current.id ?: UUID.randomUUID().toString(),
            name = name,
            primaryMuscleId = current.primaryMuscleId,
            secondaries = current.secondaries,
            secondsPerSet = current.secondsPerSet.toInt(),
            restSeconds = current.restSeconds.toInt(),
            compound = current.compound,
            loadType = current.loadType,
        )
        scope.launch {
            exercises.saveExercise(exercise)
            editing = null
            lastData?.let(::publish)
        }
    }

    fun askDelete() {
        editing = editing?.copy(confirmingDelete = true)
        lastData?.let(::publish)
    }

    fun cancelDelete() {
        editing = editing?.copy(confirmingDelete = false)
        lastData?.let(::publish)
    }

    fun delete() {
        val id = editing?.id ?: return
        editing = null
        lastData?.let(::publish)
        scope.launch { exercises.deleteExercise(id) }
    }

    fun setNewMuscleName(value: String) {
        newMuscleName = value
        muscleError = null
        lastData?.let(::publish)
    }

    fun addMuscle() {
        val data = lastData ?: return
        val name = newMuscleName.trim()
        when {
            name.isEmpty() -> muscleError = EjercicioError.NOMBRE_VACIO
            data.muscles.any { it.name.trim().equals(name, ignoreCase = true) } ->
                muscleError = EjercicioError.NOMBRE_DUPLICADO
            else -> {
                val muscle = Muscle(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    position = (data.muscles.maxOfOrNull { it.position } ?: 0) + 1,
                )
                muscleError = null
                scope.launch {
                    exercises.saveMuscle(muscle)
                    newMuscleName = ""
                    lastData?.let(::publish)
                }
            }
        }
        publish(data)
    }

    // --- proyección -------------------------------------------------------------

    private fun publish(data: Data) {
        val musclesById = data.muscles.associateBy { it.id }
        val usage = HashMap<String, Int>()
        data.routines.forEach { routine ->
            routine.days.forEach { day ->
                day.items.forEach { item -> usage[item.exerciseId] = (usage[item.exerciseId] ?: 0) + 1 }
            }
        }
        val normalized = query.trim().lowercase()
        val rows = data.exercises
            .filter { exercise ->
                (normalized.isEmpty() || exercise.name.lowercase().contains(normalized)) &&
                    (filterMuscleId == null ||
                        exercise.primaryMuscleId == filterMuscleId ||
                        exercise.secondaries.any { it.muscleId == filterMuscleId })
            }
            .sortedBy { it.name.lowercase() }
            .map { exercise ->
                val count = usage[exercise.id] ?: 0
                EjercicioRowUi(
                    id = exercise.id,
                    name = exercise.name,
                    muscleName = musclesById[exercise.primaryMuscleId]?.name ?: "—",
                    detail = buildString {
                        append(if (exercise.compound) "multiarticular" else "aislado")
                        append(" · ")
                        append(if (exercise.loadType == LoadType.BODYWEIGHT) "peso corporal" else "peso externo")
                        append(" · ")
                        append(exercise.secondsPerSet)
                        append(" s/serie · ")
                        append(exercise.restSeconds)
                        append(" s descanso")
                    },
                    usageLabel = if (count == 1) "en 1 lugar" else "en $count lugares",
                    compound = exercise.compound,
                    loadType = exercise.loadType,
                )
            }

        _state.value = EjerciciosUiState(
            loading = false,
            query = query,
            muscles = data.muscles
                .sortedBy { it.position }
                .map { EjercicioMuscleUi(it.id, it.name, it.id == filterMuscleId) },
            rows = rows,
            allCount = data.exercises.size,
            filterMuscleId = filterMuscleId,
            editing = editing,
            newMuscleName = newMuscleName,
            muscleError = muscleError,
        )
    }

    private data class Data(
        val exercises: List<Exercise>,
        val muscles: List<Muscle>,
        val routines: List<Routine>,
    )

    companion object {
        fun roundTenth(value: Double): Double = kotlin.math.round(value * 10.0) / 10.0
    }
}
