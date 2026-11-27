package app.gimcheck.ui.screens

import app.gimcheck.core.DayItem
import app.gimcheck.core.E1rmFormula
import app.gimcheck.core.Exercise
import app.gimcheck.core.Muscle
import app.gimcheck.core.Routine
import app.gimcheck.core.RoutineDay
import app.gimcheck.core.Session
import app.gimcheck.core.SessionSet
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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

/**
 * **Fin de entrenamiento** (GIM-24, modelo libre de GIM-49) contra una base real
 * (Robolectric) y reloj falso.
 *
 * Criterios verificables en la JVM:
 * 1. los números del resumen (duración, volumen, series) son **los que devuelve `:core`**
 *    sobre las series guardadas;
 * 2. una serie que supera el máximo previo marca PR y una que **empata** no;
 * 3. descartar deja la sesión `ABANDONED` con sus series y **sin** resumen;
 * 4. el entrenamiento terminado aparece en "Últimos" de Hoy;
 * 5. un segundo "terminar" no reescribe la sesión.
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
        settings.setActiveRoutine(ROUTINE_ID)
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
        startTraining()
        play(press = 100.0, row = 105.0)
        viewModel.finishNow()

        val state = awaitState { it.summary != null }
        val summary = requireNotNull(state.summary)
        val stored = requireNotNull(sessions.session(state.sessionId))
        val catalog = exercises.all()
        val core = AppSettings.CORE_DEFAULT

        assertEquals("Plan A", summary.dayName)
        assertEquals("2", summary.setsLabel)
        assertEquals(stored.sets.count { !it.isWarmup }.toString(), summary.setsLabel)
        assertEquals(
            formatNumber(sessionVolume(stored, catalog, core)) + " kg",
            summary.volumeLabel,
        )
        assertEquals(
            "una serie de 5 reps por ejercicio: (500 + 525)",
            1025.0,
            sessionVolume(stored, catalog, core),
            1e-9,
        )

        // Duración real: started_at (10:00) → ended_at (10:02).
        val ended = requireNotNull(stored.endedAt) { "la sesión tiene que quedar cerrada" }
        val seconds = Duration.between(
            stored.startedAt.toJavaLocalDateTime(),
            ended.toJavaLocalDateTime(),
        ).seconds
        assertEquals(formatDuration(seconds), summary.durationLabel)
        assertEquals("2 min", summary.durationLabel)

        // Sin histórico previo, la primera serie de cada ejercicio es PR (:core).
        assertEquals(2, summary.prs.size)
        assertEquals(setOf("Press banca", "Remo"), summary.prs.map { it.exerciseName }.toSet())

        // Top de músculos: las mismas series efectivas que `sessionVolumeByMuscle`.
        assertEquals(listOf("Pecho", "Espalda"), summary.muscles.map { it.name })
        assertEquals(listOf("1", "1"), summary.muscles.map { it.setsLabel })
        assertTrue(summary.muscles.all { it.fraction == 1f })
    }

    // --- 2. PR sí, empate no ----------------------------------------------------------

    @Test
    fun `una serie que rompe el maximo previo marca PR y un empate no`() = runBlocking {
        priorSession()
        startTraining()
        play(press = 100.0, row = 110.0)
        viewModel.finishNow()

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

    // --- 3. descartar ----------------------------------------------------------------

    @Test
    fun `descartar deja la sesion ABANDONED con sus series y sin resumen`() = runBlocking {
        startTraining()
        awaitState { it.phase == EntrenarPhase.ACTIVE }
        log(100.0)
        awaitState { it.setsDone == 1 }
        val sessionId = viewModel.state.value.sessionId

        viewModel.abandonNow()

        assertEquals(EntrenarPhase.NO_SESSION, viewModel.state.value.phase)
        assertNull("una abandonada no tiene resumen de PRs", viewModel.state.value.summary)
        assertEquals(RowStatus.ABANDONED, requireNotNull(db.sessionDao().getById(sessionId)).status)
        assertEquals(
            "descartar no borra las series",
            1,
            db.sessionSetDao().ofSession(sessionId).size,
        )
        assertTrue("no es un entrenamiento", sessions.finishedHistory().isEmpty())
    }

    // --- 4. Hoy muestra el entrenamiento terminado ------------------------------------

    @Test
    fun `el entrenamiento terminado aparece en los ultimos entrenamientos de Hoy`() = runBlocking {
        startTraining()
        play(press = 100.0, row = 105.0)
        viewModel.finishNow()
        awaitState { it.summary != null }
        val sessionId = viewModel.state.value.sessionId

        val hoy = HoyViewModel(
            routines = routines,
            exercises = exercises,
            sessions = sessions,
            metrics = metrics,
            settings = settings,
            resumer = SessionResumer(sessions, scope, now = { clock }),
            scope = scope,
            now = { Instants.fromEpochMillis(clock.toEpochMilli(), zone) },
        )
        hoy.start()
        try {
            val state = withTimeout(5_000) {
                hoy.state.first { !it.loading && it.recent.isNotEmpty() }
            }
            assertEquals(1, state.recent.size)
            assertEquals(2, state.recent.single().setCount)
            val stored = requireNotNull(sessions.session(sessionId))
            assertEquals(
                formatNumber(sessionVolume(stored, exercises.all(), AppSettings.CORE_DEFAULT)) + " kg",
                state.recent.single().volumeLabel,
            )
        } finally {
            hoy.stop()
        }
    }

    @Test
    fun `un segundo terminar no reescribe la sesion`() = runBlocking {
        startTraining()
        play(press = 100.0, row = 105.0)
        viewModel.finishNow()
        awaitState { it.summary != null }
        val sessionId = viewModel.state.value.sessionId
        val ended = requireNotNull(db.sessionDao().getById(sessionId)).endedAt
        val sets = db.sessionSetDao().ofSession(sessionId).size

        // Un toque tardío sobre "Serie hecha" o "Terminar" no puede cerrar dos veces.
        viewModel.finishNow()
        viewModel.logSetNow()

        assertEquals(ended, db.sessionDao().getById(sessionId)?.endedAt)
        assertEquals(sets, db.sessionSetDao().ofSession(sessionId).size)
        assertEquals(RowStatus.FINISHED, db.sessionDao().getById(sessionId)?.status)
    }

    // --- andamiaje --------------------------------------------------------------------

    private suspend fun savePlan() {
        exercises.saveMuscle(Muscle(id = "m1", name = "Pecho"))
        exercises.saveMuscle(Muscle(id = "m2", name = "Espalda"))
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
                            DayItem(id = "i1", exercise = press, sets = 2),
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

    private suspend fun startTraining() {
        viewModel = EntrenarViewModel(
            sessions = sessions,
            settings = settings,
            exercises = exercises,
            routines = routines,
            scope = scope,
            now = { Instants.fromEpochMillis(clock.toEpochMilli(), zone) },
            nowInstant = { clock },
        )
        viewModel.start()
        awaitState { it.phase == EntrenarPhase.ACTIVE }
    }

    /** Una serie de 5 reps del ejercicio actual (Press) y después una de Remo. */
    private suspend fun play(press: Double, row: Double) {
        log(press)
        advance(60)
        viewModel.pickExercise("e2")
        awaitState { it.exerciseName == "Remo" }
        log(row)
        advance(60)
    }

    private suspend fun log(weight: Double) {
        viewModel.setWeight(weight)
        viewModel.setReps(5.0)
        viewModel.logSetNow()
        awaitState { state -> state.sets.any { group -> group.sets.any { it.weightLabel == formatNumber(weight) + " kg" } } }
    }

    private fun advance(seconds: Long) {
        clock = clock.plusSeconds(seconds)
    }

    private suspend fun awaitState(predicate: (EntrenarUiState) -> Boolean): EntrenarUiState =
        withTimeout(5_000) { viewModel.state.first { !it.loading && predicate(it) } }

    private companion object {
        const val ROUTINE_ID = "routine-summary"
        const val DAY_ID = "day-summary"
        const val PRIOR_ID = "session-prior"

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
    }
}
