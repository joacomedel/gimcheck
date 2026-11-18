package app.gimcheck.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §3.1 (e1RM y PR) y §3.2 (volumen de sesión y agregados).
 *
 * Casos exigidos por el ticket: 100 kg × 5 → Epley 116,66 / Brzycki 112,5; empate de
 * PR; PR roto en medio de la sesión; bodyweight puro con y sin `bodyweightVolume`;
 * calentamientos fuera; `reps > 12` sin e1RM pero con volumen; semana ISO en el borde
 * de año.
 */
class PrVolumeTest {

    private val settings = Settings.DEFAULT

    private fun at(day: LocalDate, hour: Int = 18, minute: Int = 0) =
        LocalDateTime(day.year, day.month, day.day, hour, minute)

    private fun set(
        id: String,
        exercise: String = "press",
        weight: Double,
        reps: Int,
        at: LocalDateTime = at(LocalDate(2026, 3, 2)),
        warmup: Boolean = false,
        dropSet: Boolean = false,
    ) = SessionSet(
        id = id,
        exerciseId = exercise,
        weightKg = weight,
        reps = reps,
        completedAt = at,
        isWarmup = warmup,
        isDropSet = dropSet,
    )

    private fun session(
        id: String,
        day: LocalDate = LocalDate(2026, 3, 2),
        bodyWeight: Double? = null,
        sets: List<SessionSet> = emptyList(),
    ) = Session(
        id = id,
        startedAt = at(day, 17),
        bodyWeightKg = bodyWeight,
        sets = sets,
    )

    // --- §3.1 e1RM -----------------------------------------------------------

    @Test
    fun `100 kg x 5 da Epley 116,66 y Brzycki 112,5`() {
        val epley = e1rm(100.0, 5, E1rmFormula.EPLEY)!!
        val brzycki = e1rm(100.0, 5, E1rmFormula.BRZYCKI)!!

        // 100 * (1 + 5/30) = 350/3 = 116,666…
        assertEquals(350.0 / 3.0, epley, 1e-9)
        assertEquals(116.66, epley, 0.01)
        // 100 * 36 / (37 - 5) = 3600/32 = 112,5
        assertEquals(112.5, brzycki, 0.0)
        // La presentación de la web: coma decimal y sin ceros de cola.
        assertEquals("112,5", formatNumber(brzycki))
    }

    @Test
    fun `e1rm descarta reps fuera de 1 a 12 y pesos no positivos`() {
        assertNull("reps 0", e1rm(100.0, 0))
        assertNull("reps negativas", e1rm(100.0, -3))
        assertNull("reps 13", e1rm(100.0, 13))
        assertNull("peso 0", e1rm(0.0, 5))
        assertNull("peso negativo", e1rm(-20.0, 5))
        assertNull("peso NaN", e1rm(Double.NaN, 5))
        // Los bordes del rango sí estiman.
        assertTrue(e1rm(100.0, 1)!! > 100.0)
        assertTrue(e1rm(100.0, 12)!! > 0.0)
    }

    @Test
    fun `Epley es la formula por defecto`() {
        assertEquals(E1rmFormula.EPLEY, Settings.DEFAULT.e1rmFormula)
        assertEquals(
            e1rm(80.0, 6, E1rmFormula.EPLEY),
            e1rm(80.0, 6),
        )
    }

    // --- §3.1 PR -------------------------------------------------------------

    @Test
    fun `empate de PR no marca PR nuevo`() {
        val s = session(
            "s1",
            sets = listOf(
                set("a", weight = 100.0, reps = 5),
                set("b", weight = 100.0, reps = 5),
            ),
        )
        val points = sessionE1rm(s, emptyMap(), E1rmFormula.EPLEY)
        assertEquals(2, points.size)
        assertTrue("la primera serie válida es PR", points[0].isPr)
        assertNull(points[0].previousBest)
        assertFalse("el empate no es PR", points[1].isPr)
        assertEquals(points[0].value, points[1].previousBest!!, 0.0)
    }

