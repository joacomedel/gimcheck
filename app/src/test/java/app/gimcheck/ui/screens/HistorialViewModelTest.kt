package app.gimcheck.ui.screens

import app.gimcheck.core.Session
import app.gimcheck.core.SessionSet
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.DAY_ID
import app.gimcheck.data.db.ROUTINE_ID
import app.gimcheck.data.db.inMemoryDatabase
import app.gimcheck.data.db.seedPlan
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.RoutineRepository
import app.gimcheck.data.repository.SessionRepository
import app.gimcheck.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

/**
 * Historial (GIM-25) verificado por estado, no por píxeles: lista por semana ISO,
 * detalle, corrección de una serie (el volumen se recalcula) y borrado.
 *
 * Es la verificación que se puede correr en la JVM; la visual la hace el dueño en el
 * teléfono (`docs/build.md` no tiene emulador).
 */
@RunWith(RobolectricTestRunner::class)
class HistorialViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var routines: RoutineRepository
    private lateinit var exercises: ExerciseRepository
    private lateinit var sessions: SessionRepository
    private lateinit var settings: SettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var viewModel: HistorialViewModel

    private val now = LocalDateTime(2026, 11, 18, 20, 0)

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDatabase()
        routines = RoutineRepository(db)
        exercises = ExerciseRepository(db)
        sessions = SessionRepository(db, zone = ZoneOffset.UTC)
        settings = SettingsRepository(db)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        db.seedPlan()
        viewModel = HistorialViewModel(
            routines = routines,
            exercises = exercises,
            sessions = sessions,
            settings = settings,
            scope = scope,
            now = { now },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private suspend fun seedSession() {
        sessions.start(
            Session(
                id = "s1",
                startedAt = LocalDateTime(2026, 11, 18, 9, 0),
                endedAt = null,
                routineId = ROUTINE_ID,
                routineDayId = DAY_ID,
            ),
        )
        sessions.recordSet(
            sessionId = "s1",
            set = SessionSet(
                id = "set1",
                exerciseId = "e1",
                weightKg = 100.0,
                reps = 5,
                completedAt = LocalDateTime(2026, 11, 18, 9, 5),
            ),
            dayItemId = "i1",
        )
        sessions.finish("s1", LocalDateTime(2026, 11, 18, 10, 0))
    }

    private fun awaitState(condition: (HistorialUiState) -> Boolean): HistorialUiState = runBlocking {
        withTimeout(5_000) { viewModel.state.first(condition) }
    }

    @Test
    fun `lista la sesion por semana ISO con duracion volumen y nombre del dia`() = runBlocking {
        seedSession()
        viewModel.start()

        val state = awaitState { it.weeks.isNotEmpty() }
        assertEquals(1, state.weeks.size)
        assertEquals("16–22 nov", state.weeks.single().label)
        val row = state.weeks.single().sessions.single()
        assertEquals("Día 1", row.title)
        assertEquals("500 kg", row.volumeLabel)
        assertTrue("la duración se muestra en la fila: ${row.subtitle}", row.subtitle.contains("1 h"))
    }

    @Test
    fun `el detalle agrupa por ejercicio y corrige una serie recalculando el volumen`() = runBlocking {
        seedSession()
        viewModel.start()
        awaitState { it.weeks.isNotEmpty() }

        viewModel.openSession("s1")
        val detail = awaitState { it.detail != null }.detail!!
        assertEquals(1, detail.exercises.size)
        assertEquals("e1", detail.exercises.single().name)
        assertEquals("500 kg", detail.volumeLabel)

        viewModel.startEditSet("set1")
        viewModel.setEditWeight(110.0)
        viewModel.saveSetEdit()

        val updated = awaitState { it.detail?.volumeLabel == "550 kg" }.detail!!
        assertEquals("550 kg", updated.volumeLabel)
        assertEquals(110.0, sessions.session("s1")!!.sets.single().weightKg, 1e-9)
    }

    @Test
    fun `borrar la sesion la saca de la lista y cierra el detalle`() = runBlocking {
        seedSession()
        viewModel.start()
        awaitState { it.weeks.isNotEmpty() }

        viewModel.openSession("s1")
        awaitState { it.detail != null }
        viewModel.askDeleteSession()
        viewModel.deleteSession()

        val empty = awaitState { it.weeks.isEmpty() && it.detail == null }
        assertNull(empty.detail)
        assertNull(sessions.session("s1"))
    }

    @Test
    fun `borrar una serie deja la sesion sin series pero no la borra`() = runBlocking {
        seedSession()
        viewModel.start()
        awaitState { it.weeks.isNotEmpty() }
        viewModel.openSession("s1")
        awaitState { it.detail != null }

        viewModel.startEditSet("set1")
        viewModel.askDeleteSet()
        viewModel.deleteSet()

        val detail = awaitState { it.detail?.exercises?.isEmpty() == true }.detail!!
        assertTrue(detail.exercises.isEmpty())
        assertTrue(sessions.session("s1") != null)
    }
}
