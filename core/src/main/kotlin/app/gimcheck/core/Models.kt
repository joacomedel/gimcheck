package app.gimcheck.core

/**
 * Modelo de dominio de GimCheck. Kotlin puro: sin Android, sin Room, sin Compose.
 *
 * Los campos replican `docs/especificacion.md` §2 (tablas Room). Dos decisiones de
 * esta capa:
 *
 *  - Las referencias entre objetos son directas ([DayItem.exercise],
 *    [Secondary.muscleId]) en lugar de ids sueltos que obliguen a pasar un mapa a
 *    cada función de cálculo. La persistencia sigue guardando ids; el repositorio
 *    (GIM-10) resuelve el join al materializar el dominio.
 *  - `seconds_per_set` y `rest_seconds` viven en el ejercicio (como en la web:
 *    `ex.work` / `ex.rest`), no en el ítem. El ítem sólo puede pisar el descanso con
 *    [DayItem.restOverrideSeconds].
 */

/** Tipo de carga de un ejercicio. BODYWEIGHT = peso corporal + lastre opcional. */
enum class LoadType { EXTERNAL, BODYWEIGHT }

/** Músculo del catálogo editable (la web arranca con 15 y permite agregar más). */
data class Muscle(
    val id: String,
    val name: String,
    val position: Int = 0,
    val archived: Boolean = false,
)

/** Músculo secundario de un ejercicio con su factor de aporte (0–1). */
data class Secondary(
    val muscleId: String,
    val factor: Double,
)

/**
 * Ejercicio del catálogo.
 *
 * @param secondsPerSet segundos de trabajo por serie (`ex.work` en la web).
 * @param restSeconds segundos de descanso entre series (`ex.rest`).
 * @param compound multiarticular (`ex.multi` en la web); sólo se usa para las
 *   advertencias del editor, no para las métricas.
 */
data class Exercise(
    val id: String,
    val name: String,
    val primaryMuscleId: String,
    val secondaries: List<Secondary> = emptyList(),
    val secondsPerSet: Int,
    val restSeconds: Int,
    val compound: Boolean = false,
    val loadType: LoadType = LoadType.EXTERNAL,
    val position: Int = 0,
    val archived: Boolean = false,
) {
    /** Aporte al músculo pedido: 1 si es el principal, el factor si es secundario, 0 si no participa. */
    fun factorFor(muscleId: String): Double =
        if (muscleId == primaryMuscleId) 1.0
        else secondaries.firstOrNull { it.muscleId == muscleId }?.factor ?: 0.0
}

/**
 * Ítem de un día: un ejercicio con sus series y las marcas de superserie/drop set.
 *
 * @param sets 1–20 según el editor de la web.
 * @param dropSet cuando es true, la última ronda suma `settings.drops * settings.dropSec`
 *   de trabajo y `settings.drops * settings.dropVol` series efectivas.
 * @param supersetNext encadena este ítem con el siguiente: forman un grupo.
 * @param restOverrideSeconds pisa el descanso del ejercicio para este ítem.
 */
data class DayItem(
    val id: String,
    val exercise: Exercise,
    val sets: Int,
    val dropSet: Boolean = false,
    val supersetNext: Boolean = false,
    val restOverrideSeconds: Int? = null,
    val targetRepsMin: Int? = null,
    val targetRepsMax: Int? = null,
) {
    val exerciseId: String get() = exercise.id
}

/** Día de una rutina, con sus ítems en orden de ejecución. */
data class RoutineDay(
    val id: String,
    val name: String,
    val items: List<DayItem> = emptyList(),
)

/**
 * Ajustes globales (`S` en la web y en `docs/especificacion.md` §3).
 *
 * @param warm minutos de calentamiento que se suman a cada día.
 * @param trans segundos de traslado entre bloques (`ngroups - 1` veces por día).
 * @param drops bajadas de un drop set.
 * @param dropSec segundos de trabajo que suma cada bajada.
 * @param dropVol series efectivas que suma cada bajada.
 * @param optMin rango de referencia semanal, mínimo.
 * @param optMax rango de referencia semanal, máximo.
 * @param includeIndirect si el estado y los totales usan directas + indirectas.
 * @param e1rmFormula fórmula del PR estimado (`e1rmFormula` de los ajustes, §3.1).
 * @param bodyweightVolume si el volumen de las series de peso corporal incluye el
 *   peso corporal del día además del lastre (`bodyweightVolume`, §3.2).
 */
data class Settings(
    val warm: Int = 5,
    val trans: Int = 30,
    val drops: Int = 2,
    val dropSec: Int = 25,
    val dropVol: Double = 0.5,
    val optMin: Int = 10,
    val optMax: Int = 20,
    val includeIndirect: Boolean = false,
    val e1rmFormula: E1rmFormula = E1rmFormula.EPLEY,
    val bodyweightVolume: Boolean = false,
) {
    companion object {
        /** Los ajustes del seed de la web (`seed()` líneas 146-160 del HTML). */
        val DEFAULT: Settings = Settings()
    }
}
