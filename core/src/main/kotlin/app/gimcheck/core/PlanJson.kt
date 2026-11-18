package app.gimcheck.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.round

/**
 * Export de **plan** con el formato exacto de la web (`docs/especificacion.md` §7).
 *
 * `buildPlanJson()` de `Rutinas de Gimnasio.html` (líneas 455-466) produce:
 *
 * ```
 * { app: "rutinas-gimnasio", tipo: "plan", version: 1,
 *   plan: { nombre, dias: [ { nombre, ejercicios: [ { ejercicio, series, dropSet,
 *           superserieConSiguiente } ] } ] },
 *   ejercicios: [ { nombre, musculoPrincipal, musculosSecundarios: [ { musculo, factor } ],
 *                   segundosPorSerie, descansoSegundos, multiarticular } ],
 *   ajustes: <settings>,
 *   resumen: { minutosSemana, seriesSemana, minutosPorDia: [...], seriesPorMusculo: [...] } }
 * ```
 *
 * Se replica tal cual (incluido el `resumen`, que es lo que el dueño mira al pegar el
 * JSON) **salvo los ids**: el formato de la web referencia los ejercicios por nombre,
 * así que un plan exportado no es re-importable como plan nuevo con ids propios; eso
 * es una limitación del formato de la web, no de esta función. El backup propio
 * (`exportBackup`) sí conserva ids.
 */
const val PLAN_APP: String = "rutinas-gimnasio"
const val PLAN_TIPO: String = "plan"
const val PLAN_VERSION: Int = 1

@Serializable
data class PlanFile(
    val app: String = PLAN_APP,
    val tipo: String = PLAN_TIPO,
    val version: Int = PLAN_VERSION,
    val plan: PlanBody,
    val ejercicios: List<PlanExercise> = emptyList(),
    val ajustes: PlanSettings,
    val resumen: PlanSummary,
)

@Serializable
data class PlanBody(
    val nombre: String,
    val dias: List<PlanDay> = emptyList(),
)

@Serializable
data class PlanDay(
    val nombre: String,
    val ejercicios: List<PlanDayItem> = emptyList(),
)

@Serializable
data class PlanDayItem(
    val ejercicio: String,
    val series: Int,
    val dropSet: Boolean = false,
    val superserieConSiguiente: Boolean = false,
)

@Serializable
data class PlanExercise(
    val nombre: String,
    val musculoPrincipal: String,
    val musculosSecundarios: List<PlanSecondary> = emptyList(),
    val segundosPorSerie: Int,
    val descansoSegundos: Int,
    val multiarticular: Boolean = false,
)

@Serializable
data class PlanSecondary(
    val musculo: String,
    val factor: Double,
)

/** `S.settings` de la web: los siete ajustes que existen ahí, ni uno más. */
@Serializable
data class PlanSettings(
    val warm: Int,
    val trans: Int,
    val drops: Int,
    val dropSec: Int,
    val dropVol: Double,
    val optMin: Int,
    val optMax: Int,
)

@Serializable
data class PlanSummary(
    val minutosSemana: Int,
    val seriesSemana: Int,
    val minutosPorDia: List<PlanDaySummary> = emptyList(),
    val seriesPorMusculo: List<PlanMuscleSummary> = emptyList(),
)

@Serializable
data class PlanDaySummary(
    val dia: String,
    val minutos: Int,
    val series: Int,
)

@Serializable
data class PlanMuscleSummary(
    val musculo: String,
    val directas: Double,
    val indirectas: Double,
)

private val planJson = Json { encodeDefaults = true }

private val prettyPlanJson = Json(planJson) { prettyPrint = true }

/** `r1` de la web: un decimal, redondeo half-up. */
private fun round1(value: Double): Double = round(value * 10.0) / 10.0

/** `Math.round(x / 60)` de la web sobre segundos. */
private fun minutes(seconds: Int): Int = Math.round(seconds / 60.0).toInt()

/**
 * Serializa una rutina como plan de la web. Determinista: las listas salen en el
 * orden del modelo (días → ítems → catálogo de músculos) y no hay mapas.
 *
 * @param muscles catálogo para el resumen por músculo (incluye los músculos sin
 *   volumen, que después se filtran como en la web).
 * @param pretty indentado de 2 espacios, que es lo que escribe `buildPlanJson()`.
 */
fun exportPlan(
    routine: Routine,
    muscles: List<Muscle>,
    settings: Settings,
    pretty: Boolean = true,
): String {
    val stats = routine.days.map { dayStats(it.items, settings) }
    val weeklyVolume = volume(routine.days, settings, muscles)

    val file = PlanFile(
        plan = PlanBody(
            nombre = routine.name,
            dias = routine.days.map { day ->
                PlanDay(
                    nombre = day.name,
                    ejercicios = day.items.map { item ->
                        PlanDayItem(
                            ejercicio = item.exercise.name,
                            series = item.sets,
                            dropSet = item.dropSet,
                            superserieConSiguiente = item.supersetNext,
                        )
                    },
                )
            },
        ),
        ejercicios = routine.days
            .flatMap { it.items }
            .map { it.exercise }
            .distinctBy { it.id }
            .map { exercise ->
                PlanExercise(
                    nombre = exercise.name,
                    musculoPrincipal = exercise.primaryMuscleId,
                    musculosSecundarios = exercise.secondaries.map { PlanSecondary(it.muscleId, it.factor) },
                    segundosPorSerie = exercise.secondsPerSet,
                    descansoSegundos = exercise.restSeconds,
                    multiarticular = exercise.compound,
                )
            },
        ajustes = PlanSettings(
            warm = settings.warm,
            trans = settings.trans,
            drops = settings.drops,
            dropSec = settings.dropSec,
            dropVol = settings.dropVol,
            optMin = settings.optMin,
            optMax = settings.optMax,
        ),
        resumen = PlanSummary(
            minutosSemana = minutes(stats.sumOf { it.totalSeconds }),
            seriesSemana = stats.sumOf { it.sets },
            minutosPorDia = routine.days.mapIndexed { index, day ->
                PlanDaySummary(
                    dia = day.name,
                    minutos = minutes(stats[index].totalSeconds),
                    series = stats[index].sets,
                )
            },
            seriesPorMusculo = weeklyVolume.values
                .filter { it.direct + it.indirect > 0.0 }
                .map {
                    PlanMuscleSummary(
                        musculo = muscles.firstOrNull { muscle -> muscle.id == it.muscleId }?.name ?: it.muscleId,
                        directas = round1(it.direct),
                        indirectas = round1(it.indirect),
                    )
                },
        ),
    )
    val json = if (pretty) prettyPlanJson else planJson
    return json.encodeToString(PlanFile.serializer(), file)
}
