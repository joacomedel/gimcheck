package app.gimcheck.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * Datos derivados para la pantalla **Progreso** (`docs/especificacion.md` §3.1 y §3.2).
 *
 * Kotlin puro y determinista: entra el histórico terminado y sale lo que la pantalla
 * dibuja. La UI no vuelve a calcular e1RM, PR ni volumen; eso ya está en
 * [sessionE1rm], [e1rm] y [volumeByIsoWeek], y repetirlo en un composable es la forma
 * más fácil de que el gráfico y la tarjeta de PR muestren números distintos.
 */

/** Un punto del gráfico de e1RM: la mejor serie de una sesión. */
data class E1rmPoint(
    val sessionId: String,
    val date: LocalDate,
    val value: Double,
    /** La serie que logró el valor (para el detalle del PR). */
    val set: SessionSet,
)

/**
 * e1RM máximo de cada sesión para un ejercicio, en orden cronológico.
 *
 * Una sesión sin series válidas del ejercicio (o con puro calentamiento) no genera
 * punto: el gráfico no inventa ceros. Con una sola sesión devuelve un punto, y la
 * pantalla decide mostrarlo como punto sin línea.
 */
fun e1rmBySession(
    sessions: List<Session>,
    exerciseId: String,
    formula: E1rmFormula,
): List<E1rmPoint> = sessions
    .asSequence()
    .filter { session -> session.sets.any { it.exerciseId == exerciseId } }
    .sortedBy { it.startedAt }
    .mapNotNull { session ->
        val best = session.sets
            .asSequence()
            .filter { it.exerciseId == exerciseId }
            .mapNotNull { set -> e1rm(set, formula)?.let { set to it } }
            .maxByOrNull { it.second }
            ?: return@mapNotNull null
        E1rmPoint(
            sessionId = session.id,
            date = session.startedAt.date,
            value = best.second,
            set = best.first,
        )
    }
    .toList()

/**
 * El PR actual de un ejercicio: el valor, la serie que lo logró y el récord que
 * superó.
 *
 * @param value mejor e1RM histórico.
 * @param previousBest el máximo anterior a esa serie (`null` si es el primero), que
 *   es contra lo que se muestra el delta del PR.
 */
data class PrRecord(
    val exerciseId: String,
    val value: Double,
    val achievedAt: LocalDateTime,
    val set: SessionSet,
    val previousBest: Double?,
) {
    /** Diferencia contra el récord anterior; `null` si este es el primero. */
    val delta: Double? get() = previousBest?.let { value - it }
}

/**
 * PR actual de [exerciseId] sobre el histórico, con la regla de [sessionE1rm]:
 * una serie supera el máximo **anterior** a ella; un empate no es PR.
 *
 * Devuelve el último salto de récord, no simplemente la serie más pesada: si dos
 * sesiones empatan el máximo, el PR sigue siendo el que lo estableció primero.
 */
fun currentPr(
    sessions: List<Session>,
    exerciseId: String,
    formula: E1rmFormula,
): PrRecord? {
    var best: Double? = null
    var record: PrRecord? = null
    for (session in sessions.sortedBy { it.startedAt }) {
        for (set in session.sets.filter { it.exerciseId == exerciseId }.sortedBy { it.completedAt }) {
            val value = e1rm(set, formula) ?: continue
            val previous = best
            if (previous == null || value > previous) {
                best = value
                record = PrRecord(
                    exerciseId = exerciseId,
                    value = value,
                    achievedAt = set.completedAt,
                    set = set,
                    previousBest = previous,
                )
            }
        }
    }
    return record
}

/** Un PR de una sesión concreta, con el nombre que la pantalla necesite. */
data class SessionPr(
    val exerciseId: String,
    val value: Double,
    val set: SessionSet,
)

/**
 * PRs logrados **en una sesión**, evaluados contra el histórico anterior.
 *
 * Es la misma regla de [sessionE1rm] aplicada con [priorBest]; se expone aparte
 * porque Historial necesita el conteo por sesión y el resumen del día necesita la
 * lista. No cuenta dos veces el mismo récord dentro de la sesión: la serie posterior
 * que lo supera es la que vale.
 */
fun sessionPrs(
    session: Session,
    priorBest: Map<String, Double>,
    formula: E1rmFormula,
): List<SessionPr> = sessionE1rm(session, priorBest, formula)
    .filter { it.isPr }
    .groupBy { it.set.exerciseId }
    .map { (exerciseId, sets) ->
        val best = sets.maxBy { it.value }
        SessionPr(exerciseId = exerciseId, value = best.value, set = best.set)
    }

/**
 * PRs de cada sesión del histórico, en el orden de entrada.
 *
 * `Live` (el badge "PR" de la serie) y los gráficos comparten esta cuenta: el
 * histórico se recorre una sola vez y cada sesión se evalúa contra lo anterior.
 */
fun sessionPrCounts(
    sessions: List<Session>,
    formula: E1rmFormula,
): Map<String, List<SessionPr>> {
    val prior = LinkedHashMap<String, Double>()
    val out = LinkedHashMap<String, List<SessionPr>>()
    for (session in sessions.sortedBy { it.startedAt }) {
        val prs = sessionPrs(session, prior, formula)
        out[session.id] = prs
        for (setE1rm in sessionE1rm(session, prior, formula)) {
            val current = prior[setE1rm.set.exerciseId]
            if (current == null || setE1rm.value > current) {
                prior[setE1rm.set.exerciseId] = setE1rm.value
            }
        }
    }
    return out
}

/** Rango temporal de los gráficos (`3 M / 6 M / 1 A / Todo`, §6). */
enum class ChartRange(val months: Int?) {
    THREE_MONTHS(3),
    SIX_MONTHS(6),
    ONE_YEAR(12),
    ALL(null),
    ;

    /** Inicio del rango para una fecha de referencia, o `null` = sin límite. */
    fun start(today: LocalDate): LocalDate? = months?.let { today.minusMonthsClamped(it) }
}

/** `LocalDate.minusMonths` sin dependencias nuevas: resta meses y recorta el día. */
internal fun LocalDate.minusMonthsClamped(months: Int): LocalDate {
    var year = this.year
    var month = this.monthNumber - months
    while (month < 1) {
        month += 12
        year -= 1
    }
    val lastDay = daysInMonth(year, month)
    return LocalDate(year, month, dayOfMonth.coerceAtMost(lastDay))
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    else -> if (isLeapYear(year)) 29 else 28
}

private fun isLeapYear(year: Int): Boolean =
    year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
