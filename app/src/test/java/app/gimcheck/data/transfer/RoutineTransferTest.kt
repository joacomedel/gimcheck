package app.gimcheck.data.transfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gimcheck.core.exportPlan
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.inMemoryDatabase
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.RoutineRepository
import app.gimcheck.data.repository.SettingsRepository
import app.gimcheck.data.seed.SeedImporter
import app.gimcheck.data.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Export/import de rutinas (GIM-53).
 *
 * Los casos que importan para no romper datos del usuario:
 * 1. el pack completo vuelve a entrar sin perder días ni ítems;
 * 2. importar es aditivo: no borra las rutinas que ya estaban;
 * 3. los ejercicios y músculos existentes se reutilizan por nombre, no se duplican;
 * 4. importar dos veces desambigua el nombre en vez de pisar;
 * 5. un archivo inválido no escribe nada.
 */
@RunWith(RobolectricTestRunner::class)
class RoutineTransferTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val seedJson: String by lazy {
        context.assets.open("seed.json").bufferedReader().use { it.readText() }
    }

    private lateinit var db: AppDatabase
    private lateinit var routines: RoutineRepository
    private lateinit var exercises: ExerciseRepository
    private lateinit var settings: SettingsRepository
    private lateinit var transfer: RoutineTransfer

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDatabase()
        routines = RoutineRepository(db)
        exercises = ExerciseRepository(db)
        settings = SettingsRepository(db)
        transfer = RoutineTransfer(db, routines, exercises, settings)
        SeedImporter(db).seedIfEmpty(seedJson)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `el pack completo vuelve a entrar sin perder nada`() = runBlocking {
        val before = routines.allRoutines()
        val exported = transfer.export()
        val preview = transfer.preview(exported)
        assertTrue(preview is RoutineImportPreview.Ready)

        val ready = preview as RoutineImportPreview.Ready
        assertEquals(before.size, ready.sourceRoutines)
        assertEquals(before.sumOf { it.days.size }, ready.routines.sumOf { it.days.size })
        assertEquals(
            before.sumOf { routine -> routine.days.sumOf { it.items.size } },
            ready.routines.sumOf { routine -> routine.days.sumOf { it.items.size } },
        )

        val outcome = transfer.import(ready)
        assertTrue(outcome is RoutineImportOutcome.Imported)
        val after = routines.allRoutines()
        assertEquals(before.size * 2, after.size)
        assertEquals(
            before.sumOf { routine -> routine.days.sumOf { it.items.size } } * 2,
            after.sumOf { routine -> routine.days.sumOf { it.items.size } },
        )
    }

    @Test
    fun `los ejercicios del seed se reutilizan por nombre`() = runBlocking {
        val exercisesBefore = exercises.all().size
        val routine = routines.allRoutines().first()
        val exported = transfer.export(setOf(routine.id))
        val ready = transfer.preview(exported) as RoutineImportPreview.Ready

        assertEquals("el pack usa ejercicios del seed, no crea nuevos", 0, ready.newExercises.size)
        assertTrue(ready.reusedExercises > 0)

        transfer.import(ready)
        assertEquals(exercisesBefore, exercises.all().size)
    }

    @Test
    fun `un plan de la web se importa como rutina nueva`() = runBlocking {
        val routine = routines.allRoutines().first()
        val coreSettings = AppSettings.CORE_DEFAULT
        val plan = exportPlan(routine, exercises.muscles(), coreSettings)
        val ready = transfer.preview(plan) as RoutineImportPreview.Ready

        assertEquals(1, ready.sourceRoutines)
        assertEquals(routine.days.size, ready.routines.single().days.size)
        assertEquals(
            routine.days.first().items.first().exercise.name,
            ready.routines.single().days.first().items.first().exercise.name,
        )

        val outcome = transfer.import(ready) as RoutineImportOutcome.Imported
        val imported = routines.allRoutines().first { it.id == outcome.firstRoutineId }
        assertEquals(routine.days.size, imported.days.size)
        assertNotEquals(routine.name, imported.name)
    }

    @Test
    fun `importar dos veces no pisa y desambigua el nombre`() = runBlocking {
        val routine = routines.allRoutines().first()
        val exported = transfer.export(setOf(routine.id))
        val first = transfer.preview(exported) as RoutineImportPreview.Ready
        val firstOutcome = transfer.import(first)
        assertTrue(firstOutcome is RoutineImportOutcome.Imported)
        val firstId = (firstOutcome as RoutineImportOutcome.Imported).firstRoutineId

        val second = transfer.preview(exported) as RoutineImportPreview.Ready
        val secondOutcome = transfer.import(second) as RoutineImportOutcome.Imported
        val names = routines.allRoutines().filter { it.id != routine.id }.map { it.name }

        assertEquals(2, names.size)
        assertEquals(names.size, names.distinct().size)
        assertNotEquals(firstId, secondOutcome.firstRoutineId)
        assertTrue(names.any { it.endsWith("(importada)") })
        assertTrue(names.any { it.endsWith("(importada 2)") })
    }

    @Test
    fun `un archivo invalido no escribe nada`() = runBlocking {
        val routinesBefore = routines.allRoutines().size
        val exercisesBefore = exercises.all().size

        val preview = transfer.preview("""{"schema":"gimcheck","kind":"routines","version":1}""")

        assertTrue(preview is RoutineImportPreview.Invalid)
        assertEquals(routinesBefore, routines.allRoutines().size)
        assertEquals(exercisesBefore, exercises.all().size)
    }
}
