package app.gimcheck.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Casos borde que el ticket exige, con los números calculados a mano desde las
 * reglas del HTML (no desde la implementación). Cubren las trampas del puerto:
 * el último descanso se descuenta una sola vez, la ronda la define el ítem más
 * largo, y el drop set sólo suma en la última ronda de su ítem.
 */
class StatsEdgeCasesTest {

    private val settings = Settings.DEFAULT

    private fun exercise(
        id: String,
        work: Int,
        rest: Int,
        primary: String = "Pecho",
        vararg secondary: Pair<String, Double>,
    ) = Exercise(
        id = id,
        name = id,
        primaryMuscleId = primary,
        secondaries = secondary.map { Secondary(it.first, it.second) },
        secondsPerSet = work,
        restSeconds = rest,
    )

    private fun item(
        exercise: Exercise,
        sets: Int,
        dropSet: Boolean = false,
        supersetNext: Boolean = false,
        restOverrideSeconds: Int? = null,
    ) = DayItem(
        id = "${exercise.id}-$sets-$dropSet-$supersetNext",
        exercise = exercise,
        sets = sets,
        dropSet = dropSet,
        supersetNext = supersetNext,
        restOverrideSeconds = restOverrideSeconds,
    )

    @Test
    fun `grupo de un solo item`() {
        val items = listOf(item(exercise("a", 45, 120), sets = 3))

        assertEquals(1, groups(items).size)
        assertEquals(listOf(1), groups(items).map { it.size })

        val stats = dayStats(items, settings)
        assertEquals(135, stats.workSeconds)
        assertEquals(240, stats.restSeconds) // 3 rondas de 120 menos el último descanso
        assertEquals(300, stats.otherSeconds) // sin grupos que separar: sólo el calentamiento
        assertEquals(675, stats.totalSeconds)
        assertEquals(3, stats.sets)
    }

    @Test
    fun `superserie de tres items`() {
        val items = listOf(
            item(exercise("a", 45, 120), sets = 3, supersetNext = true),
            item(exercise("b", 35, 75), sets = 3, supersetNext = true),
            item(exercise("c", 40, 90), sets = 3),
        )

        assertEquals(1, groups(items).size)
        assertEquals(3, groups(items).single().size)

        val stats = dayStats(items, settings)
        assertEquals(360, stats.workSeconds) // 3 rondas de 45+35+40
        assertEquals(240, stats.restSeconds) // 3 × max(120, 75, 90) − el último
        assertEquals(300, stats.otherSeconds) // un solo grupo: no hay traslados
        assertEquals(900, stats.totalSeconds)
        assertEquals(9, stats.sets)
    }

    @Test
    fun `drop set suma trabajo y series efectivas en la ultima ronda`() {
        val items = listOf(item(exercise("a", 45, 120), sets = 3, dropSet = true))

        val stats = dayStats(items, settings)
        assertEquals(185, stats.workSeconds) // 3 × 45 + 2 bajadas × 25 s
        assertEquals(240, stats.restSeconds)
        assertEquals(725, stats.totalSeconds)
        assertEquals(3, stats.sets) // las series cargadas no cambian

        // El volumen sí: 2 bajadas × 0,5 series.
        assertEquals(4.0, effectiveSets(items.single(), settings), 1e-9)

        // Con otros ajustes, la fórmula sigue a drops y dropVol.
        val otro = settings.copy(drops = 3, dropVol = 1.0, dropSec = 20)
        assertEquals(195, dayStats(items, otro).workSeconds) // 135 + 3 × 20
        assertEquals(6.0, effectiveSets(items.single(), otro), 1e-9)
    }

    @Test
    fun `item con menos series que la ronda del bloque`() {
        val items = listOf(
            item(exercise("a", 45, 120), sets = 3, supersetNext = true),
            item(exercise("b", 35, 75), sets = 2),
        )

        assertEquals(1, groups(items).size)
        val stats = dayStats(items, settings)
        assertEquals(205, stats.workSeconds) // 2 rondas de 80 + la tercera sólo con "a"
        assertEquals(240, stats.restSeconds) // la ronda la manda "a" (120 s)
        assertEquals(745, stats.totalSeconds)
        assertEquals(5, stats.sets)
    }

    @Test
    fun `dia vacio igual suma calentamiento`() {
        val stats = dayStats(emptyList(), settings)

        assertEquals(0, stats.workSeconds)
        assertEquals(0, stats.restSeconds)
        assertEquals(300, stats.otherSeconds) // warm 5 min, sin traslados
        assertEquals(300, stats.totalSeconds)
        assertEquals(0, stats.sets)
        assertEquals(0, groups(emptyList()).size)
        assertEquals("5 min", formatDuration(stats.totalSeconds))
    }

    @Test
    fun `musculo sin entrenar aparece en cero y muy bajo`() {
        val muscles = listOf(Muscle("Pecho", "Pecho"), Muscle("Gemelos", "Gemelos"))
        val days = listOf(
            RoutineDay("d1", "Día 1", listOf(item(exercise("a", 45, 120, primary = "Pecho"), sets = 3))),
        )

        val volume = volume(days, settings, muscles)
        assertEquals(listOf("Pecho", "Gemelos"), volume.keys.toList())

        val gemelos = volume.getValue("Gemelos")
        assertEquals(0.0, gemelos.direct, 1e-9)
        assertEquals(0.0, gemelos.indirect, 1e-9)
        assertEquals(0, gemelos.days)
        assertEquals(VolumeBand.VERY_LOW, estado(gemelos, settings))
    }

