package app.gimcheck.ui.screens

import app.gimcheck.core.DayItem
import app.gimcheck.core.E1rmFormula
import app.gimcheck.core.Exercise
import app.gimcheck.core.GuidedSession
import app.gimcheck.core.Muscle
import app.gimcheck.core.Routine
import app.gimcheck.core.RoutineDay
import app.gimcheck.core.Session
import app.gimcheck.core.SessionSet
import app.gimcheck.core.StartSession
import app.gimcheck.core.e1rm
import app.gimcheck.core.formatDuration
import app.gimcheck.core.formatNumber
import app.gimcheck.core.sessionVolume
import app.gimcheck.data.Instants
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.SessionStatus as RowStatus
import app.gimcheck.data.db.inMemoryDatabase
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.MetricsRepository
import app.gimcheck.data.repository.RoutineRepository
import app.gimcheck.data.repository.SessionRepository
import app.gimcheck.data.repository.SessionResumer
import app.gimcheck.data.repository.SettingsRepository
import app.gimcheck.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * **Fin de sesión** (GIM-24) contra una base real (Robolectric) y reloj falso.
 *
 * Los criterios del ticket que se pueden verificar en la JVM:
 * 1. los números del resumen (duración, volumen, series) son **los que devuelve `:core`**
 *    sobre las series guardadas, no una segunda fórmula en la UI;
 * 2. una serie que supera el máximo previo marca PR y una que **empata** no;
 * 3. abandonar desde Entrenar deja la sesión `ABANDONED` con sus series y **sin** resumen;
 * 4. una sesión terminada aparece en "Últimos" de Hoy y una abandonada no.
 *
 * La pantalla no se renderiza en este equipo (`docs/build.md`): lo de abajo es estado.
 */
@RunWith(RobolectricTestRunner::class)
class EntrenarSummaryTest {

    private val zone: ZoneId = ZoneOffset.UTC

    /** 2026-11-18 10:00 UTC; cada serie avanza el reloj un minuto ([advance]). */
    private var clock: Instant = Instant.parse("2026-11-18T10:00:00Z")

    private lateinit var db: AppDatabase
    private lateinit var exercises: ExerciseRepository
    private lateinit var routines: RoutineRepository
    private lateinit var sessions: SessionRepository
    private lateinit var metrics: MetricsRepository
    private lateinit var settings: SettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var viewModel: EntrenarViewModel

    @Before
    fun setUp() = runBlocking {
        clock = Instant.parse("2026-11-18T10:00:00Z")
        db = inMemoryDatabase()
        exercises = ExerciseRepository(db)
        routines = RoutineRepository(db)
        sessions = SessionRepository(db, zone = zone, now = { clock })
        metrics = MetricsRepository(db, zone = zone)
        settings = SettingsRepository(db)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        savePlan()
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.stop()
        scope.cancel()
        db.close()
    }

    // --- 1. resumen = :core -----------------------------------------------------------

    @Test
    fun `el resumen del dia coincide con los calculos de core`() = runBlocking {
        startGuidedSession()
        playSession(press = 100.0, row = 105.0)

        val state = awaitState { it.summary != null }
        val summary = requireNotNull(state.summary)
        val stored = requireNotNull(sessions.session(SESSION_ID))
        val catalog = exercises.all()
        val core = AppSettings.CORE_DEFAULT

        assertEquals("Día A", summary.dayName)
        assertEquals("4", summary.setsLabel)
        assertEquals(stored.sets.count { !it.isWarmup }.toString(), summary.setsLabel)
        assertEquals(
            formatNumber(sessionVolume(stored, catalog, core)) + " kg",
            summary.volumeLabel,
        )
        assertEquals(
            "dos series de 5 reps por ejercicio: (500 + 525) × 2",
            2050.0,
            sessionVolume(stored, catalog, core),
            1e-9,
        )

        // Duración real: started_at (10:00) → ended_at (10:03).
        val ended = requireNotNull(stored.endedAt) { "la sesión tiene que quedar cerrada" }
        val seconds = Duration.between(
            stored.startedAt.toJavaLocalDateTime(),
            ended.toJavaLocalDateTime(),
        ).seconds
        assertEquals(formatDuration(seconds), summary.durationLabel)
        assertEquals("3 min", summary.durationLabel)

        // Sin histórico previo, la primera serie de cada ejercicio es PR (:core).
        assertEquals(2, summary.prs.size)
        assertEquals(setOf("Press banca", "Remo"), summary.prs.map { it.exerciseName }.toSet())

        // Top de músculos: las mismas series efectivas que `sessionVolumeByMuscle`.
        assertEquals(listOf("Pecho", "Espalda"), summary.muscles.map { it.name })
        assertEquals(listOf("2", "2"), summary.muscles.map { it.setsLabel })
        assertTrue(summary.muscles.all { it.fraction == 1f })
    }

