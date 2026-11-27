package app.gimcheck.ui.screens

import app.gimcheck.core.DayItem
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.inMemoryDatabase
import app.gimcheck.data.db.seedPlan
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.RoutineRepository
import app.gimcheck.data.repository.SettingsRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Comparar (GIM-19) por estado: dos planes, delta B−A, duración por día y series por
 * músculo. La verificación visual no existe acá; sí la aritmética, que es lo que se
 * puede romper sin que se note en una captura.
 */
@RunWith(RobolectricTestRunner::class)
class CompararViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var exercises: ExerciseRepository
    private lateinit var routines: RoutineRepository
    private lateinit var settings: SettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var viewModel: CompararViewModel

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDatabase()
        exercises = ExerciseRepository(db)
        routines = RoutineRepository(db)
        settings = SettingsRepository(db)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        db.seedPlan()
        viewModel = CompararViewModel(routines, exercises, settings, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun awaitState(condition: (CompararUiState) -> Boolean): CompararUiState = runBlocking {
        withTimeout(5_000) { viewModel.state.first(condition) }
    }

    @Test
    fun `con un solo plan muestra el estado vacio`() = runBlocking {
        viewModel.start()
        val state = awaitState { !it.loading }
        assertEquals(1, state.routines.size)
        assertTrue(state.metrics.isEmpty())
        assertTrue(state.days.isEmpty())
    }

    @Test
    fun `con dos planes compara series duracion y musculos con delta B menos A`() = runBlocking {
        val exercise = exercises.exercise("e1")!!
        val routineB = routines.createRoutine("Rutina B")
        val dayB = routines.addDay(routineB, "Día B")
        routines.addItem(
            dayId = dayB,
            item = DayItem(
                id = "b1",
                exercise = exercise,
                sets = 4,
                supersetNext = false,
            ),
        )
        viewModel.start()

        val state = awaitState { it.metrics.isNotEmpty() }
        assertEquals(2, state.routines.size)
        assertNotNull(state.aId)
        assertNotNull(state.bId)

        val series = state.metrics.first { it.label == "Series" }
        assertEquals("9", series.a)
        assertEquals("4", series.b)
        assertEquals("−5", series.delta)
        assertEquals(-1, series.deltaSign)

        assertEquals(1, state.days.size)
        assertTrue(state.days.all { it.delta.isNotEmpty() })

        val muscle = state.muscles.first { it.name == "Pecho" }
        assertEquals("9", muscle.aLabel)
        assertEquals("4", muscle.bLabel)
        assertTrue(muscle.aValue > muscle.bValue)
    }

    @Test
    fun `el toggle de indirectas y el cambio de plan se reflejan en el estado`() = runBlocking {
        val exercise = exercises.exercise("e1")!!
        val routineB = routines.createRoutine("Rutina B")
        val dayB = routines.addDay(routineB, "Día B")
        routines.addItem(dayB, DayItem(id = "b1", exercise = exercise, sets = 2))
        viewModel.start()
        val initial = awaitState { it.metrics.isNotEmpty() }

        viewModel.toggleIndirect()
        val toggled = awaitState { it.includeIndirect != initial.includeIndirect }
        assertTrue(toggled.includeIndirect)

        viewModel.selectB(initial.aId!!)
        val selected = awaitState { it.bId == initial.aId }
        assertEquals(initial.aId, selected.bId)
    }
}
