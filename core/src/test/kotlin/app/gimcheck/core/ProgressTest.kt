package app.gimcheck.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Datos derivados de Progreso: e1RM por sesión, PR actual y rangos de fecha. */
class ProgressTest {

    private fun set(
        id: String,
        exercise: String = "e1",
        weight: Double,
        reps: Int,
        at: String,
        warmup: Boolean = false,
    ) = SessionSet(
        id = id,
        exerciseId = exercise,
        weightKg = weight,
        reps = reps,
        completedAt = LocalDateTime.parse(at),
        isWarmup = warmup,
    )

    private fun session(id: String, at: String, sets: List<SessionSet>) = Session(
        id = id,
        startedAt = LocalDateTime.parse(at),
        endedAt = LocalDateTime.parse(at),
        sets = sets,
    )

    @Test
    fun `un punto por sesion con la mejor serie y en orden cronologico`() {
        val sessions = listOf(
            session("s2", "2026-11-18T10:00", listOf(set("2a", weight = 100.0, reps = 5, at = "2026-11-18T10:05"))),
            session("s1", "2026-11-11T10:00", listOf(
                set("1a", weight = 80.0, reps = 5, at = "2026-11-11T10:05"),
                set("1b", weight = 90.0, reps = 3, at = "2026-11-11T10:10"),
            )),
        )
        val points = e1rmBySession(sessions, "e1", E1rmFormula.EPLEY)
        assertEquals(listOf("s1", "s2"), points.map { it.sessionId })
        assertEquals(99.0, points[0].value, 1e-9) // 90 * (1 + 3/30)
        assertEquals(LocalDate(2026, 11, 11), points[0].date)
    }

    @Test
    fun `las sesiones sin series validas no generan punto`() {
        val sessions = listOf(
            session("s1", "2026-11-11T10:00", listOf(set("1a", weight = 80.0, reps = 5, at = "2026-11-11T10:05", warmup = true))),
            session("s2", "2026-11-12T10:00", listOf(set("2a", exercise = "otro", weight = 80.0, reps = 5, at = "2026-11-12T10:05"))),
            session("s3", "2026-11-13T10:00", emptyList()),
        )
        assertTrue(e1rmBySession(sessions, "e1", E1rmFormula.EPLEY).isEmpty())
    }

    @Test
    fun `el PR actual es el ultimo salto de record y guarda contra que se supero`() {
        val sessions = listOf(
            session("s1", "2026-11-01T10:00", listOf(set("1", weight = 100.0, reps = 5, at = "2026-11-01T10:05"))),
            session("s2", "2026-11-08T10:00", listOf(set("2", weight = 105.0, reps = 5, at = "2026-11-08T10:05"))),
            session("s3", "2026-11-15T10:00", listOf(set("3", weight = 100.0, reps = 8, at = "2026-11-15T10:05"))),
        )
        val pr = requireNotNull(currentPr(sessions, "e1", E1rmFormula.EPLEY))
        // 105 * (1 + 5/30) = 122,5; 100 * (1 + 8/30) = 126,66…
        assertEquals(126.66666666666667, pr.value, 1e-9)
        assertEquals(122.5, requireNotNull(pr.previousBest), 1e-9)
        assertEquals("3", pr.set.id)
    }

    @Test
    fun `un empate no es PR y el record queda en la primera vez`() {
        val sessions = listOf(
            session("s1", "2026-11-01T10:00", listOf(set("1", weight = 100.0, reps = 5, at = "2026-11-01T10:05"))),
            session("s2", "2026-11-08T10:00", listOf(set("2", weight = 100.0, reps = 5, at = "2026-11-08T10:05"))),
        )
        val pr = requireNotNull(currentPr(sessions, "e1", E1rmFormula.EPLEY))
        assertEquals("1", pr.set.id)
        assertNull(pr.previousBest)
    }

    @Test
    fun `los PR de cada sesion se evaluan contra el historico anterior`() {
        val sessions = listOf(
            session("s1", "2026-11-01T10:00", listOf(set("1", weight = 100.0, reps = 5, at = "2026-11-01T10:05"))),
            session("s2", "2026-11-08T10:00", listOf(
                set("2", weight = 90.0, reps = 5, at = "2026-11-08T10:05"),
                set("3", weight = 110.0, reps = 5, at = "2026-11-08T10:10"),
            )),
        )
        val counts = sessionPrCounts(sessions, E1rmFormula.EPLEY)
        assertEquals(1, counts.getValue("s1").size)
        assertEquals(1, counts.getValue("s2").size)
        assertEquals("3", counts.getValue("s2").single().set.id)
    }

    @Test
    fun `el rango 3 M arranca tres meses atras y Todo no tiene limite`() {
        val today = LocalDate(2026, 11, 27)
        assertEquals(LocalDate(2026, 8, 27), ChartRange.THREE_MONTHS.start(today))
        assertEquals(LocalDate(2026, 5, 27), ChartRange.SIX_MONTHS.start(today))
        assertNull(ChartRange.ALL.start(today))
    }

    @Test
    fun `restar meses recorta el dia al ultimo del mes`() {
        assertEquals(LocalDate(2026, 2, 28), LocalDate(2026, 3, 31).minusMonthsClamped(1))
        assertEquals(LocalDate(2024, 2, 29), LocalDate(2024, 3, 31).minusMonthsClamped(1))
        assertEquals(LocalDate(2025, 12, 15), LocalDate(2026, 1, 15).minusMonthsClamped(1))
    }
}
