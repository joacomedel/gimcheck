package app.gimcheck.core

/**
 * Puerto 1:1 de `volume` y `status` de `Rutinas de Gimnasio.html` (líneas 382-410).
 * Reglas en `docs/especificacion.md` §3.
 */

/** Banda de referencia semanal de un músculo. Los textos son los de la web. */
enum class VolumeBand(val label: String) {
    VERY_LOW("▼ Muy bajo"),
    LOW("▼ Bajo"),
    IN_RANGE("✓ En rango"),
    HIGH("▲ Alto"),
}

/**
 * Series semanales de un músculo.
 *
 * @param direct series efectivas donde el músculo es principal.
 * @param indirect series efectivas de otros ejercicios ponderadas por el factor.
 * @param days cantidad de días de la rutina en los que el músculo recibe volumen
 *   (directo siempre cuenta; indirecto sólo si aporta 1 serie efectiva o más).
 */
data class MuscleVolume(
    val muscleId: String,
    val direct: Double,
    val indirect: Double,
    val days: Int,
) {
    /** Total que muestra la web según el toggle de volumen indirecto. */
    fun total(includeIndirect: Boolean = false): Double =
        if (includeIndirect) direct + indirect else direct
}

/**
 * Volumen semanal por músculo.
 *
 * @param days días de la rutina (la web recibe `S.days`; acá el plan completo).
 * @param muscles catálogo: garantiza que los músculos sin entrenar aparezcan con
 *   ceros en vez de faltar de la tabla. El orden de la lista se conserva.
 * @return filas por `muscleId`, en el orden del catálogo y después los ids que
 *   aparezcan en los ejercicios pero no en el catálogo (igual que la web, que
 *   crea la entrada al vuelo).
 */
fun volume(
    days: List<RoutineDay>,
    settings: Settings,
    muscles: List<Muscle> = emptyList(),
): Map<String, MuscleVolume> {
    val acc = LinkedHashMap<String, MutableVolume>()
    for (muscle in muscles) acc.getOrPut(muscle.id) { MutableVolume() }

    days.forEachIndexed { dayIndex, day ->
        for (item in day.items) {
            val effective = effectiveSets(item, settings)

            val primary = acc.getOrPut(item.exercise.primaryMuscleId) { MutableVolume() }
            primary.direct += effective
            primary.days.add(dayIndex)

            for (secondary in item.exercise.secondaries) {
                val row = acc.getOrPut(secondary.muscleId) { MutableVolume() }
                val share = effective * secondary.factor
                row.indirect += share
                // La web sólo cuenta el día si el aporte llega a una serie completa.
                if (share >= 1.0) row.days.add(dayIndex)
            }
        }
    }

    return acc.mapValuesTo(LinkedHashMap()) { (id, v) ->
        MuscleVolume(muscleId = id, direct = v.direct, indirect = v.indirect, days = v.days.size)
    }
}

/**
 * Banda de un valor de series contra el rango de referencia.
 *
 * `valor < 0.5 * optMin` → muy bajo, `< optMin` → bajo, `<= optMax` → en rango,
 * el resto alto. `includeIndirect` no cambia los umbrales: cambia qué valor se pasa.
 */
fun estado(value: Double, settings: Settings): VolumeBand = when {
    value < settings.optMin * 0.5 -> VolumeBand.VERY_LOW
    value < settings.optMin -> VolumeBand.LOW
    value <= settings.optMax -> VolumeBand.IN_RANGE
    else -> VolumeBand.HIGH
}

/** Atajo: banda de un músculo según el toggle de volumen indirecto. */
fun estado(row: MuscleVolume, settings: Settings): VolumeBand =
    estado(row.total(settings.includeIndirect), settings)

private class MutableVolume {
    var direct: Double = 0.0
    var indirect: Double = 0.0
    val days: MutableSet<Int> = mutableSetOf()
}
