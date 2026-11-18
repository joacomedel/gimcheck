package app.gimcheck.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Paridad con `Rutinas de Gimnasio.html`: mismos datos de ejemplo, mismos números.
 *
 * Los valores esperados son los que publica la web con el seed intacto (Rutina A,
 * ajustes por defecto: warm 5, trans 30, drops 2, dropSec 25, dropVol 0,5, optMin 10,
 * optMax 20, volumen indirecto desactivado). La tabla está en `docs/paridad-web.md`.
 */
class StatsParityTest {

    private val settings = Settings.DEFAULT

    /** `work`, `rest`, `other`, `total` y `sets` de cada día según la web. */
    private data class WebDay(
        val name: String,
        val work: Int,
        val rest: Int,
        val other: Int,
        val total: Int,
        val sets: Int,
    )

    private val webDays = listOf(
        WebDay("Día 1", work = 735, rest = 1320, other = 450, total = 2505, sets = 18),
        WebDay("Día 2", work = 660, rest = 1455, other = 450, total = 2565, sets = 18),
        WebDay("Día 3", work = 750, rest = 1290, other = 450, total = 2490, sets = 18),
        WebDay("Día 4", work = 710, rest = 1665, other = 510, total = 2885, sets = 18),
        WebDay("Día 5", work = 825, rest = 1230, other = 480, total = 2535, sets = 21),
        WebDay("Día 6", work = 515, rest = 1155, other = 450, total = 2120, sets = 13),
    )

    @Test
    fun `dayStats coincide con la web en los 6 dias`() {
        assertEquals(webDays.size, HtmlSeed.days.size)
        HtmlSeed.days.forEachIndexed { index, day ->
            val web = webDays[index]
            val stats = dayStats(day.items, settings)
            assertEquals("${web.name} work", web.work, stats.workSeconds)
            assertEquals("${web.name} rest", web.rest, stats.restSeconds)
            assertEquals("${web.name} other", web.other, stats.otherSeconds)
            assertEquals("${web.name} total", web.total, stats.totalSeconds)
            assertEquals("${web.name} sets", web.sets, stats.sets)
        }
    }

    @Test
    fun `la semana coincide con la web`() {
        val stats = HtmlSeed.days.map { dayStats(it.items, settings) }
        assertEquals(106, stats.sumOf { it.sets })
        assertEquals(15100, stats.sumOf { it.totalSeconds })
        // Los tiles de la web: "4 h 12 min" y promedio "42 min".
        assertEquals("4 h 12 min", formatDuration(stats.sumOf { it.totalSeconds }))
        assertEquals("42 min", formatDuration(stats.sumOf { it.totalSeconds } / HtmlSeed.days.size))
    }

    @Test
    fun `duracion por dia coincide con la web`() {
        val expected = listOf("42 min", "43 min", "42 min", "48 min", "42 min", "35 min")
        val actual = HtmlSeed.days.map { formatDuration(dayStats(it.items, settings).totalSeconds) }
        assertEquals(expected, actual)
    }

    @Test
    fun `volumen directo e indirecto por musculo coincide con la web`() {
        val volume = volume(HtmlSeed.days, settings, HtmlSeed.muscles)

        // Músculo -> (directas, indirectas, días con volumen). Tabla de la web.
        val expected = mapOf(
            "Pecho" to Triple(16.0, 1.75, 3),
            "Espalda" to Triple(14.0, 1.25, 3),
            "Hombro anterior" to Triple(6.0, 6.75, 4),
            "Hombro lateral" to Triple(9.0, 1.5, 4),
            "Hombro posterior" to Triple(5.0, 0.0, 2),
            "Bíceps" to Triple(10.0, 3.5, 3),
            "Tríceps" to Triple(10.0, 8.75, 3),
            "Antebrazo" to Triple(3.0, 3.75, 3),
            "Cuádriceps" to Triple(3.0, 0.0, 1),
            "Isquiotibiales" to Triple(3.0, 0.75, 1),
            "Glúteo" to Triple(3.0, 0.0, 1),
            "Gemelos" to Triple(6.0, 0.0, 2),
            "Abductores" to Triple(3.0, 0.0, 1),
            "Aductores" to Triple(3.0, 0.0, 1),
            "Abdomen" to Triple(12.0, 0.0, 2),
        )

        assertEquals(HtmlSeed.muscles.size, expected.size)
        assertEquals(expected.keys.toList(), volume.keys.toList())
        expected.forEach { (muscleId, web) ->
            val row = volume.getValue(muscleId)
            assertEquals("$muscleId directas", web.first, row.direct, 1e-9)
            assertEquals("$muscleId indirectas", web.second, row.indirect, 1e-9)
            assertEquals("$muscleId días", web.third, row.days)
        }
    }

