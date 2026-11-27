package app.gimcheck.ui.screens

import app.gimcheck.core.Exercise
import app.gimcheck.core.LoadType
import app.gimcheck.core.Muscle
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.inMemoryDatabase
import app.gimcheck.data.db.seedPlan
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.RoutineRepository
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
 * Catálogo de ejercicios (GIM-18): alta, edición, duplicados, filtro y borrado con
 * aviso de uso. La verificación es de estado (Robolectric + Room), no de píxeles.
 */
@RunWith(RobolectricTestRunner::class)
class EjerciciosViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var exercises: ExerciseRepository
    private lateinit var routines: RoutineRepository
    private lateinit var scope: CoroutineScope
    private lateinit var viewModel: EjerciciosViewModel

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDatabase()
        exercises = ExerciseRepository(db)
        routines = RoutineRepository(db)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        db.seedPlan()
        viewModel = EjerciciosViewModel(exercises, routines, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun awaitState(condition: (EjerciciosUiState) -> Boolean): EjerciciosUiState = runBlocking {
        withTimeout(5_000) { viewModel.state.first(condition) }
    }

    @Test
    fun `el catalogo se lista con el uso en las rutinas`() = runBlocking {
        viewModel.start()
        val state = awaitState { it.rows.size == 3 }
        assertEquals(3, state.allCount)
        assertEquals("e1", state.rows.first().name)
        assertEquals("en 1 lugar", state.rows.first().usageLabel)
        assertEquals("Pecho", state.rows.first().muscleName)
    }

    @Test
    fun `crea un ejercicio con los valores del editor`() = runBlocking {
        viewModel.start()
        awaitState { it.rows.size == 3 }

        viewModel.startCreate()
        viewModel.setName("Press banca")
        viewModel.setPrimaryMuscle("m1")
        viewModel.setSecondsPerSet(45.0)
        viewModel.setRestSeconds(120.0)
        viewModel.setCompound(true)
        viewModel.setLoadType(LoadType.EXTERNAL)
        viewModel.save()

        val state = awaitState { it.rows.any { row -> row.name == "Press banca" } && it.editing == null }
        val row = state.rows.first { it.name == "Press banca" }
        assertNull(state.editing)
        assertEquals(4, state.allCount)
        assertEquals("multiarticular", if (row.compound) "multiarticular" else "aislado")
        val saved = exercises.all().first { it.name == "Press banca" }
        assertEquals(45, saved.secondsPerSet)
        assertEquals(120, saved.restSeconds)
    }

    @Test
    fun `no permite dos ejercicios con el mismo nombre`() = runBlocking {
        viewModel.start()
        awaitState { it.rows.size == 3 }

        viewModel.startCreate()
        viewModel.setName("e1")
        viewModel.setPrimaryMuscle("m1")
        viewModel.save()

        val state = awaitState { it.editing?.error == EjercicioError.NOMBRE_DUPLICADO }
        assertEquals(EjercicioError.NOMBRE_DUPLICADO, state.editing?.error)
    }

    @Test
    fun `editar cambia el nombre y el descanso`() = runBlocking {
        viewModel.start()
        awaitState { it.rows.size == 3 }

        viewModel.startEdit("e1")
        viewModel.setName("Sentadilla")
        viewModel.setRestSeconds(180.0)
        viewModel.save()

        val state = awaitState { it.rows.any { row -> row.name == "Sentadilla" } && it.editing == null }
        assertNull(state.editing)
        val saved = exercises.exercise("e1")!!
        assertEquals("Sentadilla", saved.name)
        assertEquals(180, saved.restSeconds)
    }

    @Test
    fun `el filtro por musculo y la busqueda se combinan`() = runBlocking {
        exercises.saveExercise(
            Exercise(
                id = "e9",
                name = "Remo",
                primaryMuscleId = "m2",
                secondsPerSet = 40,
                restSeconds = 90,
            ),
        )
        viewModel.start()
        awaitState { it.rows.size == 4 }

        viewModel.setQuery("rem")
        var state = awaitState { it.rows.size == 1 }
        assertEquals("e9", state.rows.single().id)

        viewModel.selectMuscle("m1")
        state = awaitState { it.rows.isEmpty() }
        assertTrue(state.rows.isEmpty())

        viewModel.clearFilters()
        state = awaitState { it.rows.size == 4 }
        assertEquals("", state.query)
        assertNull(state.filterMuscleId)
    }

    @Test
    fun `borrar un ejercicio lo saca de los dias`() = runBlocking {
        viewModel.start()
        awaitState { it.rows.size == 3 }

        viewModel.startEdit("e1")
        viewModel.askDelete()
        assertEquals(true, viewModel.state.value.editing?.confirmingDelete)
        viewModel.delete()

        val state = awaitState { it.rows.none { row -> row.id == "e1" } }
        assertEquals(2, state.allCount)
        assertNull(exercises.exercise("e1"))
        val day = routines.day("day-1")!!
        assertTrue(day.items.none { it.exerciseId == "e1" })
    }

    @Test
    fun `agregar un musculo no permite duplicados`() = runBlocking {
        viewModel.start()
        awaitState { it.muscles.isNotEmpty() }

        viewModel.setNewMuscleName("Cuádriceps")
        viewModel.addMuscle()
        var state = awaitState { it.muscles.any { muscle -> muscle.name == "Cuádriceps" } }
        assertTrue(state.muscles.any { it.name == "Cuádriceps" })

        viewModel.setNewMuscleName("cuádriceps")
        viewModel.addMuscle()
        state = awaitState { it.muscleError == EjercicioError.NOMBRE_DUPLICADO }
        assertEquals(EjercicioError.NOMBRE_DUPLICADO, state.muscleError)
        assertEquals(1, exercises.muscles().count { it.name.equals("Cuádriceps", ignoreCase = true) })
    }

    @Test
    fun `los secundarios se agregan con factor y no aceptan el principal`() = runBlocking {
        exercises.saveMuscle(Muscle(id = "m9", name = "Bíceps", position = 9))
        viewModel.start()
        awaitState { it.rows.size == 3 }

        viewModel.startEdit("e1")
        viewModel.openSecondaryPicker()
        viewModel.addSecondary("m9")
        viewModel.addSecondary("m1")
        val state = awaitState { it.editing?.secondaries?.any { s -> s.muscleId == "m9" } == true }
        assertEquals(1, state.editing!!.secondaries.size)
        assertEquals(EjercicioError.MUSCULO_DUPLICADO, viewModel.state.value.editing?.error)

        viewModel.setSecondaryFactor("m9", 0.7)
        assertNotNull(viewModel.state.value.editing?.secondaries?.first { it.muscleId == "m9" })
        viewModel.save()
        val saved = exercises.exercise("e1")!!
        assertEquals(0.7, saved.secondaries.single().factor, 1e-9)
    }
}
