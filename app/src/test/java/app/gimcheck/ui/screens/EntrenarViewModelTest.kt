package app.gimcheck.ui.screens

import app.gimcheck.core.DayItem
import app.gimcheck.core.Exercise
import app.gimcheck.core.Muscle
import app.gimcheck.core.Routine
import app.gimcheck.core.RoutineDay
import app.gimcheck.data.Instants
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.SessionStatus as RowStatus
import app.gimcheck.data.db.inMemoryDatabase
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.RoutineRepository
import app.gimcheck.data.repository.SessionRepository
import app.gimcheck.data.repository.SettingsRepository
import app.gimcheck.ui.components.formatRestClock
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * El ViewModel de **Entrenar** en el modelo de entrenamiento libre (GIM-49), contra una
 * base real (Robolectric) y **reloj falso**.
 *
 * Criterios verificables en la JVM:
 * 1. entrar sin sesión crea el entrenamiento solo y muestra el primer ejercicio de la
 *    rutina activa;
 * 2. anotar una serie la guarda y **se queda** en el mismo ejercicio;
 * 3. si el ejercicio es el primero de una superserie, salta **una vez** al siguiente sin
 *    descanso y al anotar allí no vuelve solo;
 * 4. el selector lista la rutina activa y `+` agrega todas las rutinas; elegir un
 *    ejercicio de otra rutina loguea con ese `exercise_id`;
 * 5. saltar/extender el descanso se reflejan en pantalla;
 * 6. terminar cierra la sesión; corregir una serie no cambia de ejercicio.
 *
 * Píxeles y `keepScreenOn` son review + teléfono (`docs/diseno.md` §11).
 */
@RunWith(RobolectricTestRunner::class)
class EntrenarViewModelTest {

    private val zone: ZoneId = ZoneOffset.UTC

