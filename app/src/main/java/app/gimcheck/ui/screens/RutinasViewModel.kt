package app.gimcheck.ui.screens

import app.gimcheck.AppContainer
import app.gimcheck.core.DayItem
import app.gimcheck.core.DuplicateSecondary
import app.gimcheck.core.EditorError
import app.gimcheck.core.Exercise
import app.gimcheck.core.Muscle
import app.gimcheck.core.NegativeRestOverride
import app.gimcheck.core.Routine
import app.gimcheck.core.RoutineDay
import app.gimcheck.core.SecondaryFactorOutOfRange
import app.gimcheck.core.SetsOutOfRange
import app.gimcheck.core.Settings
import app.gimcheck.core.SupersetOnLastItem
import app.gimcheck.core.dayStats
import app.gimcheck.core.effectiveSets
import app.gimcheck.core.exportPlan
import app.gimcheck.core.formatDuration
import app.gimcheck.core.formatNumber
import app.gimcheck.core.heavyPairs
import app.gimcheck.core.supersetConflicts
import app.gimcheck.core.validateDay
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.RoutineRepository
import app.gimcheck.data.repository.SettingsRepository
import app.gimcheck.data.settings.AppSettings
import app.gimcheck.data.transfer.RoutineImportOutcome
import app.gimcheck.data.transfer.RoutineImportPreview
import app.gimcheck.data.transfer.RoutineTransfer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

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
 * El detalle se muestra solo lectura hasta que la pantalla entra en modo edición
 * (GIM-17): ahí el borrador vive acá, se valida con [validateDay] y sólo se escribe en
 * [saveEdit].
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
    /**
     * Editor del día (GIM-17); `null` = el detalle se muestra solo lectura. Mientras no
     * sea `null`, el borrador manda: las métricas, las advertencias y los errores son
     * del borrador y no del día guardado.
     */
    val editor: DiaEditorUiState? = null,
    /** Aviso o confirmación de export/import de rutinas; `null` = nada pendiente. */
    val transfer: RutinasTransferUi? = null,
)

/** Estado visible del export/import de rutinas (GIM-53). */
sealed interface RutinasTransferUi {

    /** El archivo se leyó y se resolvió; falta confirmar la escritura. */
    data class Confirm(
        val fileName: String,
        val routines: Int,
        val days: Int,
        val exercisesCreated: Int,
        val exercisesReused: Int,
    ) : RutinasTransferUi

    data class Imported(
        val routines: Int,
        val days: Int,
        val exercisesCreated: Int,
        val exercisesReused: Int,
    ) : RutinasTransferUi

    data class Error(val message: String) : RutinasTransferUi

    data object Exported : RutinasTransferUi

    data object ExportFailed : RutinasTransferUi

    data object ReadFailed : RutinasTransferUi
}

