package app.gimcheck.core

/**
 * Puerto 1:1 de `groupsOf` / `dayStats` / `effSets` de `Rutinas de Gimnasio.html`
 * (líneas 331-385 del HTML). Las reglas están en `docs/especificacion.md` §3.
 *
 * Todo es puro: mismo input, mismo output, sin estado global ni orden de iteración
 * dependiente de hashes.
 */

/**
 * Agrupa ítems consecutivos donde `supersetNext = true`; un ítem suelto es un grupo
 * de 1. Con `noSupersets = true` cada ítem es su propio grupo: así la web calcula
 * cuánto ahorran las superseries.
 */
fun groups(items: List<DayItem>, noSupersets: Boolean = false): List<List<DayItem>> {
    val result = mutableListOf<MutableList<DayItem>>()
    var current: MutableList<DayItem>? = null
    for (item in items) {
        val open = current ?: mutableListOf<DayItem>().also {
            result += it
            current = it
        }
        open += item
        if (noSupersets || !item.supersetNext) current = null
    }
    return result
}

/** Descanso efectivo de un ítem: el override del ítem gana sobre el del ejercicio. */
internal fun restSecondsOf(item: DayItem): Int =
    item.restOverrideSeconds ?: item.exercise.restSeconds

/**
 * Métricas de un día: trabajo, descanso, calentamiento/traslados y total, en segundos.
 *
 * Reglas que no son obvias y hay que respetar (verificado contra la web):
 *  - Se descuenta **un solo** descanso al final del día (el de la última ronda del
 *    último grupo), no uno por grupo. Es lo que hace `rest -= lastRest` en el HTML.
 *  - En una ronda participan sólo los ítems con `sets > r`; los que ya terminaron no
 *    aportan trabajo ni entran en el máximo de descanso de esa ronda.
 *  - Un día vacío igual suma el calentamiento (`warm * 60`).
 *
 * @param noSupersets como el segundo parámetro de la web (`dayStats(day, true)`):
 *   ignora las marcas de superserie para medir cuánto tiempo ahorran.
 */
fun dayStats(
    items: List<DayItem>,
    settings: Settings,
    noSupersets: Boolean = false,
): DayStats {
    var work = 0
    var rest = 0
    var sets = 0
    var lastRest = 0
    var groupCount = 0

    for (group in groups(items, noSupersets)) {
        if (group.isEmpty()) continue
        groupCount++
        val rounds = group.maxOf { it.sets }
        for (r in 0 until rounds) {
            var roundRest = 0
            for (item in group) {
                if (item.sets > r) {
                    work += item.exercise.secondsPerSet
                    if (item.dropSet && r == item.sets - 1) {
                        work += settings.drops * settings.dropSec
                    }
                    roundRest = maxOf(roundRest, restSecondsOf(item))
                }
            }
            rest += roundRest
            lastRest = roundRest
        }
        for (item in group) sets += item.sets
    }

    rest -= lastRest
    val other = maxOf(0, groupCount - 1) * settings.trans + settings.warm * 60
    return DayStats(
        workSeconds = work,
        restSeconds = rest,
        otherSeconds = other,
        totalSeconds = work + rest + other,
        sets = sets,
    )
}

/** Métricas de un día, en segundos. `total = work + rest + other`. */
data class DayStats(
    val workSeconds: Int,
    val restSeconds: Int,
    val otherSeconds: Int,
    val totalSeconds: Int,
    val sets: Int,
)

/**
 * Series efectivas de un ítem: las series cargadas más lo que aporta el drop set.
 * Es la unidad de volumen que usa la web (`effSets`).
 */
fun effectiveSets(item: DayItem, settings: Settings): Double =
    item.sets.toDouble() + if (item.dropSet) settings.drops * settings.dropVol else 0.0

/**
 * Formato de duración de la web (`fmt`): minutos redondeados; a partir de 60 min
 * pasa a `3 h 12 min` con dos dígitos en los minutos.
 */
fun formatDuration(totalSeconds: Number): String {
    // Math.round de Java es floor(x + 0,5), idéntico al de JS: mismos empates.
    val minutes = Math.round(totalSeconds.toDouble() / 60.0)
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val rest = minutes % 60
    val padded = if (rest < 10) "0$rest" else "$rest"
    return "$hours h $padded min"
}

/**
 * Formato de número de la web (`num`): dos decimales como máximo, sin ceros de
 * cola y con **coma decimal** (`2,5`, `1,75`, `16`).
 *
 * Redondea sobre centésimos con `Math.round` (floor(x + 0,5)), que es exactamente
 * lo que hace el JS, así que `2.555` da `2,55` en los dos lados.
 */
fun formatNumber(value: Double): String {
    val cents = Math.round(value * 100.0)
    val negative = cents < 0
    val abs = if (negative) -cents else cents
    val whole = abs / 100
    val frac = (abs % 100).toInt()
    val sign = if (negative) "-" else ""
    return when {
        frac == 0 -> "$sign$whole"
        frac % 10 == 0 -> "$sign$whole,${frac / 10}"
        else -> "$sign$whole,${frac.toString().padStart(2, '0')}"
    }
}
