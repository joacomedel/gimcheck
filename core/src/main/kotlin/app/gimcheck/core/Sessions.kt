package app.gimcheck.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalIsoWeekDate

/**
 * Sesión anotada — `docs/especificacion.md` §2 (`session`, `session_set`) y §3.2.
 *
 * Decisión de esta capa: las marcas de tiempo son [LocalDateTime] (hora local, sin
 * zona). Todo lo que se agrega en §3.2 es "por día calendario / semana ISO", y eso
 * se define en la hora del usuario; el repositorio (GIM-10) convierte el epoch de
 * Room a local antes de armar el dominio. Así `:core` queda determinista y sin
 * depender del reloj ni del `TimeZone` del equipo.
 */

/**
 * Serie anotada de una sesión.
 *
 * @param weightKg peso externo (lastre incluido) en kg. `0.0` = peso corporal puro.
 * @param rpe esfuerzo percibido opcional (6–10); no entra en ningún cálculo de v1.
 * @param isWarmup calentamiento: fuera de volumen y de PR (§3.1, §3.2 y §4).
 * @param isDropSet serie de bajada de un drop set (cuenta como `settings.dropVol`
 *   series efectivas, igual que la web cuenta las bajadas en `effSets`).
 * @param completedAt hora local de la serie; ordena la detección de PR.
 */
data class SessionSet(
    val id: String,
    val exerciseId: String,
    val weightKg: Double,
    val reps: Int,
    val completedAt: LocalDateTime,
    val rpe: Double? = null,
    val isWarmup: Boolean = false,
    val isDropSet: Boolean = false,
)

/**
 * Sesión de entrenamiento.
 *
 * @param bodyWeightKg peso corporal del día (`session.body_weight_kg`); si falta,
 *   el repositorio lo completa con el `body_metric` de la fecha. Acá no se busca en
 *   ninguna base: entra por parámetro.
 * @param sets series anotadas, en cualquier orden (la detección de PR las ordena).
 */
data class Session(
    val id: String,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime? = null,
    val bodyWeightKg: Double? = null,
    val routineId: String? = null,
    val routineDayId: String? = null,
    val sets: List<SessionSet> = emptyList(),
)

/** Semana ISO-8601: `year` es el año-semana (puede no ser el año calendario). */
data class IsoWeek(val year: Int, val week: Int)

/**
 * Semana ISO de una fecha. 2026-12-31 → 2026-W53; 2024-12-30 (lunes) → 2025-W01.
 * La cuenta la hace kotlinx-datetime (`toLocalIsoWeekDate`), que ya es dependencia
 * de `:core`; los bordes de año están testeados igual.
 */
fun isoWeek(date: LocalDate): IsoWeek {
    val iso = date.toLocalIsoWeekDate()
    return IsoWeek(year = iso.isoWeekYear, week = iso.isoWeekNumber)
}

/** Semana ISO de la fecha de inicio de una sesión. */
fun isoWeek(session: Session): IsoWeek = isoWeek(session.startedAt.date)

/**
 * Volumen (kg) de una serie según §3.2.
 *
 *  - Calentamiento → 0 (fuera de volumen).
 *  - `EXTERNAL` → `weightKg * reps`.
 *  - `BODYWEIGHT` → `weightKg * reps` (el lastre, si hay) y, si
 *    `settings.bodyweightVolume = true`, **además** `bodyWeightKg * reps`.
 *    Sin peso corporal conocido no se inventa nada: aporta sólo el lastre.
 *
 * @param bodyWeightKg peso corporal del día (de la sesión o del `body_metric`).
 */
fun setVolume(
    set: SessionSet,
    loadType: LoadType,
    bodyWeightKg: Double?,
    settings: Settings,
): Double {
    if (set.isWarmup) return 0.0
    val reps = set.reps.coerceAtLeast(0)
    if (reps == 0) return 0.0
    val external = if (set.weightKg > 0.0) set.weightKg * reps else 0.0
    if (loadType != LoadType.BODYWEIGHT || !settings.bodyweightVolume) return external
    val body = bodyWeightKg?.takeIf { it > 0.0 } ?: return external
    return external + body * reps
}

/**
 * Volumen de una sesión, con el `LoadType` resuelto por el llamador (el repo lo
 * tiene en la tabla `exercise`; acá no hay acceso a datos).
 */
fun sessionVolume(
    session: Session,
    loadTypeOf: (String) -> LoadType,
    settings: Settings,
): Double = session.sets.sumOf { setVolume(it, loadTypeOf(it.exerciseId), session.bodyWeightKg, settings) }

/** Volumen de una sesión con el catálogo de ejercicios a mano. */
fun sessionVolume(session: Session, exercises: List<Exercise>, settings: Settings): Double {
    val byId = exercises.associateBy { it.id }
    return sessionVolume(session, { byId[it]?.loadType ?: LoadType.EXTERNAL }, settings)
}

/**
 * Volumen por sesión (`session.id` → kg). El orden de las sesiones se conserva.
 * Una sesión sin series aparece con 0.
 */
fun volumeBySession(
    sessions: List<Session>,
    loadTypeOf: (String) -> LoadType,
    settings: Settings,
): Map<String, Double> {
    val out = LinkedHashMap<String, Double>()
    for (session in sessions) {
        out[session.id] = (out[session.id] ?: 0.0) + sessionVolume(session, loadTypeOf, settings)
    }
    return out
}

