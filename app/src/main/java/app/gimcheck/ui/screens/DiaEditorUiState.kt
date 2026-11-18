package app.gimcheck.ui.screens

import app.gimcheck.core.DayItem
import app.gimcheck.core.EditorErrorCode

/**
 * Estado del **editor del día** (GIM-17). Vive colgado de [RutinasUiState.editor]: `null`
 * significa que el día se muestra solo lectura, que es lo que dejó GIM-16.
 *
 * Todo lo que se ve acá sale de `:core` o del borrador en memoria del ViewModel:
 * [durationLabel] y [setsLabel] son `dayStats`, [warnings] son
 * `supersetConflicts`/`heavyPairs` y [problems] son los `EditorError` de `validateDay`
 * traducidos a texto **conservando el código tipado** para que la pantalla no parsee
 * strings ni decida nada.
 *
 * Nada se escribe hasta [RutinasViewModel.saveEdit]: editar mueve el borrador y el día
 * guardado sigue intacto hasta que el usuario elige guardar y la validación pasa.
 */
data class DiaEditorUiState(
    val dayId: String,
    val dayName: String,
    /** Duración estimada del borrador (`formatDuration(dayStats.totalSeconds)`). */
    val durationLabel: String,
    /** Series totales del borrador (`dayStats.sets`). */
    val setsLabel: String,
    /** `2 min de trabajo · 8 min de descanso` del borrador. */
    val workRestLabel: String,
    /** Ítems en orden de ejecución, con lo que necesita cada fila editable. */
    val items: List<DiaEditorItemUi> = emptyList(),
    /** Advertencias del bloque (músculo compartido, dos multiarticulares). */
    val warnings: List<String> = emptyList(),
    /** Errores tipados de validación; vacío = se puede guardar. */
    val problems: List<DiaEditorProblemUi> = emptyList(),
    /** Catálogo para "Agregar ejercicio" (el CRUD completo es GIM-18). */
    val catalog: List<DiaExerciseOptionUi> = emptyList(),
    /** Hay cambios sin guardar. */
    val dirty: Boolean = false,
    val saving: Boolean = false,
    /** Se intentó guardar con errores: la base no se tocó. */
    val rejected: Boolean = false,
    /** El selector de ejercicio está abierto. */
    val picking: Boolean = false,
) {
    /** Sólo se guarda si hay cambios y `validateDay` no encontró nada. */
    val canSave: Boolean get() = dirty && problems.isEmpty() && !saving
}

/**
 * Un ítem del borrador.
 *
 * @param canSuperset `false` en el último ítem del día: la UI deshabilita el toggle y el
 *   ViewModel ignora el toque (la invariante también está en el DAO).
 * @param effectiveSets `effectiveSets` de `:core`: las series más lo que aporta el drop set.
 * @param problemCodes códigos tipados que apuntan a este ítem (para marcar la fila).
 */
data class DiaEditorItemUi(
    val id: String,
    val position: Int,
    val exerciseId: String,
    val name: String,
    val muscle: String,
    val sets: Int,
    val dropSet: Boolean,
    val supersetNext: Boolean,
    val canSuperset: Boolean,
    val effectiveSets: Double,
    val detail: String,
    val problemCodes: Set<EditorErrorCode> = emptySet(),
    val problemTexts: List<String> = emptyList(),
)

/**
 * Un error de validación ya traducido, con el código y el sujeto intactos.
 *
 * @param subjectId id del ítem o del ejercicio que lo origina (`EditorError.subjectId`).
 */
data class DiaEditorProblemUi(
    val code: EditorErrorCode,
    val subjectId: String,
    val text: String,
)

/** Ejercicio del catálogo en el selector de "Agregar ejercicio". */
data class DiaExerciseOptionUi(
    val id: String,
    val name: String,
    val muscle: String,
)

/**
 * Invariante del orden del día: **el último ítem no puede encadenar superserie** con
 * nada (§2). Cada operación del editor pasa el resultado por acá, así mover, quitar o
 * agregar ítems deja el día consistente sin que la pantalla sepa que la regla existe.
 *
 * Devuelve la misma lista (misma instancia) si no hay nada que corregir.
 */
internal fun withSupersetInvariant(items: List<DayItem>): List<DayItem> {
    val last = items.lastOrNull() ?: return items
    if (!last.supersetNext) return items
    return items.dropLast(1) + last.copy(supersetNext = false)
}