    @Test
    fun `frecuencia cuenta dias distintos y solo indirectos de una serie completa`() {
        val pecho = exercise("pp", 45, 120, primary = "Pecho", "Tríceps" to 0.5)
        val days = listOf(
            RoutineDay("d1", "Día 1", listOf(item(pecho, sets = 1), item(pecho, sets = 2))),
            RoutineDay("d2", "Día 2", listOf(item(pecho, sets = 1))),
        )

        val volume = volume(days, settings, listOf(Muscle("Pecho", "Pecho"), Muscle("Tríceps", "Tríceps")))
        val pectoral = volume.getValue("Pecho")
        assertEquals(4.0, pectoral.direct, 1e-9)
        assertEquals(2, pectoral.days) // dos ítems el mismo día cuentan una vez

        // Indirectas: 1×0,5 + 2×0,5 + 1×0,5 = 2,0; sólo el ítem de 2 series llega a 1.
        val triceps = volume.getValue("Tríceps")
        assertEquals(2.0, triceps.indirect, 1e-9)
        assertEquals(1, triceps.days)
    }

    @Test
    fun `rest override pisa el descanso del ejercicio`() {
        val items = listOf(
            item(exercise("a", 45, 120), sets = 3, restOverrideSeconds = 60),
        )

        val stats = dayStats(items, settings)
        assertEquals(120, stats.restSeconds) // 3 × 60 − 60
        assertEquals(555, stats.totalSeconds) // 135 de trabajo + 120 + 300
    }

    @Test
    fun `superserie abierta al final del dia forma un solo grupo`() {
        val items = listOf(
            item(exercise("a", 45, 120), sets = 3, supersetNext = true),
            item(exercise("b", 35, 75), sets = 3, supersetNext = true),
        )

        assertEquals(1, groups(items).size)
        val stats = dayStats(items, settings)
        assertEquals(240, stats.workSeconds)
        assertEquals(240, stats.restSeconds)
        assertEquals(780, stats.totalSeconds)
        assertEquals(6, stats.sets)
    }

    @Test
    fun `sin superseries cada item es un grupo y el dia cuesta mas`() {
        val items = listOf(
            item(exercise("a", 45, 120), sets = 3, supersetNext = true),
            item(exercise("b", 35, 75), sets = 3),
        )

        assertEquals(1, groups(items).size)
        assertEquals(2, groups(items, noSupersets = true).size)

        val conSuperserie = dayStats(items, settings)
        val sinSuperserie = dayStats(items, settings, noSupersets = true)

        assertEquals(780, conSuperserie.totalSeconds)
        // Dos bloques sueltos: 240 s de trabajo, 510 s de descanso, 330 s de calentamiento
        // y un traslado de 30 s. El ahorro que reporta la web son 300 s (5 min).
        assertEquals(1080, sinSuperserie.totalSeconds)
        assertEquals(300, sinSuperserie.totalSeconds - conSuperserie.totalSeconds)
        assertEquals("5 min", formatDuration(sinSuperserie.totalSeconds - conSuperserie.totalSeconds))
    }

    @Test
    fun `umbrales de estado`() {
        assertEquals(VolumeBand.VERY_LOW, estado(4.99, settings))
        assertEquals(VolumeBand.LOW, estado(5.0, settings)) // 0,5 × optMin ya no es "muy bajo"
        assertEquals(VolumeBand.LOW, estado(9.99, settings))
        assertEquals(VolumeBand.IN_RANGE, estado(10.0, settings))
        assertEquals(VolumeBand.IN_RANGE, estado(20.0, settings))
        assertEquals(VolumeBand.HIGH, estado(20.01, settings))

        val exigente = settings.copy(optMin = 8, optMax = 30)
        assertEquals(VolumeBand.VERY_LOW, estado(3.99, exigente))
        assertEquals(VolumeBand.LOW, estado(4.0, exigente))
        assertEquals(VolumeBand.IN_RANGE, estado(30.0, exigente))
        assertEquals(VolumeBand.HIGH, estado(30.5, exigente))
    }

    @Test
    fun `formato de duracion de la web`() {
        assertEquals("0 min", formatDuration(0))
        assertEquals("1 min", formatDuration(59))
        assertEquals("1 min", formatDuration(60))
        assertEquals("42 min", formatDuration(2505))
        assertEquals("59 min", formatDuration(3540))
        assertEquals("1 h 00 min", formatDuration(3600))
        assertEquals("2 h 01 min", formatDuration(7260))
        assertEquals("4 h 12 min", formatDuration(15100))
    }

    @Test
    fun `formato de numeros con coma decimal`() {
        assertEquals("2,5", formatNumber(2.5))
        assertEquals("1,75", formatNumber(1.75))
        assertEquals("16", formatNumber(16.0))
        assertEquals("0", formatNumber(0.0))
        assertEquals("1234,57", formatNumber(1234.567))
        assertEquals("2,56", formatNumber(2.555)) // 2,555*100 = 255,5000…: empate hacia arriba, igual que el JS
        assertEquals("-2,5", formatNumber(-2.5))
        assertEquals("0,05", formatNumber(0.05))
    }
}