fun volumeBySession(
    sessions: List<Session>,
    exercises: List<Exercise>,
    settings: Settings,
): Map<String, Double> {
    val byId = exercises.associateBy { it.id }
    return volumeBySession(sessions, { byId[it]?.loadType ?: LoadType.EXTERNAL }, settings)
}

/**
 * Volumen por día calendario (fecha de inicio de la sesión, hora local).
 * Dos sesiones el mismo día se suman.
 */
fun volumeByDay(
    sessions: List<Session>,
    loadTypeOf: (String) -> LoadType,
    settings: Settings,
): Map<LocalDate, Double> {
    val out = linkedMapOf<LocalDate, Double>()
    for (session in sessions) {
        val day = session.startedAt.date
        out[day] = (out[day] ?: 0.0) + sessionVolume(session, loadTypeOf, settings)
    }
    return out
}

fun volumeByDay(
    sessions: List<Session>,
    exercises: List<Exercise>,
    settings: Settings,
): Map<LocalDate, Double> {
    val byId = exercises.associateBy { it.id }
    return volumeByDay(sessions, { byId[it]?.loadType ?: LoadType.EXTERNAL }, settings)
}

/**
 * Volumen por semana ISO. La semana sale de la fecha de inicio de la sesión, y el
 * mapa queda ordenado por (año-semana, semana).
 */
fun volumeByIsoWeek(
    sessions: List<Session>,
    loadTypeOf: (String) -> LoadType,
    settings: Settings,
): Map<IsoWeek, Double> {
    val acc = HashMap<IsoWeek, Double>()
    for (session in sessions) {
        val week = isoWeek(session)
        acc[week] = (acc[week] ?: 0.0) + sessionVolume(session, loadTypeOf, settings)
    }
    return acc.entries
        .sortedWith(compareBy({ it.key.year }, { it.key.week }))
        .associateTo(LinkedHashMap()) { it.key to it.value }
}

fun volumeByIsoWeek(
    sessions: List<Session>,
    exercises: List<Exercise>,
    settings: Settings,
): Map<IsoWeek, Double> {
    val byId = exercises.associateBy { it.id }
    return volumeByIsoWeek(sessions, { byId[it]?.loadType ?: LoadType.EXTERNAL }, settings)
}

/**
 * Series efectivas que aporta una serie anotada, con la misma convención que la
 * web (`seriesEfectivas` = series + bajadas × `dropVol`):
 *  - calentamiento → 0
 *  - serie normal → 1
 *  - serie de drop → `settings.dropVol` (0,5 por defecto)
 */
fun loggedEffectiveSets(set: SessionSet, settings: Settings): Double = when {
    set.isWarmup -> 0.0
    set.isDropSet -> settings.dropVol
    else -> 1.0
}

/**
 * Series efectivas **anotadas** por músculo: directas para el principal, ponderadas
 * por `factor` para los secundarios. `days` cuenta días calendario distintos y usa el
 * mismo umbral que [volume] (una serie efectiva completa), así los números comparan
 * con el análisis semanal de la web.
 */
fun sessionVolumeByMuscle(
    sessions: List<Session>,
    exercises: List<Exercise>,
    settings: Settings,
): Map<String, MuscleVolume> {
    val byId = exercises.associateBy { it.id }
    val direct = LinkedHashMap<String, Double>()
    val indirect = LinkedHashMap<String, Double>()
    val days = HashMap<String, MutableSet<LocalDate>>()

    for (session in sessions) {
        val day = session.startedAt.date
        // Se agrupa por ejercicio (el equivalente al ítem del día): el factor de un
        // secundario se aplica sobre las series efectivas del ejercicio en la sesión,
        // no serie por serie. Es lo que hace la web con `seriesEfectivas(item)`.
        val effectiveByExercise = LinkedHashMap<String, Double>()
        for (set in session.sets) {
            val effective = loggedEffectiveSets(set, settings)
            if (effective <= 0.0) continue
            effectiveByExercise[set.exerciseId] =
                (effectiveByExercise[set.exerciseId] ?: 0.0) + effective
        }

        for ((exerciseId, effective) in effectiveByExercise) {
            val exercise = byId[exerciseId] ?: continue
            direct[exercise.primaryMuscleId] = (direct[exercise.primaryMuscleId] ?: 0.0) + effective
            days.getOrPut(exercise.primaryMuscleId) { mutableSetOf() }.add(day)
            for (secondary in exercise.secondaries) {
                val share = effective * secondary.factor
                if (share <= 0.0) continue
                indirect[secondary.muscleId] = (indirect[secondary.muscleId] ?: 0.0) + share
                if (share >= 1.0) days.getOrPut(secondary.muscleId) { mutableSetOf() }.add(day)
            }
        }
    }

    val ids = LinkedHashSet<String>().apply {
        addAll(direct.keys)
        addAll(indirect.keys)
    }
    return ids.associateTo(LinkedHashMap()) { id ->
        id to MuscleVolume(
            muscleId = id,
            direct = direct[id] ?: 0.0,
            indirect = indirect[id] ?: 0.0,
            days = days[id]?.size ?: 0,
        )
    }
}