/** Lo que el selector de archivos necesita para guardar el pack. */
data class RoutineExportReady(val name: String, val json: String)

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
    private val transfer: RoutineTransfer,
    private val scope: CoroutineScope,
    private val newItemId: () -> String = { UUID.randomUUID().toString() },
) {

    constructor(container: AppContainer, scope: CoroutineScope) : this(
        routines = container.routines,
        exercises = container.exercises,
        settings = container.settings,
        transfer = container.routineTransfer,
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

    /**
     * Borrador del editor (GIM-17). `null` = el día se mira, no se edita. Sólo se escribe
     * en la base en [saveEdit], y sólo si `validateDay` no encuentra nada.
     */
    private var draft: List<DayItem>? = null

    /** Archivo ya resuelto que espera confirmación para escribir. */
    private var pendingImport: RoutineImportPreview.Ready? = null
    private var transferState: RutinasTransferUi? = null

    /** El día que resuelve [publish] en la última emisión; el editor trabaja sobre éste. */
    private var selectedDay: RoutineDay? = null
    private var draftDirty: Boolean = false
    private var saving: Boolean = false
    private var rejected: Boolean = false
    private var picking: Boolean = false

    /** Empieza a observar repositorios. Idempotente: la pantalla la llama una vez. */
    fun start() {
        if (observation != null) return
        observation = scope.launch {
            combine(
                routines.observeRoutines(),
                exercises.observeMuscles(),
                exercises.observeExercises(),
                settings.settings,
                settings.activeRoutineId,
            ) { plans, muscles, catalog, prefs, activeId ->
                RutinasData(
                    plans = plans,
                    muscles = muscles,
                    exercises = catalog,
                    preferences = prefs,
                    activeId = activeId,
                    muscleNames = muscles.associate { it.id to it.name },
                )
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
        clearDraft()
        lastData?.let(::publish)
    }

    /** Toca un día: el detalle de abajo pasa a ese día. */
    fun selectDay(dayId: String) {
        if (dayId == selectedDayId) return
        selectedDayId = dayId
        clearDraft()
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
            val name = "Día ${(plan?.days?.size ?: 0) + 1}"
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
            if (selectedDay?.id == dayId) clearDraft()
            lastData?.let(::publish)
        }
    }

    // --- editor del día (GIM-17) -------------------------------------------------

    /**
     * Entra en modo edición con una copia del día guardado. Nada se escribe hasta
     * [saveEdit]: todo lo de acá para abajo mueve el borrador.
     */
    fun startEdit() {
        val data = lastData ?: return
        val day = selectedDay ?: return
        draft = withSupersetInvariant(day.items)
        draftDirty = false
        saving = false
        rejected = false
        picking = false
        publish(data)
    }

    /** Sale del editor descartando el borrador; el día guardado queda como estaba. */
    fun cancelEdit() {
        if (draft == null) return
        clearDraft()
        lastData?.let(::publish)
    }

    /** Abre y cierra el selector de ejercicio del catálogo. */
    fun openPicker() {
        if (draft == null || picking) return
        picking = true
        lastData?.let(::publish)
    }

    /**
     * "Agregar ejercicio" desde el día en lectura (GIM-47): entra al editor y abre el
     * selector en el mismo toque. Antes esa acción navegaba a la pantalla Ejercicios,
     * que todavía es el placeholder de GIM-18, y el usuario aterrizaba en una lista vacía.
     */
    fun startEditWithPicker() {
        if (draft == null) startEdit()
        openPicker()
    }

    fun closePicker() {
        if (!picking) return
        picking = false
        lastData?.let(::publish)
    }

    /**
     * Agrega el ejercicio al final del día con [DEFAULT_SETS] series (3, como la web) y
     * cierra el selector. El ítem nuevo nunca encadena superserie: queda último.
     */
    fun addExercise(exerciseId: String) {
        val data = lastData ?: return
        val current = draft ?: return
        val exercise = data.exercises.firstOrNull { it.id == exerciseId } ?: return
        val item = DayItem(id = newItemId(), exercise = exercise, sets = DEFAULT_SETS)
        applyDraft(current + item)
        picking = false
        publish(data)
    }

    /** Sube o baja el ítem una posición; el nuevo último pierde la marca de superserie. */
    fun moveItem(itemId: String, offset: Int) {
        val data = lastData ?: return
        val current = draft ?: return
        val from = current.indexOfFirst { it.id == itemId }
        if (from < 0) return
        val to = (from + offset).coerceIn(0, current.lastIndex)
        if (to == from) return
        val moved = current.toMutableList().apply {
            add(to, removeAt(from))
        }
        applyDraft(moved)
        publish(data)
    }

    /**
     * Quita el ítem del borrador. Si era el último, el anterior se destilda solo: la
     * invariante de §2 viaja con cada operación ([withSupersetInvariant]).
     */
    fun removeItem(itemId: String) {
        val data = lastData ?: return
        val current = draft ?: return
        applyDraft(current.filterNot { it.id == itemId })
        publish(data)
    }

    /**
     * Cambia las series del ítem. **No clampea**: un valor fuera de 1..20 queda en el
     * borrador como error tipado (`SETS_OUT_OF_RANGE`) y bloquea el guardado. El stepper
     * de la pantalla ya topa en 1..20 (`ITEM_SETS_RANGE`), así que por UI no se llega.
     */
    fun setSets(itemId: String, sets: Int) {
        val data = lastData ?: return
        val current = draft ?: return
        applyDraft(current.map { if (it.id == itemId) it.copy(sets = sets) else it })
        publish(data)
    }

    /** Prende o apaga el drop set del ítem (suma trabajo y series efectivas al final). */
    fun toggleDropSet(itemId: String) {
        val data = lastData ?: return
        val current = draft ?: return
        applyDraft(current.map { if (it.id == itemId) it.copy(dropSet = !it.dropSet) else it })
        publish(data)
    }

    /** Prende o apaga "superserie con el siguiente". En el último ítem no hace nada. */
    fun toggleSupersetNext(itemId: String) {
        val data = lastData ?: return
        val current = draft ?: return
        val index = current.indexOfFirst { it.id == itemId }
        if (index < 0 || index == current.lastIndex) return
        applyDraft(
            current.mapIndexed { i, item ->
                if (i == index) item.copy(supersetNext = !item.supersetNext) else item
            },
        )
        publish(data)
    }

    /**
     * Guarda el borrador: primero `validateDay` de `:core`, después
     * [RoutineRepository.saveDayItems] (que corre en `Dispatchers.IO` y aplica las
     * invariantes del DAO). Con errores **no se escribe nada** y el estado los muestra.
     */
    fun saveEdit() {
        val data = lastData ?: return
        val day = selectedDay ?: return
        val items = draft ?: return
        if (saving) return

        val normalized = withSupersetInvariant(items)
        val errors = validateDay(RoutineDay(day.id, day.name, normalized))
        draft = normalized
        if (errors.isNotEmpty()) {
            rejected = true
            publish(data)
            return
        }

        rejected = false
        saving = true
        publish(data)
        scope.launch {
            routines.saveDayItems(day.id, normalized)
            draft = null
            draftDirty = false
            saving = false
            picking = false
            lastData?.let(::publish)
        }
    }

    /** El borrador como día de `:core`. Es lo que usan los tests y el guardado. */
    internal fun draftDay(): RoutineDay? {
        val day = selectedDay ?: return null
        return RoutineDay(day.id, day.name, draft ?: day.items)
    }

    private fun applyDraft(items: List<DayItem>) {
        val normalized = withSupersetInvariant(items)
        if (normalized == draft) return
        draft = normalized
        draftDirty = true
        rejected = false
    }

    private fun clearDraft() {
        draft = null
        draftDirty = false
        saving = false
        rejected = false
        picking = false
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

    // --- export/import de rutinas (GIM-53) ---------------------------------------

    /** Arma el pack con todas las rutinas y lo entrega listo para guardar. */
    fun exportAll(onReady: (RoutineExportReady) -> Unit) {
        scope.launch {
            val json = runCatching { transfer.export() }.getOrNull()
            if (json == null) {
                setTransfer(RutinasTransferUi.ExportFailed)
            } else {
                onReady(RoutineExportReady(name = transfer.suggestedName(), json = json))
            }
        }
    }

    /** El selector de archivos confirmó que escribió el pack. */
    fun exportWritten() {
        setTransfer(RutinasTransferUi.Exported)
    }

    /** Se canceló o falló la escritura del pack. */
    fun exportFailed() {
        setTransfer(RutinasTransferUi.ExportFailed)
    }

    /** No se pudo leer el archivo elegido (permiso, proveedor roto). */
    fun readFailed() {
        setTransfer(RutinasTransferUi.ReadFailed)
    }

    /**
     * Lee el archivo en segundo plano y lo resuelve contra el catálogo. Con errores no
     * se escribe nada; con un preview válido la pantalla pide confirmación.
     */
    fun previewImport(fileName: String, text: String) {
        scope.launch {
            when (val preview = transfer.preview(text)) {
                is RoutineImportPreview.Invalid -> {
                    setTransfer(RutinasTransferUi.Error(preview.message))
                }

                is RoutineImportPreview.Ready -> {
                    pendingImport = preview
                    setTransfer(
                        RutinasTransferUi.Confirm(
                            fileName = fileName,
                            routines = preview.sourceRoutines,
                            days = preview.routines.sumOf { it.days.size },
                            exercisesCreated = preview.newExercises.size,
                            exercisesReused = preview.reusedExercises,
                        ),
                    )
                }
            }
        }
    }

    /** Escribe el preview confirmado y selecciona la primera rutina importada. */
    fun confirmImport() {
        val preview = pendingImport ?: return
        scope.launch {
            when (val outcome = transfer.import(preview)) {
                is RoutineImportOutcome.Imported -> {
                    pendingImport = null
                    pendingPlanId = outcome.firstRoutineId
                    pendingDayId = null
                    selectedPlanId = outcome.firstRoutineId
                    selectedDayId = null
                    transferState = RutinasTransferUi.Imported(
                        routines = outcome.routines,
                        days = outcome.days,
                        exercisesCreated = outcome.exercisesCreated,
                        exercisesReused = outcome.exercisesReused,
                    )
                    republish()
                }

                is RoutineImportOutcome.Failed -> {
                    setTransfer(RutinasTransferUi.Error(outcome.message))
                }
            }
        }
    }

    /** Cierra el aviso o cancela la confirmación pendiente. */
    fun dismissTransfer() {
        pendingImport = null
        setTransfer(null)
    }

    private fun setTransfer(value: RutinasTransferUi?) {
        transferState = value
        republish()
    }

    private fun republish() {
        val data = lastData
        if (data != null) {
            publish(data)
        } else {
            _state.value = _state.value.copy(transfer = transferState)
        }
    }

    // --- proyección --------------------------------------------------------------

    private fun publish(data: RutinasData) {
        val core = data.preferences.toCoreSettings()

        if (data.plans.isEmpty()) {
            selectedPlanId = null
            selectedDayId = null
            pendingPlanId = null
            pendingDayId = null
            selectedDay = null
            clearDraft()
            _state.value = RutinasUiState(
                loading = false,
                hasPlans = false,
                canDeletePlan = false,
                transfer = transferState,
            )
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
        selectedDay = day
        if (draft != null && day == null) clearDraft()

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
            editor = if (day != null && draft != null) {
                editorUi(day, requireNotNull(draft), core, data)
            } else {
                null
            },
            transfer = transferState,
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

    // --- proyección del editor (GIM-17) ------------------------------------------

    /**
     * Arma el estado del editor desde el borrador. Todo lo numérico sale de `:core`
     * ([dayStats], [effectiveSets], [validateDay], [supersetConflicts], [heavyPairs]) y
     * acá sólo se traduce a texto: la pantalla no calcula ni interpreta códigos.
     */
    private fun editorUi(
        day: RoutineDay,
        items: List<DayItem>,
        core: Settings,
        data: RutinasData,
    ): DiaEditorUiState {
        val draftDay = RoutineDay(day.id, day.name, items)
        val stats = dayStats(items, core)
        val problems = validateDay(draftDay).map { error -> error.toProblem(data) }
        val muscleName = { id: String -> data.muscleNames[id] ?: id }
        val lastIndex = items.lastIndex

        return DiaEditorUiState(
            dayId = day.id,
            dayName = day.name,
            durationLabel = formatDuration(stats.totalSeconds),
            setsLabel = stats.sets.toString(),
            workRestLabel = "${formatDuration(stats.workSeconds)} de trabajo · " +
                "${formatDuration(stats.restSeconds)} de descanso",
            items = items.mapIndexed { index, item ->
                val own = problems.filter {
                    it.subjectId == item.id || it.subjectId == item.exercise.id
                }
                val effective = effectiveSets(item, core)
                DiaEditorItemUi(
                    id = item.id,
                    position = index + 1,
                    exerciseId = item.exercise.id,
                    name = item.exercise.name,
                    muscle = muscleName(item.exercise.primaryMuscleId),
                    sets = item.sets,
                    dropSet = item.dropSet,
                    supersetNext = item.supersetNext,
                    canSuperset = index != lastIndex,
                    effectiveSets = effective,
                    detail = "${item.sets} series · ${formatNumber(effective)} efectivas · " +
                        "descanso ${item.restOverrideSeconds ?: item.exercise.restSeconds} s",
                    problemCodes = own.map { it.code }.toSet(),
                    problemTexts = own.map { it.text },
                )
            },
            warnings = warnings(draftDay, data.muscleNames),
            problems = problems,
            catalog = data.exercises
                .filterNot { it.archived }
                .sortedWith(compareBy({ it.position }, { it.name }))
                .map { DiaExerciseOptionUi(it.id, it.name, muscleName(it.primaryMuscleId)) },
            dirty = draftDirty,
            saving = saving,
            rejected = rejected,
            picking = picking,
        )
    }

    /**
     * Traduce un [EditorError] de `:core` a texto, conservando el código y el sujeto.
     * El detalle que necesita nombres (músculo, ejercicio) se resuelve acá, que es donde
     * están los mapas del árbol.
     */
    private fun EditorError.toProblem(data: RutinasData): DiaEditorProblemUi {
        val text = when (this) {
            is SupersetOnLastItem ->
                "El último ítem del día no puede encadenar superserie."

            is SetsOutOfRange ->
                "Series fuera de ${allowed.first}–${allowed.last}: $sets."

            is NegativeRestOverride ->
                "Descanso inválido (${seconds} s): no puede ser negativo."

            is DuplicateSecondary ->
                "El músculo ${data.muscleNames[muscleId] ?: muscleId} está repetido " +
                    "como secundario."

            is SecondaryFactorOutOfRange ->
                "Factor del músculo ${data.muscleNames[muscleId] ?: muscleId} fuera de " +
                    "0–1: ${formatNumber(factor)}."
        }
        return DiaEditorProblemUi(code = code, subjectId = subjectId, text = text)
    }

    private data class RutinasData(
        val plans: List<Routine>,
        val muscles: List<Muscle>,
        val exercises: List<Exercise>,
        val preferences: AppSettings,
        val activeId: String?,
        val muscleNames: Map<String, String> = emptyMap(),
    )

    companion object {
        /** Sufijos en `muted` del diseño (`docs/diseno.md` §8 #4). */
        const val DROP_SET: String = "Drop set"
        const val SUPERSET: String = "Superserie"

        /** Series con las que entra un ítem nuevo (la web: 3). */
        const val DEFAULT_SETS: Int = 3
    }
}
