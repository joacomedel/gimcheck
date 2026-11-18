package app.gimcheck.ui.screens

import app.gimcheck.AppContainer
import app.gimcheck.core.Muscle
import app.gimcheck.core.Routine
import app.gimcheck.core.RoutineDay
import app.gimcheck.core.Settings
import app.gimcheck.core.dayStats
import app.gimcheck.core.exportPlan
import app.gimcheck.core.formatDuration
import app.gimcheck.core.heavyPairs
import app.gimcheck.core.supersetConflicts
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.RoutineRepository
import app.gimcheck.data.repository.SettingsRepository
import app.gimcheck.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Estado de **Rutinas** (§6 fila 4, GIM-16): planes, días y el detalle de un día.
 *
 * Dos niveles de selección, los dos en memoria:
 *
 *  - **plan**: el activo (persistido en `app_setting`, [SettingsRepository.activeRoutineId])
 *    es el que abre la pantalla; tocar otro plan lo selecciona sin cambiar el activo.
 *  - **día**: el primero del plan seleccionado. `routine.active_day` no se persiste (§2):
 *    el detalle muestra el día elegido y no escribe nada.
 *
 * **Cero cálculo en la pantalla**: duración y series salen de [dayStats], los totales del
 * plan de sumar los de sus días, y las advertencias de [supersetConflicts]/[heavyPairs].
 * El detalle es **solo lectura**: el editor del día es GIM-17.
 */
data class RutinasUiState(
    val loading: Boolean = true,
    /** `false` = no hay ningún plan: la pantalla muestra el estado vacío de §9. */
    val hasPlans: Boolean = false,
    /** Todos los planes, con el activo marcado y sus totales. */
    val plans: List<RutinaPlanUi> = emptyList(),
    val selectedPlanId: String? = null,
    val selectedPlanName: String = "",
    /** Días del plan seleccionado, para los chips. */
    val days: List<RutinaDayChipUi> = emptyList(),
    val selectedDayId: String? = null,
    /** Detalle del día elegido; `null` si el plan no tiene días o no hay plan. */
    val day: RutinaDayDetailUi? = null,
    /** `false` si es el único plan: "Eliminar" no está disponible (§1.1). */
    val canDeletePlan: Boolean = false,
)

/**
 * Un plan en la lista.
 *
 * @param setsLabel series de todos sus días (`dayStats.sets`, ya sumadas).
 * @param durationLabel `1 h 32 min` de la semana del plan (`formatDuration`).
 */
data class RutinaPlanUi(
    val id: String,
    val name: String,
    val active: Boolean,
    val daysCount: Int,
    val setsLabel: String,
    val durationLabel: String,
)

/** Chip de día del plan seleccionado. */
data class RutinaDayChipUi(
    val id: String,
    val name: String,
    val selected: Boolean,
)

/**
 * Detalle de un día, solo lectura.
 *
 * @param warnings en `warn`: superseries que comparten músculo y pares de
 *   multiarticulares. Vacío = día sin advertencias.
 */