    @Test
    fun `PR se rompe en medio de la sesion y la serie anterior no era PR`() {
        // Histórico: 110 kg x 5 (Epley 128,33).
        val history = historicalBestByExercise(
            listOf(session("h", sets = listOf(set("h1", weight = 110.0, reps = 5)))),
            E1rmFormula.EPLEY,
        )
        val s = session(
            "s",
            sets = listOf(
                set("a", weight = 105.0, reps = 5), // 122,50 → no
                set("b", weight = 112.0, reps = 5), // 130,67 → sí, rompe en el medio
                set("c", weight = 100.0, reps = 5), // 116,67 → no
            ),
        )
        val points = sessionE1rm(s, history, E1rmFormula.EPLEY).associateBy { it.set.id }

        assertFalse(points.getValue("a").isPr)
        assertTrue(points.getValue("b").isPr)
        assertFalse("después del PR las series más livianas no marcan", points.getValue("c").isPr)
        // La comparación es contra el máximo previo, no contra el posterior.
        assertEquals(history["press"]!!, points.getValue("a").previousBest!!, 1e-9)
        // El running max no baja: la serie floja de "a" no reemplaza al histórico.
        assertEquals(history["press"]!!, points.getValue("b").previousBest!!, 1e-9)
        assertEquals(points.getValue("b").value, points.getValue("c").previousBest!!, 1e-9)
    }

    @Test
    fun `calentamientos fuera del PR y del e1RM`() {
        val s = session(
            "s",
            sets = listOf(
                // Calentamiento más pesado que la serie de trabajo: si contara, sería PR.
                set("w", weight = 140.0, reps = 1, warmup = true),
                set("a", weight = 100.0, reps = 5),
            ),
        )
        val points = sessionE1rm(s, emptyMap(), E1rmFormula.EPLEY)
        assertEquals(1, points.size)
        assertEquals("a", points[0].set.id)
        assertTrue(points[0].isPr)
        assertNull("el calentamiento no genera e1RM", e1rm(s.sets[0], E1rmFormula.EPLEY))
    }

    @Test
    fun `PR historico por ejercicio y PR de la sesion`() {
        val s1 = session("s1", sets = listOf(set("a", weight = 100.0, reps = 5)))
        val s2 = session("s2", sets = listOf(set("b", weight = 90.0, reps = 8)))
        val s3 = session("s3", sets = listOf(set("c", exercise = "sentadilla", weight = 150.0, reps = 3)))

        val best = historicalBestByExercise(listOf(s1, s2, s3), settings)
        assertEquals(e1rm(100.0, 5)!!, best.getValue("press"), 1e-9)
        assertEquals(e1rm(150.0, 3)!!, best.getValue("sentadilla"), 1e-9)

        assertEquals(e1rm(100.0, 5)!!, sessionBest(s1, settings)!!, 1e-9)
        assertNull(sessionBest(session("vacia"), settings))
    }

    @Test
    fun `reps mayor a 12 no genera e1RM pero si volumen`() {
        val s = session("s", sets = listOf(set("a", weight = 100.0, reps = 15)))
        assertNull(e1rm(s.sets[0], E1rmFormula.EPLEY))
        assertTrue(sessionE1rm(s, emptyMap(), E1rmFormula.EPLEY).isEmpty())
        assertNull(sessionBest(s, settings))
        assertEquals(1500.0, sessionVolume(s, emptyList(), settings), 0.0)
    }

    // --- §3.2 volumen --------------------------------------------------------

    @Test
    fun `volumen de sesion excluye calentamientos`() {
        val s = session(
            "s",
            sets = listOf(
                set("w1", weight = 40.0, reps = 10, warmup = true),
                set("a", weight = 100.0, reps = 5),
                set("b", weight = 100.0, reps = 5),
            ),
        )
        assertEquals(1000.0, sessionVolume(s, emptyList(), settings), 1e-9)
    }

    @Test
    fun `sesion sin peso - bodyweight puro respeta bodyweightVolume`() {
        val sentadilla = Exercise(
            id = "bw",
            name = "Dominada",
            primaryMuscleId = "espalda",
            secondsPerSet = 40,
            restSeconds = 90,
            loadType = LoadType.BODYWEIGHT,
        )
        val s = session(
            "s",
            bodyWeight = 80.0,
            sets = listOf(
                set("a", exercise = "bw", weight = 0.0, reps = 10),
                set("b", exercise = "bw", weight = 0.0, reps = 8),
            ),
        )

        // Sin el ajuste: peso corporal puro no suma volumen (weight 0).
        assertEquals(0.0, sessionVolume(s, listOf(sentadilla), settings), 1e-9)

        // Con el ajuste: 80 * (10 + 8).
        val conPeso = settings.copy(bodyweightVolume = true)
        assertEquals(1440.0, sessionVolume(s, listOf(sentadilla), conPeso), 1e-9)

        // Sin peso corporal del día no se inventa: queda en 0.
        val sinPeso = session("sin", sets = listOf(set("a", exercise = "bw", weight = 0.0, reps = 10)))
        assertEquals(0.0, sessionVolume(sinPeso, listOf(sentadilla), conPeso), 1e-9)
    }

