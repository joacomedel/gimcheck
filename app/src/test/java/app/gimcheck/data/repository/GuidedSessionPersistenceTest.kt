package app.gimcheck.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gimcheck.core.GuidedSession
import app.gimcheck.core.LogSet
import app.gimcheck.core.RoutineDay
import app.gimcheck.core.SessionEffect
import app.gimcheck.core.SessionStatus
import app.gimcheck.core.StartSession
import app.gimcheck.data.Instants
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.DAY_ID
import app.gimcheck.data.db.SessionEntity
import app.gimcheck.data.db.SessionStateEntity
import app.gimcheck.data.db.SessionStatus as RowStatus
import app.gimcheck.data.db.seedPlan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset

/**
 * Supervivencia del cursor al proceso muerto (§4, GIM-11).
 *
 * **Cómo se modela el "proceso muerto"**: la base es un **archivo** (`AppDatabase.build`,
 * `gimcheck.db`) y se cierra con `close()`; el segundo repositorio se construye sobre
 * una base **nueva**, abierta de cero. No queda ningún objeto en memoria compartido
 * entre las dos mitades: si el cursor no estuviera en `session_state`, el segundo
 * repositorio no tendría de dónde sacarlo. Una base `inMemory` no serviría para este
 * test porque sobrevive justamente a lo que hay que probar.
 *
 * El reloj tampoco se comparte: cada mitad recibe el `Instant` explícito, como hace un
 * test de la máquina. El deadline se verifica **exacto** (epoch millis), no aproximado.
 */