    @Test
    fun `los totales semanales de la tabla coinciden`() {
        val volume = volume(HtmlSeed.days, settings, HtmlSeed.muscles)
        assertEquals(106.0, volume.values.sumOf { it.direct }, 1e-9)
        assertEquals(28.0, volume.values.sumOf { it.indirect }, 1e-9)
    }

    @Test
    fun `el estado por musculo coincide con la web`() {
        val volumenIndirecto = settings.copy(includeIndirect = true)
        val volume = volume(HtmlSeed.days, settings, HtmlSeed.muscles)

        // Sólo directas (toggle apagado, como el seed).
        val directo = mapOf(
            "Pecho" to VolumeBand.IN_RANGE,
            "Espalda" to VolumeBand.IN_RANGE,
            "Hombro anterior" to VolumeBand.LOW,
            "Hombro lateral" to VolumeBand.LOW,
            "Hombro posterior" to VolumeBand.LOW,
            "Bíceps" to VolumeBand.IN_RANGE,
            "Tríceps" to VolumeBand.IN_RANGE,
            "Antebrazo" to VolumeBand.VERY_LOW,
            "Cuádriceps" to VolumeBand.VERY_LOW,
            "Isquiotibiales" to VolumeBand.VERY_LOW,
            "Glúteo" to VolumeBand.VERY_LOW,
            "Gemelos" to VolumeBand.LOW,
            "Abductores" to VolumeBand.VERY_LOW,
            "Aductores" to VolumeBand.VERY_LOW,
            "Abdomen" to VolumeBand.IN_RANGE,
        )
        directo.forEach { (muscleId, band) ->
            assertEquals("$muscleId (sólo directas)", band, estado(volume.getValue(muscleId), settings))
        }

        // Con volumen indirecto: cuatro músculos suben de banda, el resto igual.
        val conIndirecto = mapOf(
            "Hombro anterior" to VolumeBand.IN_RANGE,
            "Hombro lateral" to VolumeBand.IN_RANGE,
            "Antebrazo" to VolumeBand.LOW,
            "Pecho" to VolumeBand.IN_RANGE,
        )
        conIndirecto.forEach { (muscleId, band) ->
            assertEquals(
                "$muscleId (directas + indirectas)",
                band,
                estado(volume.getValue(muscleId), volumenIndirecto),
            )
        }
        assertEquals(
            "Hombro posterior sigue bajo",
            VolumeBand.LOW,
            estado(volume.getValue("Hombro posterior"), volumenIndirecto),
        )
    }

    @Test
    fun `los textos de estado son los de la web`() {
        assertEquals("▼ Muy bajo", VolumeBand.VERY_LOW.label)
        assertEquals("▼ Bajo", VolumeBand.LOW.label)
        assertEquals("✓ En rango", VolumeBand.IN_RANGE.label)
        assertEquals("▲ Alto", VolumeBand.HIGH.label)
    }

    @Test
    fun `el plan A no tiene superseries ni drop sets`() {
        val items = HtmlSeed.days.flatMap { it.items }
        assertEquals(39, items.size)
        assertEquals(6, groups(HtmlSeed.days[0].items).size)
        assertEquals(0, items.count { it.supersetNext })
        assertEquals(0, items.count { it.dropSet })
        // effSets de un ítem sin drop set son sus series.
        assertEquals(3.0, effectiveSets(items.first(), settings), 1e-9)
    }
}