data class RutinaDayDetailUi(
    val id: String,
    val name: String,
    val durationLabel: String,
    val setsLabel: String,
    val items: List<RutinaItemUi> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/**
 * Ítem del día en orden de ejecución.
 *
 * @param detail `4 series · descanso 90 s`.
 * @param tags `Drop set` / `Superserie`, en `muted` como sufijo del diseño.
 */
data class RutinaItemUi(
    val id: String,
    val position: Int,
    val name: String,
    val muscle: String,
    val detail: String,
    val tags: List<String> = emptyList(),
)

/**
 * ViewModel de **Rutinas** con `StateFlow` (sin Hilt: recibe el [AppContainer]).
 *
 * Las escrituras van al repositorio y la pantalla no espera: el árbol completo viaja por
 * `Flow`, así que crear/duplicar/renombrar/borrar se ven al instante, sin recargar.
 */
class RutinasViewModel internal constructor(
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

    private val _state = MutableStateFlow(RutinasUiState())

    /** Lo que compone la pantalla. */
    val state: StateFlow<RutinasUiState> = _state.asStateFlow()

    private var selectedPlanId: String? = null
    private var selectedDayId: String? = null

    /**
     * Selección pedida que todavía no está en el árbol emitido (plan o día recién
     * creado/duplicado). El `Flow` de Room puede emitir antes de que el `launch` vuelva,
     * así que la selección no puede depender del orden: se recuerda acá hasta que el
     * árbol la contenga.
     */
    private var pendingPlanId: String? = null
    private var pendingDayId: String? = null

    private var observation: Job? = null
    private var lastData: RutinasData? = null

    /** Empieza a observar repositorios. Idempotente: la pantalla la llama una vez. */
    fun start() {
        if (observation != null) return
        observation = scope.launch {
            combine(
                routines.observeRoutines(),
                exercises.observeMuscles(),
                settings.settings,
                settings.activeRoutineId,
            ) { plans, muscles, prefs, activeId ->
                RutinasData(plans, muscles, prefs, activeId, muscles.associate { it.id to it.name })
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

    // --- selección ---------------------------------------------------------------

    /** Toca otro plan: cambia el seleccionado (no el activo) y vuelve al primer día. */
    fun selectPlan(planId: String) {
        if (planId == selectedPlanId) return
        selectedPlanId = planId
        selectedDayId = null
        lastData?.let(::publish)
    }

    /** Toca un día: el detalle de abajo pasa a ese día. */
    fun selectDay(dayId: String) {
        if (dayId == selectedDayId) return
        selectedDayId = dayId
        lastData?.let(::publish)
    }

    // --- acciones de plan (§1.1) -------------------------------------------------

    /** Crea un plan vacío, lo marca activo y lo selecciona. */
    fun createPlan() {
        scope.launch {
            val name = nextPlanName()
            val id = routines.createRoutine(name)
            pendingPlanId = id
            pendingDayId = null
            settings.setActiveRoutine(id)
            lastData?.let(::publish)
        }
    }

    /** Duplica el plan con días e ítems (ids nuevos) y selecciona la copia. */
    fun duplicatePlan(planId: String) {
        scope.launch {
            val source = lastData?.plans?.firstOrNull { it.id == planId } ?: return@launch
            val id = routines.duplicateRoutine(planId, copyName(source.name)) ?: return@launch
            pendingPlanId = id
            pendingDayId = null
            lastData?.let(::publish)
        }
    }

    /** Renombra el plan. Un nombre vacío se ignora: un plan sin nombre no es editable. */
    fun renamePlan(planId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        scope.launch { routines.renameRoutine(planId, trimmed) }
    }

    /**
     * Marca el plan como activo (lo que la web llama `curPlan`).
     *
     * Si el que se borra era el activo, la base queda apuntando a un id inexistente y el
     * [publish] cae al primero; acá se reescribe el puntero para no dejar basura.
     */
    fun setActivePlan(planId: String) {
        scope.launch { settings.setActiveRoutine(planId) }
    }

    /** Borra un plan. Nunca el último: el repositorio lo rechaza y la UI no lo ofrece. */
    fun deletePlan(planId: String) {
        scope.launch {
            if (!routines.deleteRoutine(planId)) return@launch
            if (lastData?.activeId == planId) {
                val next = lastData?.plans?.firstOrNull { it.id != planId }?.id
                settings.setActiveRoutine(next)
            }
            if (selectedPlanId == planId) selectedPlanId = null
            if (pendingPlanId == planId) pendingPlanId = null
            lastData?.let(::publish)
        }
    }

    // --- acciones de día (§1.1) --------------------------------------------------

    /** Agrega un día vacío al final del plan seleccionado y lo abre. */
    fun addDay() {
        val planId = selectedPlanId ?: return
        scope.launch {
            val plan = lastData?.plans?.firstOrNull { it.id == planId }
            val name = "Día ${(plan?.daysCount ?: 0) + 1}"
            val id = routines.addDay(planId, name)
            pendingDayId = id
            lastData?.let(::publish)
        }
    }

    /** Duplica el día con ítems nuevos y lo abre. */
    fun duplicateDay(dayId: String) {
        scope.launch {
            val id = routines.duplicateDay(dayId) ?: return@launch
            pendingDayId = id
            lastData?.let(::publish)
        }
    }

    fun renameDay(dayId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        scope.launch { routines.renameDay(dayId, trimmed) }
    }

    /** Borra el día (con sus ítems) y selecciona el primero que quede. */
    fun deleteDay(dayId: String) {
        scope.launch {
            routines.deleteDay(dayId)
            if (selectedDayId == dayId) selectedDayId = null
            if (pendingDayId == dayId) pendingDayId = null
            lastData?.let(::publish)
        }
    }

    // --- exportar (§5) -----------------------------------------------------------

    /**
     * JSON del plan seleccionado con el formato de la web (`exportPlan` de `:core`, que
     * es el mismo `buildPlanJson()` del HTML): `app:"rutinas-gimnasio"`, `tipo:"plan"`,
     * `version:1`.
     *
     * @return el JSON, o `null` si no hay plan seleccionado.
     */
    internal fun exportJson(planId: String? = selectedPlanId): String? {
        val data = lastData ?: return null
        val id = planId ?: return null
        val plan = data.plans.firstOrNull { it.id == id } ?: return null
        return exportPlan(plan, data.muscles, data.preferences.toCoreSettings())
    }

    /** Igual que [exportJson] pero entrega el resultado al borde para compartirlo. */
    fun exportPlanJson(planId: String, onReady: (String?) -> Unit) {
        onReady(exportJson(planId))
    }

    // --- proyección --------------------------------------------------------------

    private fun publish(data: RutinasData) {
        val core = data.preferences.toCoreSettings()

        if (data.plans.isEmpty()) {
            selectedPlanId = null
            selectedDayId = null
            pendingPlanId = null
            pendingDayId = null
            _state.value = RutinasUiState(loading = false, hasPlans = false, canDeletePlan = false)
            return
        }

        val activeId = data.activeId?.takeIf { id -> data.plans.any { it.id == id } }
            ?: data.plans.first().id
        val plan = data.plans.firstOrNull { it.id == pendingPlanId }
            ?: data.plans.firstOrNull { it.id == selectedPlanId }
            ?: data.plans.first { it.id == activeId }
        if (plan.id == pendingPlanId) pendingPlanId = null
        selectedPlanId = plan.id

        val day = plan.days.firstOrNull { it.id == pendingDayId }
            ?: plan.days.firstOrNull { it.id == selectedDayId }
            ?: plan.days.firstOrNull()
        if (day?.id == pendingDayId) pendingDayId = null
        selectedDayId = day?.id

        _state.value = RutinasUiState(
            loading = false,
            hasPlans = true,
            plans = data.plans.map { routine ->
                val stats = routine.days.map { dayStats(it.items, core) }
                RutinaPlanUi(
                    id = routine.id,
                    name = routine.name,
                    active = routine.id == activeId,
                    daysCount = routine.days.size,
                    setsLabel = stats.sumOf { it.sets }.toString(),
                    durationLabel = formatDuration(stats.sumOf { it.totalSeconds }),
                )
            },
            selectedPlanId = plan.id,
            selectedPlanName = plan.name,
            days = plan.days.map { RutinaDayChipUi(it.id, it.name, it.id == day?.id) },
            selectedDayId = day?.id,
            day = day?.toUi(core, data.muscleNames),
            canDeletePlan = data.plans.size > 1,
        )
    }

    private fun RoutineDay.toUi(core: Settings, muscleNames: Map<String, String>): RutinaDayDetailUi {
        val stats = dayStats(items, core)
        return RutinaDayDetailUi(
            id = id,
            name = name,
            durationLabel = formatDuration(stats.totalSeconds),
            setsLabel = stats.sets.toString(),
            items = items.mapIndexed { index, item ->
                RutinaItemUi(
                    id = item.id,
                    position = index + 1,
                    name = item.exercise.name,
                    muscle = muscleNames[item.exercise.primaryMuscleId] ?: item.exercise.primaryMuscleId,
                    detail = "${item.sets} series · descanso ${item.restOverrideSeconds ?: item.exercise.restSeconds} s",
                    tags = buildList {
                        if (item.dropSet) add(DROP_SET)
                        if (item.supersetNext) add(SUPERSET)
                    },
                )
            },
            warnings = warnings(this, muscleNames),
        )
    }

    /**
     * Las dos advertencias del editor de la web (§1.1), tal cual las calcula `:core`.
     * El texto se arma acá (no en el composable) porque necesita los nombres de músculo
     * y la pantalla sólo dibuja.
     */
    private fun warnings(day: RoutineDay, muscleNames: Map<String, String>): List<String> {
        val conflicts = supersetConflicts(day).map { conflict ->
            val shared = conflict.sharedMuscleIds.joinToString(", ") { muscleNames[it] ?: it }
            "Superserie: ${conflict.a.exercise.name} y ${conflict.b.exercise.name} comparten $shared."
        }
        val heavy = heavyPairs(day).map { pair ->
            "Superserie muy pesada: ${pair.a.exercise.name} y ${pair.b.exercise.name} son multiarticulares."
        }
        return conflicts + heavy
    }

    private fun nextPlanName(): String =
        "Rutina ${(lastData?.plans?.size ?: 0) + 1}"

    private fun copyName(name: String): String = "$name (copia)"

    private data class RutinasData(
        val plans: List<Routine>,
        val muscles: List<Muscle>,
        val preferences: AppSettings,
        val activeId: String?,
        val muscleNames: Map<String, String> = emptyMap(),
    )

    companion object {
        /** Sufijos en `muted` del diseño (`docs/diseno.md` §8 #4). */
        const val DROP_SET: String = "Drop set"
        const val SUPERSET: String = "Superserie"
    }
}
