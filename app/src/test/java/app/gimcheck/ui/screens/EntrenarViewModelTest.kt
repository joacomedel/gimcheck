package app.gimcheck.ui.screens

import app.gimcheck.core.DayItem
import app.gimcheck.core.Exercise
import app.gimcheck.core.GuidedSession
import app.gimcheck.core.Muscle
import app.gimcheck.core.Routine
import app.gimcheck.core.RoutineDay
import app.gimcheck.core.StartSession
import app.gimcheck.data.Instants
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.inMemoryDatabase
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.RoutineRepository
import app.gimcheck.data.repository.SessionRepository
import app.gimcheck.data.repository.SettingsRepository
import app.gimcheck.ui.components.formatRestClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * El ViewModel de **Entrenar** contra una base real (Robolectric, sin render), con
 * **reloj falso**: una sesión entera se recorre sin esperar un segundo.
 *
 * Los criterios del ticket que se pueden verificar en la JVM (§4):
 * 1. en una superserie de 2, la primera serie deja el cursor en el segundo ítem **sin
 *    descanso**;
 * 2. la segunda cierra la ronda y arranca descanso con el **máximo de los que
 *    participaron** (90 s de A contra 120 s de B → 120 s);
 * 3. `SkipRest` y `ExtendRest` se reflejan en lo que la pantalla muestra;
 * 4. corregir una serie **no mueve el cursor** (y sí cambia el dato guardado);
 * 5. A/B/A/B de dos rondas termina la sesión.
 *
 * Todo lo demás (píxeles, `TimerRing`, `keepScreenOn`) es review + teléfono
 * (`docs/diseno.md` §11): acá no hay emulador.
 */
@RunWith(RobolectricTestRunner::class)
class EntrenarViewModelTest {

    private val zone: ZoneId = ZoneOffset.UTC

    /** 2026-11-18 10:00:00 UTC. El reloj avanza a mano: [clock]. */
    private var clock: Instant = Instant.parse("2026-11-18T10:00:00Z")

