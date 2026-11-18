package app.gimcheck.core

/**
 * Puerto 1:1 de `musclesOf` / `ssConflicts` / `heavyPairs` de
 * `Rutinas de Gimnasio.html` (líneas 329-354). Reglas en `docs/especificacion.md` §1.1
 * (advertencias del editor) y comparación en `docs/paridad-web.md`.
 *
 * Dos cosas de la web que hay que respetar tal cual:
 *
 *  - El conflicto se evalúa **sólo dentro de un bloque** (los ítems consecutivos unidos
 *    por `supersetNext`). Dos ítems sueltos que comparten músculo no son conflicto: son
 *    días de entrenamiento normales.
 *  - Se devuelve **un conflicto por par**, no por bloque. Un bloque de 3 ítems produce
 *    3 pares; la pestaña Comparar de la web cuenta `ssConflicts(d).length`, así que un
 *    bloque de 3 suma 3 al total de conflictos. Cambiar eso mueve los números.
 *
 * La web decide participación con `secondary.filter(function(x){return x.f>0})`: el
 * factor no pesa en la detección, sólo habilita (> 0). Acá se conserva ese criterio y
 * además se expone el factor de cada lado en [MuscleShare] para que la UI pueda explicar
 * por qué hay conflicto (principal contra secundario, secundario contra secundario).
 */

/**
 * Participación de un músculo en un ejercicio.
 *
 * @param factor 1 para el principal, el factor del secundario (0–1) para el resto.
 */
data class MuscleContribution(
    val muscleId: String,
    val factor: Double,
)

/**
 * Músculo compartido por dos ítems de un bloque, con el aporte de cada uno.
 *
 * @param overlap producto de los dos aportes (1×1 = mismo principal, 1×0,5 = principal
 *   contra secundario, 0,5×0,5 = los dos como secundarios). Es informativo: la detección
 *   del conflicto no depende de su valor, sólo de que ambos aportes sean mayores que 0.
 */
data class MuscleShare(
    val muscleId: String,
    val aFactor: Double,
    val bFactor: Double,
) {
    val overlap: Double get() = aFactor * bFactor
}

/** Dos ítems del mismo bloque comparten al menos un músculo. */
data class SupersetConflict(
    val a: DayItem,
    val b: DayItem,
    val shared: List<MuscleShare>,
) {
    /** Sólo los ids, en el orden de la web (principal primero, después los secundarios de `a`). */
    val sharedMuscleIds: List<String> get() = shared.map { it.muscleId }
}

/** Dos multiarticulares en el mismo bloque: la web lo marca como superserie muy pesada. */
data class HeavyPair(
    val a: DayItem,
    val b: DayItem,
)

/**
 * Músculos que participan en un ejercicio: principal (factor 1) y secundarios con
 * factor > 0, en ese orden.
 *
 * Diferencia deliberada con la web: si un secundario repite un músculo ya presente
 * (mismo id que el principal o repetido entre secundarios), acá queda una sola entrada
 * con el factor mayor. La web no deduplica y su advertencia puede decir
 * «Comparte Pecho, Pecho»; el editor de GimCheck además rechaza secundarios repetidos
 * ([DuplicateSecondary]).
 */
fun participatingMuscles(exercise: Exercise): List<MuscleContribution> {
    val byMuscle = LinkedHashMap<String, Double>()
    byMuscle[exercise.primaryMuscleId] = 1.0
    for (secondary in exercise.secondaries) {
        if (secondary.factor <= 0.0) continue
        val previous = byMuscle[secondary.muscleId]
        if (previous == null || secondary.factor > previous) {
            byMuscle[secondary.muscleId] = secondary.factor
        }
    }
    return byMuscle.map { MuscleContribution(it.key, it.value) }
}

/**
 * Conflictos de músculo dentro de un bloque ya armado (ver [groups]).
 *
 * Un bloque de un solo ítem no tiene pares, así que nunca devuelve conflictos: eso
 * cubre el ítem suelto y el último ítem del día con `supersetNext` (grupo de 1).
 */
fun supersetConflicts(block: List<DayItem>): List<SupersetConflict> {
    val out = mutableListOf<SupersetConflict>()
    for (i in block.indices) {
        val a = participatingMuscles(block[i].exercise)
        for (j in i + 1 until block.size) {
            val bFactors = participatingMuscles(block[j].exercise).associate { it.muscleId to it.factor }
            val shared = a.mapNotNull { contribution ->
                bFactors[contribution.muscleId]?.let { bFactor ->
                    MuscleShare(contribution.muscleId, contribution.factor, bFactor)
                }
            }
            if (shared.isNotEmpty()) out += SupersetConflict(block[i], block[j], shared)
        }
    }
    return out
}

/** Conflictos de músculo de todo el día, bloque por bloque (como `ssConflicts(day)`). */
fun supersetConflicts(day: RoutineDay): List<SupersetConflict> =
    groups(day.items).flatMap { supersetConflicts(it) }

/** Pares de multiarticulares dentro de un bloque (como `heavyPairs` sobre un grupo). */
fun heavyPairs(block: List<DayItem>): List<HeavyPair> {
    val out = mutableListOf<HeavyPair>()
    for (i in block.indices) {
        if (!block[i].exercise.compound) continue
        for (j in i + 1 until block.size) {
            if (block[j].exercise.compound) out += HeavyPair(block[i], block[j])
        }
    }
    return out
}

/** Pares de multiarticulares de todo el día, bloque por bloque (como `heavyPairs(day)`). */
fun heavyPairs(day: RoutineDay): List<HeavyPair> =
    groups(day.items).flatMap { heavyPairs(it) }

/**
 * Bloques de superserie reales del día (2 ítems o más). Los ítems sueltos no son bloque,
 * y un último ítem con `supersetNext = true` queda como grupo de 1 que esto descarta.
 */
fun supersetBlocks(day: RoutineDay): List<List<DayItem>> =
    groups(day.items).filter { it.size > 1 }
