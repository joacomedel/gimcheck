package app.gimcheck.data.repository

import app.gimcheck.core.BodyMetric
import app.gimcheck.core.Session
import app.gimcheck.core.SessionSet
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.inMemoryDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Tipos de fecha en el borde de la persistencia (deuda heredada de la auditoría de
 * GIM-9).
 *
 * `body_metric.updated_at`, `session.started_at`/`ended_at` y
 * `session_set.completed_at` están en **epoch millis UTC** en Room, y `:core` trabaja
 * con `LocalDateTime`/`String` ISO. Estos tests fijan las dos direcciones:
 *
 *  - el valor que se **escribe** es el instante correcto en UTC (se mira la entidad,
 *    no el dominio: si el repositorio guardara la hora local como si fuera UTC, acá se
 *    vería);
 *  - el valor que se **lee** vuelve idéntico al que entró, en la misma zona, también
 *    en una zona con horario de verano.
 */
@RunWith(RobolectricTestRunner::class)
class InstantBoundaryTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun local(text: String): LocalDateTime = LocalDateTime.parse(text)

    /** 2026-11-18 10:15:30 en Buenos Aires (UTC-3) son las 13:15:30 UTC. */
    @Test
    fun `body_metric guarda los millis del instante y devuelve el ISO original`() = runBlocking {
        val zone = ZoneId.of("America/Argentina/Buenos_Aires")
        val metrics = MetricsRepository(db, zone = zone)
        val updated = "2026-11-18T10:15:30"

        metrics.save(
            BodyMetric(
                date = LocalDate.parse("2026-11-18"),
                weightKg = 78.4,
                bodyFatPct = 17.5,
                note = "en ayunas",
                updatedAt = updated,
            ),
        )

        val row = requireNotNull(db.bodyMetricDao().getByDate("2026-11-18"))
        assertEquals("2026-11-18T13:15:30Z", java.time.Instant.ofEpochMilli(row.updatedAt).toString())

        val loaded = requireNotNull(metrics.get(LocalDate.parse("2026-11-18")))
        assertEquals(updated, loaded.updatedAt)
        assertEquals(78.4, loaded.weightKg, 1e-9)
        assertEquals("en ayunas", loaded.note)
    }

    @Test
    fun `updated_at vacio se guarda como 0 y ordena antes que cualquier fecha`() = runBlocking {
        val metrics = MetricsRepository(db, zone = ZoneOffset.UTC)

        metrics.save(BodyMetric(date = LocalDate.parse("2026-11-17"), weightKg = 79.0, updatedAt = ""))

        val row = requireNotNull(db.bodyMetricDao().getByDate("2026-11-17"))
        assertEquals(0L, row.updatedAt)
        assertEquals("1970-01-01T00:00", metrics.get(LocalDate.parse("2026-11-17"))!!.updatedAt)
    }

    @Test
    fun `session startedAt y endedAt vuelven identicos en una zona con horario de verano`() = runBlocking {
        val zone = ZoneId.of("Europe/Madrid")
        val sessions = SessionRepository(db, zone = zone)
        val started = local("2026-03-29T09:30:00")
        val ended = local("2026-03-29T11:00:00")
        val id = UUID.randomUUID().toString()

        sessions.start(Session(id = id, startedAt = started, routineId = "pa", routineDayId = "da1"))
        sessions.finish(id, endedAt = ended, notes = "buena")

        val row = requireNotNull(db.sessionDao().getById(id))
        // Madrid ya está en CEST (UTC+2) a las 09:30 del 2026-03-29: 07:30 UTC.
        assertEquals("2026-03-29T07:30:00Z", java.time.Instant.ofEpochMilli(row.startedAt).toString())
        assertEquals("2026-03-29T09:00:00Z", java.time.Instant.ofEpochMilli(requireNotNull(row.endedAt)).toString())

        val loaded = requireNotNull(sessions.session(id))
        assertEquals(started, loaded.startedAt)
        assertEquals(ended, loaded.endedAt)
        assertEquals("buena", row.notes)
    }

    @Test
    fun `una sesion abandonada conserva su instante de cierre`() = runBlocking {
        val zone = ZoneOffset.UTC
        val sessions = SessionRepository(db, zone = zone)
        val id = UUID.randomUUID().toString()

        sessions.start(Session(id = id, startedAt = local("2026-11-18T08:00:00")))
        sessions.abandon(id, endedAt = local("2026-11-18T08:05:00"))

        val loaded = requireNotNull(sessions.session(id))
        assertEquals(local("2026-11-18T08:05:00"), loaded.endedAt)
        assertNull(db.sessionDao().active())
    }

    @Test
    fun `session_set completedAt hace ida y vuelta y ordena`() = runBlocking {
        val zone = ZoneId.of("America/Argentina/Buenos_Aires")
        val sessions = SessionRepository(db, zone = zone)
        val sessionId = UUID.randomUUID().toString()
        sessions.start(Session(id = sessionId, startedAt = local("2026-11-18T19:00:00")))

        val first = local("2026-11-18T19:02:00")
        val second = local("2026-11-18T19:04:00")
        val firstId = sessions.recordSet(
            sessionId = sessionId,
            set = SessionSet(
                id = "",
                exerciseId = "pp",
                weightKg = 80.0,
                reps = 10,
                completedAt = first,
            ),
        )
        sessions.recordSet(
            sessionId = sessionId,
            set = SessionSet(
                id = "",
                exerciseId = "pp",
                weightKg = 82.5,
                reps = 8,
                completedAt = second,
                isDropSet = true,
            ),
        )

        val stored = db.sessionSetDao().ofSession(sessionId)
        assertEquals(listOf("2026-11-18T22:02:00Z", "2026-11-18T22:04:00Z"),
            stored.map { java.time.Instant.ofEpochMilli(it.completedAt).toString() })
        assertEquals(listOf(firstId, stored[1].id), stored.map { it.id })

        val loaded = requireNotNull(sessions.session(sessionId)).sets
        assertEquals(listOf(first, second), loaded.map { it.completedAt })
        assertEquals(listOf(80.0, 82.5), loaded.map { it.weightKg })
        assertEquals(listOf(false, true), loaded.map { it.isDropSet })
        assertEquals("pp", loaded.first().exerciseId)
        // `autoFillLast` (§8) usa la última serie de trabajo: la de las 19:04.
        assertEquals(second, sessions.lastWorkingSet("pp")?.completedAt)
    }
}