    @Test
    fun `bodyweight con lastre suma lastre y peso corporal`() {
        val fondos = Exercise(
            id = "bw",
            name = "Fondos",
            primaryMuscleId = "pecho",
            secondsPerSet = 40,
            restSeconds = 90,
            loadType = LoadType.BODYWEIGHT,
        )
        val s = session(
            "s",
            bodyWeight = 80.0,
            sets = listOf(set("a", exercise = "bw", weight = 10.0, reps = 8)),
        )
        // 10 * 8 de lastre + 80 * 8 de cuerpo = 80 + 640.
        assertEquals(720.0, sessionVolume(s, listOf(fondos), settings.copy(bodyweightVolume = true)), 1e-9)
        // Sin el ajuste sólo cuenta el lastre.
        assertEquals(80.0, sessionVolume(s, listOf(fondos), settings), 1e-9)
    }

    // --- §3.2 agregados ------------------------------------------------------

    @Test
    fun `semana ISO en el borde de anio`() {
        // 2026 tiene 53 semanas ISO y el 31/12 (jueves) cae en la última.
        assertEquals(IsoWeek(2026, 53), isoWeek(LocalDate(2026, 12, 31)))
        assertEquals(IsoWeek(2026, 53), isoWeek(LocalDate(2026, 12, 28)))
        // El lunes 30/12/2024 ya es la semana 1 de 2025.
        assertEquals(IsoWeek(2025, 1), isoWeek(LocalDate(2024, 12, 30)))
        // El 1/1/2022 (sábado) todavía es la semana 52 de 2021.
        assertEquals(IsoWeek(2021, 52), isoWeek(LocalDate(2022, 1, 1)))
        // Un caso normal, lejos del borde.
        assertEquals(IsoWeek(2026, 10), isoWeek(LocalDate(2026, 3, 2)))
    }

    @Test
    fun `agregados por sesion, dia y semana ISO`() {
        val s1 = session(
            "s1",
            day = LocalDate(2026, 12, 28),
            sets = listOf(
                set("a", weight = 100.0, reps = 5, at = at(LocalDate(2026, 12, 28))),
                set("b", weight = 100.0, reps = 5, at = at(LocalDate(2026, 12, 28))),
            ),
        )
        val s2 = session(
            "s2",
            day = LocalDate(2027, 1, 4),
            sets = listOf(set("c", weight = 80.0, reps = 10, at = at(LocalDate(2027, 1, 4)))),
        )
        val sessions = listOf(s1, s2)

        assertEquals(mapOf("s1" to 1000.0, "s2" to 800.0), volumeBySession(sessions, emptyList(), settings))
        assertEquals(
            mapOf(LocalDate(2026, 12, 28) to 1000.0, LocalDate(2027, 1, 4) to 800.0),
            volumeByDay(sessions, emptyList(), settings),
        )
        assertEquals(
            listOf(IsoWeek(2026, 53) to 1000.0, IsoWeek(2027, 1) to 800.0),
            volumeByIsoWeek(sessions, emptyList(), settings).toList(),
        )
    }

    @Test
    fun `series efectivas por musculo con calentamientos y drops fuera`() {
        val press = Exercise(
            id = "press",
            name = "Press banca",
            primaryMuscleId = "pecho",
            secondaries = listOf(Secondary("triceps", 0.5)),
            secondsPerSet = 40,
            restSeconds = 120,
        )
        val s = session(
            "s",
            sets = listOf(
                set("w", weight = 60.0, reps = 10, warmup = true),
                set("a", weight = 100.0, reps = 5),
                set("b", weight = 100.0, reps = 5),
                set("c", weight = 80.0, reps = 6, dropSet = true),
            ),
        )
        val byMuscle = sessionVolumeByMuscle(listOf(s), listOf(press), settings)

        // 2 series normales + 1 drop (0,5 de serie efectiva).
        assertEquals(2.5, byMuscle.getValue("pecho").direct, 1e-9)
        assertEquals(0.0, byMuscle.getValue("pecho").indirect, 1e-9)
        assertEquals(0.0, byMuscle.getValue("triceps").direct, 1e-9)
        assertEquals(1.25, byMuscle.getValue("triceps").indirect, 1e-9)
        assertEquals(1, byMuscle.getValue("pecho").days)
        // 1,25 series efectivas indirectas: pasa el umbral de una serie completa.
        assertEquals(1, byMuscle.getValue("triceps").days)
    }
}