    // --- 2. PR sí, empate no ----------------------------------------------------------

    @Test
    fun `una serie que rompe el maximo previo marca PR y un empate no`() = runBlocking {
        priorSession()
        startGuidedSession()
        playSession(press = 100.0, row = 110.0)

        val summary = requireNotNull(awaitState { it.summary != null }.summary)

        assertEquals(
            "Press banca empata 100×5 (116,67) y no es PR; Remo 110×5 (128,33) sí",
            listOf("Remo"),
            summary.prs.map { it.exerciseName },
        )
        val pr = summary.prs.single()
        assertEquals("110 kg × 5", pr.setLabel)
        assertEquals(
            formatNumber(requireNotNull(e1rm(110.0, 5, E1rmFormula.EPLEY))) + " kg",
            pr.e1rmLabel,
        )
    }

    // --- 3. abandonar ----------------------------------------------------------------

    @Test
    fun `abandonar deja la sesion ABANDONED con sus series y sin resumen`() = runBlocking {
        startGuidedSession()
        awaitState { it.phase == EntrenarPhase.ACTIVE }
        log(100.0)

        viewModel.abandonNow()

        assertEquals(EntrenarPhase.NO_SESSION, viewModel.state.value.phase)
        assertNull("una abandonada no tiene resumen de PRs", viewModel.state.value.summary)
        assertEquals(
            RowStatus.ABANDONED,
            requireNotNull(db.sessionDao().getById(SESSION_ID)).status,
        )
        assertEquals(
            "abandonar no borra las series",
            1,
            db.sessionSetDao().ofSession(SESSION_ID).size,
        )
        assertTrue("no es un entrenamiento", sessions.finishedHistory().isEmpty())
    }

    // --- 4. Hoy muestra la sesión terminada -------------------------------------------

    @Test
    fun `la sesion terminada aparece en los ultimos entrenamientos de Hoy`() = runBlocking {
        startGuidedSession()
        playSession(press = 100.0, row = 105.0)
        awaitState { it.summary != null }

        val hoy = HoyViewModel(
            routines = routines,
            exercises = exercises,
            sessions = sessions,
            metrics = metrics,
            settings = settings,
            resumer = SessionResumer(sessions, scope, now = { clock }),
            scope = scope,
            now = { Instants.fromEpochMillis(clock.toEpochMilli(), zone) },
            nowInstant = { clock },
        )
        hoy.start()
        try {
            val state = withTimeout(5_000) {
                hoy.state.first { !it.loading && it.recent.isNotEmpty() }
            }
            assertEquals(1, state.recent.size)
            assertEquals(4, state.recent.single().setCount)
            val stored = requireNotNull(sessions.session(SESSION_ID))
            assertEquals(
                formatNumber(sessionVolume(stored, exercises.all(), AppSettings.CORE_DEFAULT)) + " kg",
                state.recent.single().volumeLabel,
            )
        } finally {
            hoy.stop()
        }
    }

    @Test
    fun `una segunda finalizacion no reescribe la sesion`() = runBlocking {
        startGuidedSession()
        playSession(press = 100.0, row = 105.0)
        awaitState { it.summary != null }
        val ended = requireNotNull(db.sessionDao().getById(SESSION_ID)).endedAt
        val sets = db.sessionSetDao().ofSession(SESSION_ID).size

        // Un toque tardío sobre "Serie hecha" no puede cerrar dos veces.
        viewModel.logSetNow()

        assertEquals(ended, db.sessionDao().getById(SESSION_ID)?.endedAt)
        assertEquals(sets, db.sessionSetDao().ofSession(SESSION_ID).size)
        assertEquals(RowStatus.FINISHED, db.sessionDao().getById(SESSION_ID)?.status)
    }

