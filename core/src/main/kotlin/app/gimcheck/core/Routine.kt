package app.gimcheck.core

import kotlinx.datetime.LocalDate

/**
 * Rutina completa (fila `routine` de `docs/especificacion.md` §2) con sus días.
 *
 * El modelo de `:core` que llegó hasta acá sólo tenía [RoutineDay] (el día y sus
 * ítems); la rutina como contenedor aparece con el import/export, que necesita
 * representar "Rutina A" y "Rutina B" de la web. Nada de esto es persistencia:
 * GIM-10 puede mapear esta clase 1:1 a Room o sustituirla.
 *
 * @param activeDayId el día que la web deja seleccionado (`activeDay` del plan).
 * @param createdAt ISO-8601; vacío si la fuente no lo tenía (la web no lo guarda).
 */
data class Routine(
    val id: String,
    val name: String,
    val days: List<RoutineDay> = emptyList(),
    val activeDayId: String? = null,
    val notes: String = "",
    val position: Int = 0,
    val createdAt: String = "",
    val archived: Boolean = false,
)

/**
 * Peso corporal diario (`body_metric` de §2). Clave natural: la fecha.
 *
 * @param updatedAt ISO-8601; se usa para el last-write-wins de la fusión.
 */
data class BodyMetric(
    val date: LocalDate,
    val weightKg: Double,
    val bodyFatPct: Double? = null,
    val note: String = "",
    val updatedAt: String = "",
)

/**
 * Todo el estado exportable de la app, ya como modelo de dominio.
 *
 * Es lo que produce [importWeb] y `importBackup`, y lo que consume [exportBackup]:
 * así el import y el export comparten un único contrato y no dos conversiones.
 */
data class DomainSnapshot(
    val settings: Settings,
    val muscles: List<Muscle>,
    val exercises: List<Exercise>,
    val routines: List<Routine>,
    val sessions: List<Session> = emptyList(),
    val bodyMetrics: List<BodyMetric> = emptyList(),
    val activeRoutineId: String? = null,
) {
    private val exercisesById: Map<String, Exercise> = exercises.associateBy { it.id }

    fun exercise(id: String): Exercise? = exercisesById[id]

    /** Atajo para tests y para la UI: la rutina activa, o la primera. */
    fun activeRoutine(): Routine? =
        routines.firstOrNull { it.id == activeRoutineId } ?: routines.firstOrNull()
}
