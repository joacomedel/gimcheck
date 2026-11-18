package app.gimcheck.core

/**
 * Invariantes del editor de rutinas, con errores **tipados** (no strings sueltos) para que
 * la UI de Compose pueda decidir qué mostrar y dónde sin parsear texto.
 *
 * Qué sale de la web (1:1) y qué es nuevo:
 *
 *  - `sets` 1–20: la web lo hace en el HTML con `Math.max(1,Math.min(20, …))` al tocar
 *    los botones `+`/`-` (línea 713) y con `min="0" max="1"` en el factor de secundario
 *    (línea 513) más el clamp de `secF` (línea 763). Acá se valida en el dominio, así
 *    que también cubre datos importados o migrados que no pasaron por el formulario.
 *  - Último ítem del día con superserie: la web deshabilita el botón (`last? disabled`) y
 *    por eso no puede guardarlo. Persistido a mano sí puede existir y produce un grupo
 *    de 1: el cálculo no explota, pero la marca es basura. Se reporta.
 *  - `restOverrideSeconds >= 0`: la web no tiene override por ítem (es de la app), y el
 *    descanso del ejercicio se clampea con `Math.max(0, …)` (línea 764). El override
 *    hereda la misma cota.
 *  - Dos secundarios con el mismo músculo: **no existe en la web**. Su `<select>` permite
 *    repetir y `volume` sumaría los dos factores mientras `musclesOf` deduplicaría por
 *    `indexOf`. Es un invariante nuevo, pedido en el ticket, no una diferencia de paridad.
 */

/** Rango de series permitido por serie de un ítem (`sets`). */
val ITEM_SETS_RANGE: IntRange = 1..20

/** Rango del factor de un músculo secundario. */
val SECONDARY_FACTOR_RANGE: ClosedFloatingPointRange<Double> = 0.0..1.0

/**
 * Código estable de cada error. La UI mapea código → texto; los tests comparan tipos, no
 * strings, para que un cambio de redacción no rompa la verificación.
 */
enum class EditorErrorCode {
    SUPERSET_ON_LAST_ITEM,
    SETS_OUT_OF_RANGE,
    SECONDARY_FACTOR_OUT_OF_RANGE,
    DUPLICATE_SECONDARY,
    NEGATIVE_REST_OVERRIDE,
}

/** Error de validación del editor. `subjectId` es el ítem o el ejercicio que lo origina. */
sealed interface EditorError {
    val code: EditorErrorCode
    val subjectId: String
}

/** El último ítem del día tiene `supersetNext = true` y no hay siguiente con qué encadenarlo. */
data class SupersetOnLastItem(
    val itemId: String,
) : EditorError {
    override val code: EditorErrorCode get() = EditorErrorCode.SUPERSET_ON_LAST_ITEM
    override val subjectId: String get() = itemId
}

/** `sets` fuera de [ITEM_SETS_RANGE]. */
data class SetsOutOfRange(
    val itemId: String,
    val sets: Int,
) : EditorError {
    override val code: EditorErrorCode get() = EditorErrorCode.SETS_OUT_OF_RANGE
    override val subjectId: String get() = itemId
    val allowed: IntRange get() = ITEM_SETS_RANGE
}

/** Factor de un secundario fuera de [SECONDARY_FACTOR_RANGE]. */
data class SecondaryFactorOutOfRange(
    val exerciseId: String,
    val muscleId: String,
    val factor: Double,
) : EditorError {
    override val code: EditorErrorCode get() = EditorErrorCode.SECONDARY_FACTOR_OUT_OF_RANGE
    override val subjectId: String get() = exerciseId
    val allowed: ClosedFloatingPointRange<Double> get() = SECONDARY_FACTOR_RANGE
}

/** El mismo músculo aparece más de una vez como secundario del ejercicio. */
data class DuplicateSecondary(
    val exerciseId: String,
    val muscleId: String,
    val count: Int,
) : EditorError {
    override val code: EditorErrorCode get() = EditorErrorCode.DUPLICATE_SECONDARY
    override val subjectId: String get() = exerciseId
}

/** Override de descanso negativo. `null` (sin override) es válido. */
data class NegativeRestOverride(
    val itemId: String,
    val seconds: Int,
) : EditorError {
    override val code: EditorErrorCode get() = EditorErrorCode.NEGATIVE_REST_OVERRIDE
    override val subjectId: String get() = itemId
}

/**
 * Valida el catálogo de un ejercicio: factor de cada secundario en 0–1 y ningún músculo
 * secundario repetido. El principal no se valida acá (es un id del catálogo de músculos).
 */
fun validateExercise(exercise: Exercise): List<EditorError> {
    val errors = mutableListOf<EditorError>()
    val counts = LinkedHashMap<String, Int>()

    for (secondary in exercise.secondaries) {
        if (secondary.factor !in SECONDARY_FACTOR_RANGE) {
            errors += SecondaryFactorOutOfRange(exercise.id, secondary.muscleId, secondary.factor)
        }
        counts[secondary.muscleId] = (counts[secondary.muscleId] ?: 0) + 1
    }
    for ((muscleId, count) in counts) {
        if (count > 1) errors += DuplicateSecondary(exercise.id, muscleId, count)
    }
    return errors
}

/**
 * Valida un ítem del día: `sets` en 1–20, override de descanso no negativo y, si es el
 * último del día, que no tenga `supersetNext`.
 *
 * @param isLastInDay lo aporta [validateDay]; en un ítem suelto deja el valor por defecto.
 */
fun validateItem(item: DayItem, isLastInDay: Boolean = false): List<EditorError> {
    val errors = mutableListOf<EditorError>()
    if (item.sets !in ITEM_SETS_RANGE) errors += SetsOutOfRange(item.id, item.sets)
    val override = item.restOverrideSeconds
    if (override != null && override < 0) errors += NegativeRestOverride(item.id, override)
    if (isLastInDay && item.supersetNext) errors += SupersetOnLastItem(item.id)
    return errors
}

/**
 * Valida el día completo: reglas de cada ítem, la marca de superserie del último, y el
 * catálogo de cada ejercicio **una sola vez** aunque se repita en el día.
 *
 * El orden es estable: primero los ítems en orden de ejecución y al final los ejercicios
 * en orden de aparición. La lista vacía significa día válido.
 */
fun validateDay(day: RoutineDay): List<EditorError> {
    val errors = mutableListOf<EditorError>()
    val lastIndex = day.items.lastIndex

    day.items.forEachIndexed { index, item ->
        errors += validateItem(item, isLastInDay = index == lastIndex)
    }

    val seen = mutableSetOf<String>()
    for (item in day.items) {
        if (seen.add(item.exercise.id)) errors += validateExercise(item.exercise)
    }
    return errors
}