    // --- andamiaje --------------------------------------------------------------------

    private suspend fun savePlan() {
        exercises.saveMuscle(Muscle(id = "m1", name = "Pecho"))
        exercises.saveMuscle(Muscle(id = "m2", name = "Espalda"))
        val press = Exercise(
            id = "e1",
            name = "Press banca",
            primaryMuscleId = "m1",
            secondsPerSet = 40,
            restSeconds = 90,
        )
        val row = Exercise(
            id = "e2",
            name = "Remo",
            primaryMuscleId = "m2",
            secondsPerSet = 40,
            restSeconds = 120,
        )
        exercises.saveExercise(press)
        exercises.saveExercise(row)
        routines.saveRoutine(
            Routine(
                id = ROUTINE_ID,
                name = "Plan A",
                days = listOf(
                    RoutineDay(
                        id = DAY_ID,
                        name = "Día A",
                        items = listOf(
                            DayItem(id = "i1", exercise = press, sets = 2, supersetNext = true),
                            DayItem(id = "i2", exercise = row, sets = 2),
                        ),
                    ),
                ),
            ),
        )
    }

    /** Sesión terminada de ayer: Press banca 100×5 y Remo 100×5 (116,67 de e1RM). */
    private suspend fun priorSession() {
        val startedAt = LocalDateTime(2026, 11, 17, 10, 0)
        sessions.start(
            Session(
                id = PRIOR_ID,
                startedAt = startedAt,
                routineId = ROUTINE_ID,
                routineDayId = DAY_ID,
            ),
        )
        sessions.recordSet(
            PRIOR_ID,
            SessionSet(
                id = "$PRIOR_ID/1",
                exerciseId = "e1",
                weightKg = 100.0,
                reps = 5,
                completedAt = LocalDateTime(2026, 11, 17, 10, 5),
            ),
        )
        sessions.recordSet(
            PRIOR_ID,
            SessionSet(
                id = "$PRIOR_ID/2",
                exerciseId = "e2",
                weightKg = 100.0,
                reps = 5,
                completedAt = LocalDateTime(2026, 11, 17, 10, 6),
            ),
        )
        sessions.finish(PRIOR_ID, LocalDateTime(2026, 11, 17, 11, 0))
    }

    private suspend fun startGuidedSession() {
        sessions.dispatch(
            state = GuidedSession(day = requireNotNull(routines.day(DAY_ID))),
            event = StartSession(sessionId = SESSION_ID, now = clock),
            now = clock,
        )
        viewModel = EntrenarViewModel(
            sessions = sessions,
            settings = settings,
            exercises = exercises,
            scope = scope,
            now = { Instants.fromEpochMillis(clock.toEpochMilli(), zone) },
            nowInstant = { clock },
        )
        viewModel.start()
    }

    /**
     * Recorre la superserie completa (2 rondas, 4 series) y termina la sesión.
     * Cada serie queda un minuto después de la anterior.
     */
    private suspend fun playSession(press: Double, row: Double) {
        awaitState { it.phase == EntrenarPhase.ACTIVE }
        log(press)
        log(row)
        viewModel.skipRestNow()
        log(press)
        log(row)
    }

    private suspend fun log(weight: Double) {
        viewModel.setWeight(weight)
        viewModel.setReps(5.0)
        viewModel.logSetNow()
        advance(60)
    }

    private fun advance(seconds: Long) {
        clock = clock.plusSeconds(seconds)
    }

    private suspend fun awaitState(predicate: (EntrenarUiState) -> Boolean): EntrenarUiState =
        withTimeout(5_000) { viewModel.state.first { !it.loading && predicate(it) } }

    private companion object {
        const val ROUTINE_ID = "routine-summary"
        const val DAY_ID = "day-summary"
        const val SESSION_ID = "session-summary"
        const val PRIOR_ID = "session-prior"
    }
}
