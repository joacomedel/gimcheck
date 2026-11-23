package app.gimcheck.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gimcheck.core.dayStats
import app.gimcheck.core.exportPlan
import app.gimcheck.core.formatDuration
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.inMemoryDatabase
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.RoutineRepository
import app.gimcheck.data.repository.SettingsRepository
import app.gimcheck.data.seed.SeedImporter
import app.gimcheck.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * El ViewModel de **Rutinas** contra el seed real, sin render (no hay emulador,
 * `docs/build.md` §11).
 *
 * Criterios de GIM-16 que se verifican acá:
 * 1. la lista de planes marca el activo y sus totales son `dayStats` sumado, no una
 *    segunda fórmula;
 * 2. crear deja un plan vacío que aparece en la lista y queda activo;
 * 3. duplicar copia días e ítems con ids nuevos y la copia queda seleccionada;
 * 4. renombrar se refleja por `Flow`;
 * 5. el último plan no se puede borrar (la UI no lo ofrece);
 * 6. exportar produce el JSON de la web, idéntico al de `exportPlan` de `:core`.
 */
@RunWith(RobolectricTestRunner::class)
class RutinasViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val seedJson: String by lazy {
        context.assets.open("seed.json").bufferedReader().use { it.readText() }
    }

    private lateinit var db: AppDatabase
    private lateinit var routines: RoutineRepository
    private lateinit var exercises: ExerciseRepository
    private lateinit var settings: SettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var viewModel: RutinasViewModel

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDatabase()
        routines = RoutineRepository(db)
        exercises = ExerciseRepository(db)
        settings = SettingsRepository(db)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        SeedImporter(db).seedIfEmpty(seedJson)
        viewModel = RutinasViewModel(routines, exercises, settings, scope)
        viewModel.start()
    }

    @After
    fun tearDown() {
        viewModel.stop()
        scope.cancel()
        db.close()
    }

    private suspend fun loadedState(): RutinasUiState =
        withTimeout(5_000) { viewModel.state.first { !it.loading } }

    @Test
    fun `con el seed el plan activo tiene los totales de dayStats y el detalle del primer dia`() =
        runBlocking {
            val state = loadedState()
            val all = routines.observeRoutines().first()
            val routine = all.first { it.id == state.selectedPlanId }
            val core = AppSettings.CORE_DEFAULT

            assertTrue(state.hasPlans)
            assertEquals("el seed trae las dos rutinas del dueño", 2, state.plans.size)
            val plan = state.plans.single { it.active }
            assertTrue("el plan del seed es el activo", plan.active)
            assertEquals("Rutina A (original)", plan.name)
            assertTrue(
                "la segunda rutina del backup también está",
                state.plans.any { it.name == "Rutina B (más multiarticulares)" },
            )
            assertEquals(routine.days.size, plan.daysCount)
            assertEquals(
                routine.days.sumOf { dayStats(it.items, core).sets }.toString(),
                plan.setsLabel,
            )
            assertEquals(
                formatDuration(routine.days.sumOf { dayStats(it.items, core).totalSeconds }),
                plan.durationLabel,
            )
            assertTrue("con dos planes el borrado está disponible", state.canDeletePlan)

            val day = requireNotNull(state.day)
            val firstDay = routine.days.first { it.id == day.id }
            val stats = dayStats(firstDay.items, core)
            assertEquals(firstDay.name, day.name)
            assertEquals(formatDuration(stats.totalSeconds), day.durationLabel)
            assertEquals(stats.sets.toString(), day.setsLabel)
            assertEquals(firstDay.items.map { it.exercise.name }, day.items.map { it.name })
            assertEquals(firstDay.id, state.days.single { it.selected }.id)
        }

    @Test
    fun `crear un plan vacio aparece en la lista y queda activo`() = runBlocking {
        loadedState()

        viewModel.createPlan()

        // El plan y el puntero del activo son dos escrituras: hay una emisión intermedia
        // con los planes y el activo todavía apuntando al viejo. Se espera la que ya
        // tiene las tres cosas (seed A + seed B + el nuevo), que es la que ve el usuario.
        val state = withTimeout(5_000) {
            viewModel.state.first { it.plans.size == 3 && it.plans.any { plan -> plan.active && plan.daysCount == 0 } }
        }
        val created = state.plans.first { it.daysCount == 0 }
        assertTrue("el plan nuevo queda activo", created.active)
        assertEquals(created.id, state.selectedPlanId)
        assertNull("un plan vacío no tiene día seleccionado", state.day)
        assertTrue(state.days.isEmpty())
        assertTrue(state.canDeletePlan)

        val activeId = withTimeout(5_000) { settings.activeRoutineId.first { it != null } }
        assertEquals(created.id, activeId)
    }

    @Test
    fun `duplicar copia dias e items con ids nuevos y selecciona la copia`() = runBlocking {
        val initial = loadedState()
        val originalId = requireNotNull(initial.selectedPlanId)
        val original = requireNotNull(routines.routine(originalId))
        val beforeIds = initial.plans.map { it.id }.toSet()

        viewModel.duplicatePlan(originalId)

        // Igual que en crear: la copia existe en la lista antes de que el ViewModel la
        // seleccione (dos emisiones). Se espera la que ya la tiene seleccionada.
        val state = withTimeout(5_000) {
            viewModel.state.first {
                it.plans.size == beforeIds.size + 1 && it.selectedPlanId !in beforeIds
            }
        }
        val copy = state.plans.first { it.id !in beforeIds }
        assertEquals(copy.id, state.selectedPlanId)
        assertEquals(original.days.size, copy.daysCount)

        val copied = requireNotNull(routines.routine(copy.id))
        assertEquals(original.days.size, copied.days.size)
        val originalItemIds = original.days.flatMap { day -> day.items.map { it.id } }.toSet()
        val copiedItemIds = copied.days.flatMap { day -> day.items.map { it.id } }.toSet()
        assertEquals(originalItemIds.size, copiedItemIds.size)
        assertTrue(
            "la copia no puede compartir ids de ítem con el original",
            originalItemIds.intersect(copiedItemIds).isEmpty(),
        )
        assertEquals(
            original.days.flatMap { day -> day.items.map { it.exerciseId } },
            copied.days.flatMap { day -> day.items.map { it.exerciseId } },
        )
    }

    @Test
    fun `renombrar el plan se refleja por flow`() = runBlocking {
        val state = loadedState()
        val planId = requireNotNull(state.selectedPlanId)

        viewModel.renamePlan(planId, "Full body B")

        val renamed = withTimeout(5_000) { viewModel.state.first { it.selectedPlanName == "Full body B" } }
        assertEquals("Full body B", renamed.plans.single { it.id == planId }.name)
    }

    @Test
    fun `eliminar el ultimo plan no esta disponible`() = runBlocking {
        val state = loadedState()
        assertEquals(2, state.plans.size)
        val activeId = requireNotNull(state.selectedPlanId)
        val otherId = state.plans.first { it.id != activeId }.id

        // Primero se borra el que no está activo: con dos planes todavía se puede.
        assertTrue(routines.deleteRoutine(otherId))
        assertFalse(
            "el repositorio rechaza borrar el único plan que queda",
            routines.deleteRoutine(activeId),
        )
        assertEquals(1, routines.observeRoutines().first().size)
        val last = withTimeout(5_000) { viewModel.state.first { it.plans.size == 1 } }
        assertFalse(last.canDeletePlan)
    }

    @Test
    fun `exportar produce el JSON de la web igual a exportPlan de core`() = runBlocking {
        val state = loadedState()
        val planId = requireNotNull(state.selectedPlanId)

        val json = requireNotNull(viewModel.exportJson(planId)) { "no se exportó el plan seleccionado" }

        val routine = requireNotNull(routines.routine(planId))
        val expected = exportPlan(
            routine = routine,
            muscles = exercises.muscles(),
            settings = settings.current().toCoreSettings(),
        )
        assertEquals(expected, json)

        assertTrue(json.contains("\"app\": \"rutinas-gimnasio\""))
        assertTrue(json.contains("\"tipo\": \"plan\""))
        assertTrue(json.contains("\"version\": 1"))
        assertTrue(json.contains("\"plan\""))
        assertTrue(json.contains("\"ejercicios\""))
        assertTrue(json.contains("\"resumen\""))
    }
}