    /** 2026-11-18 10:00:00 UTC. El reloj avanza a mano ([advance]). */
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
        saveCatalog()
        settings.setActiveRoutine(ROUTINE_ID)
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.stop()
        scope.cancel()
        db.close()
    }

    private suspend fun saveCatalog() {
        exercises.saveMuscle(Muscle(id = "m1", name = "Pecho"))
        exercises.saveMuscle(Muscle(id = "m2", name = "Espalda"))
        exercises.saveMuscle(Muscle(id = "m3", name = "Piernas"))
        exercises.saveExercise(press)
        exercises.saveExercise(forearm)
        exercises.saveExercise(row)
        exercises.saveExercise(squat)
        routines.saveRoutine(
            Routine(
                id = ROUTINE_ID,
                name = "Rutina activa",
                days = listOf(
                    day("d1", "Día 1", DayItem("i1", press, 3, supersetNext = true), DayItem("i2", forearm, 3)),
                    day("d2", "Día 2", DayItem("i3", row, 3)),
                ),
            ),
        )
        routines.saveRoutine(
            Routine(
                id = OTHER_ROUTINE_ID,
                name = "Otra rutina",
                days = listOf(day("d3", "Día 1", DayItem("i4", squat, 3))),
            ),
        )
    }

    private fun day(id: String, name: String, vararg items: DayItem) =
        RoutineDay(id = id, name = name, items = items.toList())

    private fun startViewModel(gateway: RestGateway = RestGateway.Noop) {
        viewModel = EntrenarViewModel(
            sessions = sessions,
            settings = settings,
            exercises = exercises,
            routines = routines,
            scope = scope,
            now = { Instants.fromEpochMillis(clock.toEpochMilli(), zone) },
            nowInstant = { clock },
            restGateway = gateway,
        )
        viewModel.start()
    }

    private suspend fun awaitState(predicate: (EntrenarUiState) -> Boolean): EntrenarUiState =
        withTimeout(5_000) { viewModel.state.first { !it.loading && predicate(it) } }

    private suspend fun log(weight: Double = 60.0, reps: Double = 5.0) {
        viewModel.setWeight(weight)
        viewModel.setReps(reps)
        viewModel.logSetNow()
    }

    private fun advance(seconds: Long) {
        clock = clock.plusSeconds(seconds)
    }

    // --- 1. entrenamiento abierto ------------------------------------------------------

    @Test
    fun `entrar sin sesion crea el entrenamiento y muestra el primer ejercicio`() = runBlocking {
        startViewModel()

        val state = awaitState { it.phase == EntrenarPhase.ACTIVE }
        assertEquals("Press plano", state.exerciseName)
        assertEquals("Rutina activa", state.routineName)
        assertEquals(0, state.setsDone)

        val active = withTimeout(5_000) { sessions.observeActive().first { it != null } }
        assertNotNull(active)
        assertEquals("la rutina activa queda asociada al entrenamiento", ROUTINE_ID, active?.routineId)
        assertNull("el entrenamiento no recorre un día del plan", active?.routineDayId)
    }

    @Test
    fun `anotar una serie la guarda y se queda en el mismo ejercicio`() = runBlocking {
        startViewModel()
        awaitState { it.phase == EntrenarPhase.ACTIVE }
        // Remo no es superserie: anotar tiene que quedarse ahí y abrir descanso.
        viewModel.pickExercise("e3")
        awaitState { it.exerciseName == "Remo" }

        log(weight = 62.5, reps = 8.0)

        val state = awaitState { it.setsDone == 1 }
        assertEquals("Remo", state.exerciseName)
        assertNull("un ejercicio suelto no salta", state.jumpTo)
        assertNotNull("el descanso del ejercicio arranca al anotar", state.rest)
        assertEquals(120, state.rest?.totalSeconds)

        val stored = requireNotNull(sessions.observeActive().first())
        val set = stored.sets.single()
        assertEquals("e3", set.exerciseId)
        assertEquals(62.5, set.weightKg, 1e-9)
        assertEquals(8, set.reps)
    }

    // --- 2. superserie como salto puntual ----------------------------------------------

    @Test
    fun `la superserie salta una vez al siguiente sin descanso y no vuelve`() = runBlocking {
        startViewModel()
        val initial = awaitState { it.phase == EntrenarPhase.ACTIVE }
        assertEquals("Antebrazo", initial.supersetNextName)

        log()

        val jumped = awaitState { it.exerciseName == "Antebrazo" }
        assertNull("dentro de la superserie no hay descanso", jumped.rest)
        assertEquals("Antebrazo", jumped.jumpTo)
        assertEquals(1, jumped.setsDone)

        log()

        val second = awaitState { it.setsDone == 2 && it.rest != null }
        assertEquals("anotar en antebrazo no vuelve al press", "Antebrazo", second.exerciseName)
        assertNull(second.jumpTo)
        assertEquals(60, second.rest?.totalSeconds)

        viewModel.skipRestNow()
        awaitState { it.rest == null }
        log()

        val third = awaitState { it.setsDone == 3 }
        assertEquals("después del descanso sigue en antebrazo", "Antebrazo", third.exerciseName)
    }

    @Test
    fun `el primer ejercicio de la rutina activa sin superserie no salta`() = runBlocking {
        // La segunda rutina no se activa: el primer ejercicio del seed de prueba sigue
        // siendo Press plano, así que se elige Remo (suelto en el Día 2).
        startViewModel()
        awaitState { it.phase == EntrenarPhase.ACTIVE }
        viewModel.pickExercise("e3")
        awaitState { it.exerciseName == "Remo" }

        log()

        val state = awaitState { it.setsDone == 1 }
        assertEquals("Remo", state.exerciseName)
        assertNull(state.supersetNextName)
        assertNotNull(state.rest)
    }

    // --- 3. selector --------------------------------------------------------------------

    @Test
    fun `el selector lista la rutina activa y con ver todas tambien las otras`() = runBlocking {
        startViewModel()
        awaitState { it.phase == EntrenarPhase.ACTIVE }

        viewModel.openPicker()
        val active = awaitState { it.picker != null }
        val short = requireNotNull(active.picker)
        assertTrue("arranca en la rutina activa", !short.showingAll)
        assertTrue(short.groups.all { it.routineName == "Rutina activa" })
        assertTrue(short.groups.flatMap { it.items }.any { it.name == "Press plano" })
        assertTrue(short.groups.flatMap { it.items }.none { it.name == "Sentadilla" })

        viewModel.showAllRoutines()
        val all = requireNotNull(awaitState { it.picker?.showingAll == true }.picker)
        assertTrue(all.groups.any { it.routineName == "Otra rutina" })
        assertTrue(all.groups.flatMap { it.items }.any { it.name == "Sentadilla" })
    }

    @Test
    fun `elegir un ejercicio de otra rutina lo deja actual y loguea con ese id`() = runBlocking {
        startViewModel()
        awaitState { it.phase == EntrenarPhase.ACTIVE }

        viewModel.openPicker()
        awaitState { it.picker != null }
        viewModel.showAllRoutines()
        awaitState { it.picker?.showingAll == true }
        viewModel.pickExercise("e4")

        val picked = awaitState { it.exerciseName == "Sentadilla" }
        assertNull("elegir cierra el selector", picked.picker)
        assertEquals("el ejercicio se recuerda para la próxima apertura", "e4", settings.trainingExercise())

        log(weight = 80.0)

        val state = awaitState { it.setsDone == 1 }
        assertEquals("Sentadilla", state.exerciseName)
        assertEquals("e4", requireNotNull(sessions.observeActive().first()).sets.single().exerciseId)
    }

    // --- 4. descanso --------------------------------------------------------------------

    @Test
    fun `saltar y extender el descanso se reflejan en la pantalla`() = runBlocking {
        startViewModel()
        awaitState { it.phase == EntrenarPhase.ACTIVE }
        viewModel.pickExercise("e3")
        awaitState { it.exerciseName == "Remo" }
        log()
        awaitState { it.rest != null }

        viewModel.extendRestNow(15)
        val extended = awaitState { it.rest?.totalSeconds == 135 }
        assertEquals(135, requireNotNull(extended.rest).remainingSeconds)

        viewModel.skipRestNow()
        val skipped = awaitState { it.rest == null }
        assertTrue(skipped.canLogSet)
    }

    @Test
    fun `el descanso se retoma al reabrir el entrenamiento`() = runBlocking {
        startViewModel()
        awaitState { it.phase == EntrenarPhase.ACTIVE }
        viewModel.pickExercise("e3")
        awaitState { it.exerciseName == "Remo" }
        log()
        awaitState { it.rest != null }
        viewModel.stop()

        // Otra instancia, como si se hubiera cerrado la app: misma base y mismos ajustes.
        advance(30)
        startViewModel()
        val reopened = awaitState { it.phase == EntrenarPhase.ACTIVE && it.rest != null }
        assertEquals(90, reopened.rest?.remainingSeconds)
        assertEquals(120, reopened.rest?.totalSeconds)
    }

    @Test
    fun `un entrenamiento abierto de ayer se cierra solo al volver`() = runBlocking {
        startViewModel()
        awaitState { it.phase == EntrenarPhase.ACTIVE }
        log()
        awaitState { it.setsDone == 1 }
        val previous = viewModel.state.value.sessionId
        viewModel.stop()

        advance(24L * 60 * 60)
        startViewModel()

        val state = awaitState { it.phase == EntrenarPhase.ACTIVE && it.sessionId != previous }
        assertEquals("el entrenamiento de hoy arranca vacío", 0, state.setsDone)
        assertEquals(
            "el de ayer queda cerrado con su última serie",
            RowStatus.FINISHED,
            requireNotNull(db.sessionDao().getById(previous)).status,
        )
    }

    // --- 5. corregir y terminar ---------------------------------------------------------

    @Test
    fun `corregir una serie no cambia el ejercicio actual`() = runBlocking {
        startViewModel()
        awaitState { it.phase == EntrenarPhase.ACTIVE }
        viewModel.pickExercise("e3")
        awaitState { it.exerciseName == "Remo" }
        log(weight = 60.0)
        val logged = awaitState { it.setsDone == 1 }
        val setId = requireNotNull(logged.sets.firstOrNull()?.sets?.firstOrNull()).id

        viewModel.editSet(setId)
        viewModel.setEditingWeight(82.5)
        viewModel.setEditingReps(8.0)
        viewModel.saveCorrectionNow()

        val after = awaitState { state ->
            state.sets.any { group -> group.sets.any { it.weightLabel == "82,5 kg" } }
        }
        assertEquals("Remo", after.exerciseName)
        assertNull(after.editingSetId)
    }

    @Test
    fun `terminar cierra la sesion y calcula el resumen`() = runBlocking {
        startViewModel()
        awaitState { it.phase == EntrenarPhase.ACTIVE }
        log(weight = 100.0)
        awaitState { it.setsDone == 1 }
        advance(60)

        viewModel.finishNow()

        val finished = awaitState { it.phase == EntrenarPhase.FINISHED }
        assertEquals(1, finished.finishedSets)
        assertNotNull("el resumen se calcula con :core", finished.summary)
        assertNull("al terminar ya no hay sesión abierta", sessions.observeActive().first())
        val closed = requireNotNull(sessions.session(finished.sessionId))
        assertEquals(RowStatus.FINISHED, db.sessionDao().getById(closed.id)?.status)
        assertNotNull(closed.endedAt)
    }

    @Test
    fun `un toque tardio despues de terminar no agrega series`() = runBlocking {
        startViewModel()
        awaitState { it.phase == EntrenarPhase.ACTIVE }
        log()
        awaitState { it.setsDone == 1 }
        viewModel.finishNow()
        awaitState { it.phase == EntrenarPhase.FINISHED }
        val sessionId = viewModel.state.value.sessionId

        log()

        assertEquals(1, requireNotNull(sessions.session(sessionId)).sets.size)
    }

    // --- reloj --------------------------------------------------------------------------

    @Test
    fun `el reloj del descanso usa minutos y segundos`() {
        assertEquals("1:30", formatRestClock(90))
        assertEquals("0:05", formatRestClock(5))
        assertEquals("0:00", formatRestClock(-3))
        assertEquals("10:00", formatRestClock(600))
    }

    private companion object {
        const val ROUTINE_ID = "routine-test"
        const val OTHER_ROUTINE_ID = "routine-other"

        val press = Exercise(
            id = "e1",
            name = "Press plano",
            primaryMuscleId = "m1",
            secondsPerSet = 40,
            restSeconds = 90,
        )
        val forearm = Exercise(
            id = "e2",
            name = "Antebrazo",
            primaryMuscleId = "m1",
            secondsPerSet = 30,
            restSeconds = 60,
        )
        val row = Exercise(
            id = "e3",
            name = "Remo",
            primaryMuscleId = "m2",
            secondsPerSet = 40,
            restSeconds = 120,
        )
        val squat = Exercise(
            id = "e4",
            name = "Sentadilla",
            primaryMuscleId = "m3",
            secondsPerSet = 40,
            restSeconds = 120,
        )
    }
}