@RunWith(RobolectricTestRunner::class)
class GuidedSessionPersistenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 2026-11-18 10:00:00 UTC: cualquier instante fijo sirve. */
    private val t0: Instant = Instant.parse("2026-11-18T10:00:00Z")

    private fun local(at: Instant): LocalDateTime =
        Instants.fromEpochMillis(at.toEpochMilli(), ZoneOffset.UTC)

    private fun open(): AppDatabase = AppDatabase.build(context)

    private fun sessions(db: AppDatabase, clock: Instant) =
        SessionRepository(db, zone = ZoneOffset.UTC, now = { clock })

    private suspend fun plan(db: AppDatabase): RoutineDay = requireNotNull(RoutineRepository(db).day(DAY_ID))

    /**
     * Plan del test: `seedPlan` encadena `i1 → i2 → i3` en un solo bloque; acá se corta
     * en `i2` para tener un bloque de superserie `[i1, i2]` y `i3` suelto (§4).
     */
    private suspend fun seedTwoSetPlan(db: AppDatabase) {
        db.seedPlan()
        db.dayDao().setSupersetNext("i2", false)
    }

    /**
     * Sesión viva con dos series anotadas: la primera de `i1` (la superserie mueve el
     * cursor a `i2` sin descanso) y la segunda de `i2`, que cierra la ronda 0 y abre un
     * descanso de 90 s.
     */
    private suspend fun playTwoSets(db: AppDatabase): GuidedSession {
        val repo = sessions(db, t0)
        var transition = repo.dispatch(
            GuidedSession(day = plan(db)),
            StartSession(sessionId = SESSION_ID, now = t0),
            now = t0,
        )
        assertEquals(SessionStatus.RUNNING, transition.state.status)

        transition = repo.dispatch(
            transition.state,
            LogSet(weightKg = 80.0, reps = 10, at = local(t0.plusSeconds(60)), now = t0.plusSeconds(60)),
            now = t0.plusSeconds(60),
        )
        // Superserie: mismo bloque, siguiente ítem, sin descanso.
        assertEquals(SessionStatus.RUNNING, transition.state.status)
        assertEquals(1, transition.state.itemIndex)

        transition = repo.dispatch(
            transition.state,
            LogSet(weightKg = 40.0, reps = 12, at = local(t0.plusSeconds(120)), now = t0.plusSeconds(120)),
            now = t0.plusSeconds(120),
        )
        // Cierra la ronda 0 del bloque [i1, i2]: ronda 1, primer ítem, descanso de 90 s.
        assertEquals(SessionStatus.RESTING, transition.state.status)
        assertEquals(0, transition.state.blockIndex)
        assertEquals(1, transition.state.roundIndex)
        assertEquals(0, transition.state.itemIndex)
        assertEquals(90, transition.state.restSeconds)
        return transition.state
    }

    @Before
    fun setUp() {
        context.deleteDatabase(AppDatabase.FILE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(AppDatabase.FILE_NAME)
    }

    @Test
    fun `dos series, proceso muerto y el cursor y el deadline vuelven exactos`() = runBlocking {
        val first = open()
        seedTwoSetPlan(first)
        val beforeDeath = playTwoSets(first)
        val deadline = requireNotNull(beforeDeath.restEndsAt)
        assertEquals(t0.plusSeconds(210), deadline)

        // La fila persistida: deadline absoluto en millis, no una cuenta regresiva.
        val row = requireNotNull(first.sessionStateDao().get(SESSION_ID))
        assertEquals(deadline.toEpochMilli(), row.restEndsAt)
        assertEquals(1, row.roundIndex)
        assertEquals(0, row.itemIndex)
        assertEquals(90, row.restSeconds)
        assertEquals(t0.plusSeconds(120).toEpochMilli(), row.updatedAt)
        assertEquals(2, first.sessionSetDao().ofSession(SESSION_ID).size)
        first.close()

        // Proceso nuevo: base nueva, repositorio nuevo, ningún estado en memoria.
        val second = open()
        try {
            // 30 s después del último guardado: quedan 60 s de descanso.
            val repo = sessions(second, t0.plusSeconds(150))
            val resumed = repo.resumeActive(now = t0.plusSeconds(150)) as ActiveSession.Resumable

            assertEquals(SESSION_ID, resumed.state.sessionId)
            assertEquals(SessionStatus.RESTING, resumed.state.status)
            assertEquals(0, resumed.state.blockIndex)
            assertEquals(1, resumed.state.roundIndex)
            assertEquals(0, resumed.state.itemIndex)
            // El deadline es el mismo instante, no uno recalculado con "lo que faltaba".
            assertEquals(deadline, resumed.state.restEndsAt)
            assertEquals(90, resumed.state.restSeconds)
            assertEquals(60, resumed.remainingSeconds)
            assertFalse(resumed.restFinished)

            // Las series anotadas también sobreviven, con su posición y su orden.
            assertEquals(listOf("i1", "i2"), resumed.state.sets.map { it.itemId })
            assertEquals(listOf(80.0, 40.0), resumed.state.sets.map { it.set.weightKg })
            assertEquals(listOf(0, 0), resumed.state.sets.map { it.roundIndex })
            assertEquals(0, resumed.state.sets.map { it.blockIndex }.distinct().single())

            // Y el Flow de la pantalla viva reconstruye el mismo cursor.
            assertEquals(
                deadline,
                requireNotNull(repo.observeGuided().first()).restEndsAt,
            )
        } finally {
            second.close()
        }
    }

    @Test
    fun `deadline vencido al abrir deja el cursor intacto y avisa descanso terminado`() = runBlocking {
        val first = open()
        seedTwoSetPlan(first)
        val beforeDeath = playTwoSets(first)
        val deadline = requireNotNull(beforeDeath.restEndsAt)
        first.close()

        val second = open()
        try {
            // Un minuto después del deadline: la app estuvo cerrada todo el descanso.
            val reopenAt = deadline.plusSeconds(60)
            val repo = sessions(second, reopenAt)
            val resumed = repo.resumeActive(now = reopenAt) as ActiveSession.Resumable

            // "Descanso terminado" es el efecto de la máquina, no un estado paralelo.
            assertTrue(resumed.restFinished)
            assertNull(resumed.remainingSeconds)
            assertEquals(
                listOf(SessionEffect.PersistState, SessionEffect.RestFinished, SessionEffect.ShowRestDone),
                resumed.effects,
            )
            // El cursor no se movió: mismo bloque, misma ronda, mismo ítem.
            assertEquals(SessionStatus.RUNNING, resumed.state.status)
            assertNull(resumed.state.restEndsAt)
            assertEquals(0, resumed.state.blockIndex)
            assertEquals(1, resumed.state.roundIndex)
            assertEquals(0, resumed.state.itemIndex)
            assertEquals(2, resumed.state.sets.size)

            // Y quedó persistido sin deadline: reabrir otra vez no repite el aviso.
            val row = requireNotNull(second.sessionStateDao().get(SESSION_ID))
            assertNull(row.restEndsAt)
            assertEquals(0, row.blockIndex)
            assertEquals(1, row.roundIndex)
            val again = sessions(second, reopenAt.plusSeconds(30)).resumeActive(reopenAt.plusSeconds(30))
            assertFalse((again as ActiveSession.Resumable).restFinished)
        } finally {
            second.close()
        }
    }

    @Test
    fun `sin sesion ACTIVE la rehidratacion dice sin sesion`() = runBlocking {
        val db = open()
        try {
            assertEquals(ActiveSession.None, sessions(db, t0).resumeActive(now = t0))
            assertNull(sessions(db, t0).observeGuided().first())
        } finally {
            db.close()
        }
    }

    @Test
    fun `con dos filas ACTIVE gana la mas reciente, de forma determinista`() = runBlocking {
        val db = open()
        try {
            db.seedPlan()
            // Estado imposible por el camino normal (`startSession` lo prohíbe), pero
            // posible con datos viejos o un import: el desempate no puede depender del
            // orden que devuelva SQLite.
            db.sessionDao().upsert(
                SessionEntity(
                    id = "s-vieja",
                    routineId = "routine-1",
                    routineDayId = DAY_ID,
                    startedAt = t0.toEpochMilli(),
                    status = RowStatus.ACTIVE,
                ),
            )
            db.sessionDao().upsert(
                SessionEntity(
                    id = "s-nueva",
                    routineId = "routine-1",
                    routineDayId = DAY_ID,
                    startedAt = t0.plusSeconds(3600).toEpochMilli(),
                    status = RowStatus.ACTIVE,
                ),
            )
            db.sessionStateDao().upsert(
                SessionStateEntity(sessionId = "s-nueva", roundIndex = 2, itemIndex = 1, updatedAt = t0.toEpochMilli()),
            )

            val resumed = sessions(db, t0).resumeActive(now = t0) as ActiveSession.Resumable

            // La más reciente por `started_at`: la que el usuario estaba usando.
            assertEquals("s-nueva", resumed.state.sessionId)
            assertEquals(2, resumed.state.roundIndex)
            assertEquals(1, resumed.state.itemIndex)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val SESSION_ID = "s-gim-11"
    }
}
