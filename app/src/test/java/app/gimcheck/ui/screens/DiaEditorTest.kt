package app.gimcheck.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gimcheck.core.DayItem
import app.gimcheck.core.EditorErrorCode
import app.gimcheck.core.Secondary
import app.gimcheck.core.dayStats
import app.gimcheck.core.effectiveSets
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * El **editor del día** (GIM-17) sobre el seed real, sin render (no hay emulador,
 * `docs/build.md` §11). Se verifica el criterio del ticket:
 *
 * 1. agregar al final y subir el ítem cambia el orden **y** el `superset_next` del
 *    último (que no puede encadenar nunca);
 * 2. el último ítem no se puede marcar ni por UI ni por repositorio;
 * 3. `sets` fuera de 1..20 se rechaza con error **tipado** y no se guarda;
 * 4. drop set mueve `effectiveSets` y la duración del borrador, y los tiles coinciden
 *    con `dayStats` de `:core`;
 * 5. un conflicto por **secundario** aparece en las advertencias del bloque correcto;
 * 6. quitar el último ítem destilda el anterior;
 * 7. editar no toca la base hasta guardar.
 */
@RunWith(RobolectricTestRunner::class)
class DiaEditorTest {

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

    /** El estado del editor ya abierto. */
    private fun editor(): DiaEditorUiState =
        requireNotNull(viewModel.state.value.editor) { "el editor no está abierto" }

    @Test
    fun `agregar al final y subir el nuevo deja limpio el superset del nuevo ultimo`() = runBlocking {
        val day = requireNotNull(loadedState().day)
        val original = day.items.map { it.id }
        assertTrue("el seed trae al menos dos ítems", original.size >= 2)

        viewModel.startEdit()
        val start = editor()
        assertEquals(original, start.items.map { it.id })

        // Un ejercicio del catálogo que todavía no esté en el día.
        val option = start.catalog.first { candidate ->
            start.items.none { it.exerciseId == candidate.id }
        }
        viewModel.addExercise(option.id)

        val added = editor()
        assertEquals("el ítem nuevo va al final", original, added.items.dropLast(1).map { it.id })
        val newItem = added.items.last()
        assertEquals(option.id, newItem.exerciseId)
        assertEquals(RutinasViewModel.DEFAULT_SETS, newItem.sets)
        assertFalse("el ítem nuevo no encadena: es el último", newItem.supersetNext)
        assertFalse("en el último no hay con qué encadenar", newItem.canSuperset)

        // El que era último ahora sí puede encadenar con el nuevo.
        viewModel.toggleSupersetNext(original.last())
        assertTrue(editor().items.first { it.id == original.last() }.supersetNext)

        // Subirlo: el que era último queda último y pierde la marca (invariante de §2).
        viewModel.moveItem(newItem.id, -1)
        val moved = editor()
        assertEquals(newItem.id, moved.items[moved.items.lastIndex - 1].id)
        assertEquals(original.last(), moved.items.last().id)
        assertFalse("el nuevo último no puede encadenar", moved.items.last().supersetNext)
        assertTrue("el borrador movido es válido: ${moved.problems}", moved.problems.isEmpty())

        // Guardar persiste el orden del borrador.
        val expected = moved.items.map { it.id }
        viewModel.saveEdit()
        val saved = withTimeout(5_000) {
            viewModel.state.first { state ->
                state.editor == null && state.day?.items?.map { it.id } == expected
            }
        }
        assertNull("al guardar se sale del editor", saved.editor)

        val stored = requireNotNull(routines.day(day.id))
        assertEquals(expected, stored.items.map { it.id })
        assertFalse(stored.items.last().supersetNext)
    }

