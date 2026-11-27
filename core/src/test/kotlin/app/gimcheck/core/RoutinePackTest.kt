package app.gimcheck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pack de rutinas: ida y vuelta, catálogo mínimo y los tres formatos de entrada.
 * El caso importante es que un plan exportado por la app se pueda volver a importar:
 * el formato de la web referencia ejercicios por nombre y el import tiene que
 * reconstruirlos sin perder días, series ni superseries.
 */
class RoutinePackTest {

    private val snapshot: DomainSnapshot =
        importWeb(fixture("web/web_v21.json")).valueOrThrow().snapshot

    private val exportedAt: String = "2026-11-27T10:00:00"

    @Test
    fun `el pack conserva rutinas, dias, items e ids`() {
        val text = exportRoutinePack(snapshot, exportedAt)
        val packageValue = importRoutinePack(text).valueOrThrow()

        assertEquals(snapshot.routines.size, packageValue.routines.size)
        assertEquals(snapshot.routines.map { it.id }, packageValue.routines.map { it.id })
        assertEquals(snapshot.routines.map { it.name }, packageValue.routines.map { it.name })
        assertEquals(
            snapshot.routines.sumOf { routine -> routine.days.size },
            packageValue.routines.sumOf { routine -> routine.days.size },
        )
        assertEquals(
            snapshot.routines.sumOf { routine -> routine.days.sumOf { it.items.size } },
            packageValue.routines.sumOf { routine -> routine.days.sumOf { it.items.size } },
        )

        val original = snapshot.routines.first()
        val imported = packageValue.routines.first { it.id == original.id }
        assertEquals(original.days.map { it.name }, imported.days.map { it.name })
        val originalItems = original.days.flatMap { it.items }
        val importedItems = imported.days.flatMap { it.items }
        assertEquals(originalItems.map { it.exercise.name }, importedItems.map { it.exercise.name })
        assertEquals(originalItems.map { it.sets }, importedItems.map { it.sets })
        assertEquals(originalItems.map { it.dropSet }, importedItems.map { it.dropSet })
        assertEquals(originalItems.map { it.supersetNext }, importedItems.map { it.supersetNext })
    }

    @Test
    fun `el pack de una sola rutina lleva solo su catalogo`() {
        val routine = snapshot.routines.first()
        val text = exportRoutinePack(snapshot, exportedAt, routineIds = setOf(routine.id))
        val packageValue = importRoutinePack(text).valueOrThrow()

        assertEquals(1, packageValue.routines.size)
        val usedExerciseIds = routine.days
            .flatMap { it.items }
            .map { it.exercise.id }
            .toSet()
        assertEquals(usedExerciseIds, packageValue.exercises.map { it.id }.toSet())

        val usedMuscleIds = packageValue.exercises
            .flatMap { exercise -> listOf(exercise.primaryMuscleId) + exercise.secondaries.map { it.muscleId } }
            .toSet()
        assertEquals(usedMuscleIds, packageValue.muscles.map { it.id }.toSet())
    }

    @Test
    fun `un plan exportado por la app se vuelve a importar`() {
        val routine = snapshot.activeRoutine()!!
        val plan = exportPlan(routine, snapshot.muscles, snapshot.settings)
        val packageValue = importRoutineFile(plan).valueOrThrow()

        assertEquals(1, packageValue.routines.size)
        val imported = packageValue.routines.single()
        assertEquals(routine.name, imported.name)
        assertEquals(routine.days.map { it.name }, imported.days.map { it.name })
        assertEquals(
            routine.days.map { day -> day.items.map { it.sets } },
            imported.days.map { day -> day.items.map { it.sets } },
        )
        assertEquals(
            routine.days.first().items.first().exercise.name,
            imported.days.first().items.first().exercise.name,
        )
        assertTrue(imported.days.all { it.items.lastOrNull()?.supersetNext != true })
    }

    @Test
    fun `el plan importado resuelve musculos y ejercicios por nombre`() {
        val routine = snapshot.activeRoutine()!!
        val plan = exportPlan(routine, snapshot.muscles, snapshot.settings)
        val packageValue = importPlan(plan).valueOrThrow()

        val press = packageValue.exercises.first { it.name == "Press plano" }
        val chest = packageValue.muscles.first { it.id == press.primaryMuscleId }
        assertEquals("Pecho", chest.name)
        assertEquals(45, press.secondsPerSet)
        assertEquals(120, press.restSeconds)
        assertTrue(press.compound)
        assertNotNull(packageValue.routines.single().days.firstOrNull())
    }

    @Test
    fun `un backup completo se puede importar como rutinas`() {
        val backup = exportBackup(snapshot, exportedAt, pretty = true)
        val packageValue = importRoutineFile(backup).valueOrThrow()

        assertEquals(snapshot.routines.size, packageValue.routines.size)
        assertEquals(snapshot.exercises.size, packageValue.exercises.size)
    }

    @Test
    fun `rechaza una version desconocida del pack`() {
        val compact = exportRoutinePack(snapshot, exportedAt, pretty = false)
        val text = compact.replaceFirst("\"version\":1", "\"version\":99")
        val error = importRoutinePack(text).errorOrNull()
        assertTrue(error is ImportError.UnsupportedVersion)
    }

    @Test
    fun `rechaza un item con un ejercicio inexistente`() {
        val compact = exportRoutinePack(snapshot, exportedAt, pretty = false)
        val exerciseId = snapshot.routines.first().days.first().items.first().exercise.id
        val text = compact.replaceFirst("\"exerciseId\":\"$exerciseId\"", "\"exerciseId\":\"no-existe\"")
        val error = importRoutinePack(text).errorOrNull()
        assertTrue(error is ImportError.InvalidValue)
    }

    @Test
    fun `rechaza series fuera de rango`() {
        val compact = exportRoutinePack(snapshot, exportedAt, pretty = false)
        val text = compact.replaceFirst("\"sets\":3", "\"sets\":0")
        val error = importRoutinePack(text).errorOrNull()
        assertTrue(error is ImportError.InvalidValue)
    }

    @Test
    fun `un json cualquiera no se confunde con un pack`() {
        val error = importRoutineFile("""{"hola":1}""").errorOrNull()
        assertTrue(error is ImportError.InvalidValue)
    }
}