    private lateinit var db: AppDatabase
    private lateinit var exercises: ExerciseRepository
    private lateinit var routines: RoutineRepository
    private lateinit var sessions: SessionRepository
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
        settings = SettingsRepository(db)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.stop()
        scope.cancel()
        db.close()
    }

    /**
     * Plan de prueba: bloque de superserie `[Press banca 90 s, Remo 120 s]` con [rounds]
     * rondas, sesión ya arrancada y ViewModel observando.
     */
    private suspend fun startSession(rounds: Int, gateway: RestGateway = RestGateway.Noop) {
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
                            DayItem(id = "i1", exercise = press, sets = rounds, supersetNext = true),
                            DayItem(id = "i2", exercise = row, sets = rounds),
                        ),
                    ),
                ),
            ),
        )
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
            restGateway = gateway,
        )
        viewModel.start()
    }

    private suspend fun awaitState(predicate: (EntrenarUiState) -> Boolean): EntrenarUiState =
        withTimeout(5_000) { viewModel.state.first { !it.loading && predicate(it) } }

    /** Anota la serie de A y la de B: deja el cursor en la ronda siguiente con descanso. */
    private suspend fun playUntilRest() {
        awaitState { it.exerciseName == "Press banca" }
        viewModel.logSetNow()
        awaitState { it.supersetPosition == 2 }
        viewModel.logSetNow()
        awaitState { it.rest != null }
    }

    /**
     * GIM-22: la pantalla no cuenta por su cuenta. Cuando la máquina abre el descanso, el
     * gateway recibe el **deadline absoluto** (`rest_ends_at`) y el total de la ronda; al
     * saltarlo recibe la cancelación. Que el servicio y la alarma respeten ese deadline
     * lo verifica `RestTimerGatewayTest`.
     */
    @Test
    fun `el cronometro de afuera recibe el deadline del descanso y su cancelacion`() = runBlocking {
        val gateway = RecordingRestGateway()
        startSession(rounds = 3, gateway = gateway)

        playUntilRest()

        val started = gateway.started.single()
        assertEquals("el total es el máximo de los que participaron", 120, started.second)
        assertEquals("el deadline es rest_ends_at", clock.plusSeconds(120), started.first)
        assertEquals(0, gateway.cancels)

        viewModel.skipRestNow()
        awaitState { it.rest == null }
        assertEquals(1, gateway.cancels)
    }

    /** Gateway de prueba: guarda el deadline y el total de cada arranque. */
    private class RecordingRestGateway : RestGateway {
        val started = mutableListOf<Pair<Instant, Int>>()
        var cancels: Int = 0
        var finished: Int = 0

        override fun onStartRest(seconds: Int, endsAt: Instant) {
            started += endsAt to seconds
        }

        override fun onCancelRest() {
            cancels++
        }

        override fun onRestFinished() {
            finished++
        }
    }

    @Test
    fun `la primera serie de la superserie salta al segundo item sin descanso`() = runBlocking {
        startSession(rounds = 3)

        val initial = awaitState { it.phase == EntrenarPhase.ACTIVE }
        assertEquals("Press banca", initial.exerciseName)
        assertEquals(1, initial.supersetPosition)
        assertEquals(2, initial.supersetSize)

        viewModel.logSetNow()

        val state = awaitState { it.supersetPosition == 2 }
        assertEquals("Remo", state.exerciseName)
        assertNull("dentro del bloque no hay descanso", state.rest)
        assertEquals(1, state.setsDone)
        // El cursor no se movió de ronda: sigue siendo la primera.
        assertEquals(1, state.round)
    }

    @Test
    fun `la segunda serie cierra la ronda y descansa el maximo de los que participaron`() = runBlocking {
        startSession(rounds = 3)

        playUntilRest()

        val state = awaitState { it.rest != null }
        val rest = requireNotNull(state.rest)
        // Los dos participan en la ronda 1: A descansa 90 s y B 120 s, gana el máximo.
        assertEquals(120, rest.totalSeconds)
        assertEquals(120, rest.remainingSeconds)
        assertEquals(2, state.round)
        assertEquals("Press banca", state.exerciseName)
        assertEquals(1, state.supersetPosition)
        assertEquals(2, state.setsDone)
    }

    @Test
    fun `saltar y extender el descanso se reflejan en la pantalla`() = runBlocking {
        startSession(rounds = 3)
        playUntilRest()

        viewModel.extendRestNow(15)
        val extended = awaitState { it.rest?.totalSeconds == 135 }
        assertEquals(135, requireNotNull(extended.rest).remainingSeconds)
        assertTrue("sigue en descanso", extended.rest != null)

        viewModel.skipRestNow()
        val skipped = awaitState { it.rest == null }
        assertTrue(skipped.canLogSet)
        assertEquals(2, skipped.round)
    }

    @Test
    fun `anotar durante el descanso lo cancela`() = runBlocking {
        startSession(rounds = 3)
        playUntilRest()

        viewModel.logSetNow()

        val state = awaitState { it.rest == null && it.exerciseName == "Remo" }
        assertEquals(3, state.setsDone)
        assertEquals(2, state.round)
    }

    @Test
    fun `corregir una serie no mueve el cursor`() = runBlocking {
        startSession(rounds = 3)
        playUntilRest()
        viewModel.skipRestNow()

        val before = awaitState { it.round == 2 && it.exerciseName == "Press banca" }
        val pressSets = before.sets.first { it.itemId == "i1" }.sets
        assertEquals(1, pressSets.size)
        val setId = pressSets.first().id
        assertEquals("20 kg", pressSets.first().weightLabel)

        viewModel.editSet(setId)
        viewModel.setEditingWeight(82.5)
        viewModel.setEditingReps(8.0)
        viewModel.saveCorrectionNow()

        val after = awaitState { state ->
            state.sets.any { group -> group.itemId == "i1" && group.sets.any { it.weightLabel == "82,5 kg" } }
        }
        // §4: corregir no mueve el cursor.
        assertEquals("Press banca", after.exerciseName)
        assertEquals(2, after.round)
        assertEquals(1, after.supersetPosition)
        assertNull(after.editingSetId)

        val stored = requireNotNull(sessions.session(SESSION_ID))
        val corrected = stored.sets.single { it.id == setId }
        assertEquals(82.5, corrected.weightKg, 1e-9)
        assertEquals(8, corrected.reps)
    }

    @Test
    fun `dos rondas de una superserie de 2 terminan la sesion`() = runBlocking {
        startSession(rounds = 2)

        awaitState { it.exerciseName == "Press banca" }
        viewModel.logSetNow()
        awaitState { it.supersetPosition == 2 }
        viewModel.logSetNow()
        awaitState { it.rest != null }

        viewModel.skipRestNow()
        awaitState { it.rest == null && it.round == 2 && it.exerciseName == "Press banca" }

        viewModel.logSetNow()
        awaitState { it.supersetPosition == 2 && it.round == 2 }
        viewModel.logSetNow()

        val finished = awaitState { it.phase == EntrenarPhase.FINISHED }
        assertEquals(4, finished.finishedSets)
        assertNull("la sesión quedó cerrada", sessions.observeActive().first())
    }

    @Test
    fun `el reloj del descanso usa minutos y segundos`() {
        assertEquals("1:30", formatRestClock(90))
        assertEquals("0:05", formatRestClock(5))
        assertEquals("0:00", formatRestClock(-3))
        assertEquals("10:00", formatRestClock(600))
    }

    private companion object {
        const val ROUTINE_ID = "routine-test"
        const val DAY_ID = "day-test"
        const val SESSION_ID = "session-test"
    }
}