    @Test
    fun `no se puede marcar superserie en el ultimo item ni por UI ni por repositorio`() = runBlocking {
        val day = requireNotNull(loadedState().day)

        viewModel.startEdit()
        val last = editor().items.last()

        viewModel.toggleSupersetNext(last.id)
        assertFalse("la UI no marca el último", editor().items.last().supersetNext)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { routines.setSupersetNext(last.id, true) }
        }
        val stored = requireNotNull(routines.day(day.id))
        assertFalse("el repositorio tampoco lo deja persistido", stored.items.last().supersetNext)
    }

    @Test
    fun `sets fuera de rango se rechaza con error tipado y no se guarda`() = runBlocking {
        val day = requireNotNull(loadedState().day)
        val before = requireNotNull(routines.day(day.id))

        viewModel.startEdit()
        val first = editor().items.first()
        viewModel.setSets(first.id, 21)

        val invalid = editor()
        assertEquals(
            "un error tipado, no un string suelto",
            listOf(EditorErrorCode.SETS_OUT_OF_RANGE),
            invalid.problems.map { it.code },
        )
        assertEquals(first.id, invalid.problems.single().subjectId)
        assertEquals(
            setOf(EditorErrorCode.SETS_OUT_OF_RANGE),
            invalid.items.first { it.id == first.id }.problemCodes,
        )
        assertFalse("con error no se puede guardar", invalid.canSave)

        viewModel.saveEdit()
        assertTrue("el intento rechazado se avisa en pantalla", editor().rejected)
        val after = requireNotNull(routines.day(day.id))
        assertEquals("la base no se tocó", before.items.map { it.sets }, after.items.map { it.sets })
    }

    @Test
    fun `drop set cambia las series efectivas y la duracion del borrador`() = runBlocking {
        loadedState()
        viewModel.startEdit()
        val before = editor()
        val target = before.items.first()
        val beforeDraft = requireNotNull(viewModel.draftDay())

        viewModel.toggleDropSet(target.id)

        val after = editor()
        val item = after.items.first { it.id == target.id }
        assertTrue(item.dropSet)
        assertTrue(
            "el drop set suma series efectivas (${target.effectiveSets} → ${item.effectiveSets})",
            item.effectiveSets > target.effectiveSets,
        )

        val core = AppSettings.CORE_DEFAULT
        val beforeStats = dayStats(beforeDraft.items, core)
        val afterDraft = requireNotNull(viewModel.draftDay())
        val afterStats = dayStats(afterDraft.items, core)
        assertTrue("suma trabajo", afterStats.workSeconds > beforeStats.workSeconds)
        assertTrue("y con eso la duración", afterStats.totalSeconds > beforeStats.totalSeconds)

        // Los tiles son `dayStats`, no una segunda fórmula.
        assertEquals(formatDuration(afterStats.totalSeconds), after.durationLabel)
        assertEquals(afterStats.sets.toString(), after.setsLabel)
        assertEquals(
            effectiveSets(afterDraft.items.first { it.id == target.id }, core),
            item.effectiveSets,
            1e-9,
        )
    }

    @Test
    fun `el conflicto por secundario aparece solo en el bloque que lo produce`() = runBlocking {
        val day = requireNotNull(loadedState().day)
        val catalog = exercises.all().filterNot { it.archived }
        val press = catalog.first { it.id == "pp" } // Pecho, multiarticular
        val triceps = catalog.first { it.id == "tpa" } // Tríceps
        val hombro = catalog.first { it.id == "hu" } // Hombro lateral

        // El tríceps gana Pecho como secundario: el conflicto es por secundario, no por principal.
        exercises.saveExercise(
            triceps.copy(
                secondaries = listOf(Secondary(muscleId = press.primaryMuscleId, factor = 0.5)),
            ),
        )

        val crafted = listOf(
            DayItem(id = "it-press", exercise = press, sets = 3),
            DayItem(id = "it-triceps", exercise = triceps, sets = 3),
            DayItem(id = "it-hombro", exercise = hombro, sets = 3),
        )
        routines.saveDayItems(day.id, crafted)
        withTimeout(5_000) {
            viewModel.state.first { state ->
                state.day?.items?.map { it.id } == crafted.map { it.id }
            }
        }

        val pecho = exercises.muscles().first { it.id == press.primaryMuscleId }.name

        viewModel.startEdit()
        viewModel.toggleSupersetNext("it-press")
        val conflicted = editor()
        val warning = conflicted.warnings.single { it.startsWith("Superserie") }
        assertTrue(
            "la advertencia nombra el músculo compartido: $warning",
            warning.contains(pecho),
        )
        assertTrue(
            "el tríceps no es multiarticular: no hay par pesado",
            conflicted.warnings.none { it.startsWith("Superserie muy pesada") },
        )

        // El bloque del medio no comparte nada con el hombro: sin advertencias.
        viewModel.toggleSupersetNext("it-press")
        viewModel.toggleSupersetNext("it-triceps")
        val other = editor()
        assertTrue("bloque sin músculo en común: ${other.warnings}", other.warnings.isEmpty())
    }

    @Test
    fun `quitar el ultimo item destilda el anterior`() = runBlocking {
        val day = requireNotNull(loadedState().day)
        viewModel.startEdit()
        val items = editor().items
        assertTrue(items.size >= 2)
        val lastId = items.last().id
        val secondId = items[items.size - 2].id

        viewModel.toggleSupersetNext(secondId)
        assertTrue(editor().items.first { it.id == secondId }.supersetNext)

        viewModel.removeItem(lastId)

        val after = editor()
        assertEquals(secondId, after.items.last().id)
        assertFalse("el nuevo último no encadena", after.items.last().supersetNext)
        assertTrue("el día queda consistente: ${after.problems}", after.problems.isEmpty())

        viewModel.saveEdit()
        val stored = withTimeout(5_000) {
            viewModel.state.first { state ->
                state.editor == null && state.day?.items?.map { it.id } == after.items.map { it.id }
            }
        }
        assertEquals(after.items.map { it.id }, stored.day?.items?.map { it.id })
        assertFalse(requireNotNull(routines.day(day.id)).items.last().supersetNext)
    }

    @Test
    fun `editar no toca la base hasta guardar y sin cambios no se puede guardar`() = runBlocking {
        val day = requireNotNull(loadedState().day)
        val before = requireNotNull(routines.day(day.id))

        viewModel.startEdit()
        assertFalse("sin cambios no hay nada que guardar", editor().canSave)

        val target = editor().items.first()
        viewModel.setSets(target.id, 5)
        assertTrue(editor().dirty)
        assertTrue(editor().canSave)

        val stored = requireNotNull(routines.day(day.id))
        assertEquals(
            "el borrador no escribió nada",
            before.items.map { it.sets },
            stored.items.map { it.sets },
        )

        viewModel.cancelEdit()
        assertNull(viewModel.state.value.editor)
        assertEquals(
            "descartar deja el día guardado como estaba",
            before.items.map { it.id },
            requireNotNull(routines.day(day.id)).items.map { it.id },
        )
    }
}
