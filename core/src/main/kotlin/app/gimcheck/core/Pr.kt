package app.gimcheck.core

/**
 * PR (récord personal) y PR de la sesión — `docs/especificacion.md` §3.1.
 *
 * Regla central: una serie es PR si su e1RM **supera el máximo previo a esa serie**,
 * es decir el histórico anterior más las series anteriores de la misma sesión. Se
 * compara contra el pasado, nunca contra el resto de la sesión (una serie posterior
 * más pesada no le quita el PR a la que ya lo rompió). Un empate no es PR.
 */

/** Margen para tratar dos valores como empate (ruido de coma flotante). */
private const val PR_EPSILON = 1e-9

/**
 * Evaluación de una serie para el badge "PR".
 *
 * @param previousBest máximo previo a esta serie (histórico + anteriores de la
 *   sesión); `null` si es la primera serie válida del ejercicio.
 * @param isPr `true` sólo si supera [previousBest] con margen; la primera serie de
 *   un ejercicio sin historial cuenta como PR.
 */
data class SetE1rm(
    val set: SessionSet,
    val value: Double,
    val isPr: Boolean,
    val previousBest: Double?,
)

/**
 * e1RM de cada serie válida de la sesión, en orden de `completedAt`, marcando PR.
 *
 * Quedan afuera las series de calentamiento y las que no generan e1RM
 * (`weightKg <= 0`, `reps` fuera de 1..12): no aparecen en la lista y no mueven el
 * máximo. El orden es estable: con la misma marca de tiempo se respeta el orden de
 * entrada.
 *
 * @param priorBest récords históricos por ejercicio **anteriores a la sesión**
 *   (ver [historicalBestByExercise] sobre las sesiones previas).
 */
fun sessionE1rm(
    session: Session,
    priorBest: Map<String, Double>,
    formula: E1rmFormula,
): List<SetE1rm> {
    val running = HashMap<String, Double>()
    val out = ArrayList<SetE1rm>()
    for (set in session.sets.sortedBy { it.completedAt }) {
        val value = e1rm(set, formula) ?: continue
        val previous = running[set.exerciseId] ?: priorBest[set.exerciseId]
        val isPr = previous == null || value > previous + PR_EPSILON
        if (previous == null || value > previous) running[set.exerciseId] = value
        out += SetE1rm(set = set, value = value, isPr = isPr, previousBest = previous)
    }
    return out
}

fun sessionE1rm(
    session: Session,
    priorBest: Map<String, Double>,
    settings: Settings,
): List<SetE1rm> = sessionE1rm(session, priorBest, settings.e1rmFormula)

/**
 * PR histórico por ejercicio: máximo e1RM de todas las sesiones, sin calentamientos
 * ni series descartadas. Las series se toman en el orden dado, así que se puede
 * alimentar con las sesiones anteriores a la que se está anotando.
 */
fun historicalBestByExercise(
    sessions: List<Session>,
    formula: E1rmFormula,
): Map<String, Double> {
    val best = LinkedHashMap<String, Double>()
    for (session in sessions) {
        for (set in session.sets.sortedBy { it.completedAt }) {
            val value = e1rm(set, formula) ?: continue
            val current = best[set.exerciseId]
            if (current == null || value > current) best[set.exerciseId] = value
        }
    }
    return best
}

fun historicalBestByExercise(
    sessions: List<Session>,
    settings: Settings,
): Map<String, Double> = historicalBestByExercise(sessions, settings.e1rmFormula)

/** PR de la sesión: el máximo e1RM de sus series válidas (§3.1), o `null`. */
fun sessionBest(session: Session, formula: E1rmFormula): Double? =
    session.sets.mapNotNull { e1rm(it, formula) }.maxOrNull()

fun sessionBest(session: Session, settings: Settings): Double? =
    sessionBest(session, settings.e1rmFormula)

/** La mejor serie de la sesión evaluada contra el histórico, o `null` sin series válidas. */
fun sessionBestSet(
    session: Session,
    priorBest: Map<String, Double>,
    formula: E1rmFormula,
): SetE1rm? = sessionE1rm(session, priorBest, formula).maxByOrNull { it.value }
