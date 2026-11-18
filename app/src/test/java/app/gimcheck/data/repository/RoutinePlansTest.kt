package app.gimcheck.data.repository

import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.DAY_ID
import app.gimcheck.data.db.ROUTINE_ID
import app.gimcheck.data.db.inMemoryDatabase
import app.gimcheck.data.db.seedPlan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Operaciones de plan de GIM-16 en el repositorio (paridad con la web, §1.1):
 * crear vacío, duplicar con copia profunda, renombrar y **no** borrar el último.
 *
 * El `Flow` es la verificación: después de cada escritura se lee el árbol que ve la
 * pantalla, no las filas sueltas.
 */
@RunWith(RobolectricTestRunner::class)
class RoutinePlansTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RoutineRepository

    private var sequence = 0

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDatabase()
        repository = RoutineRepository(db, newId = { "new-${sequence++}" })
        db.seedPlan()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `crear un plan vacio lo deja al final de la lista`() = runBlocking {
        val id = repository.createRoutine("Rutina B")

        val routines = repository.observeRoutines().first()
        assertEquals(2, routines.size)
        val created = requireNotNull(routines.firstOrNull { it.id == id })
        assertEquals("Rutina B", created.name)
        assertTrue("el plan nuevo no tiene días", created.days.isEmpty())
        assertTrue(
            "el plan nuevo va después del existente",
            created.position > routines.first { it.id == ROUTINE_ID }.position,
        )
    }

    @Test
    fun `duplicar copia dias e items con ids nuevos`() = runBlocking {
        val source = repository.observeRoutines().first().single { it.id == ROUTINE_ID }

        val copyId = requireNotNull(repository.duplicateRoutine(ROUTINE_ID, "Rutina A (copia)"))
        val routines = repository.observeRoutines().first()
        val copy = requireNotNull(routines.firstOrNull { it.id == copyId })

        assertEquals("Rutina A (copia)", copy.name)
        assertEquals(source.days.size, copy.days.size)
        assertNotEquals(source.id, copy.id)

        val sourceDayIds = source.days.map { it.id }.toSet()
        val copyDayIds = copy.days.map { it.id }.toSet()
        assertTrue("los días comparten ids con el original", sourceDayIds.intersect(copyDayIds).isEmpty())

        copy.days.zip(source.days).forEach { (copiedDay, originalDay) ->
            assertEquals(originalDay.name, copiedDay.name)
            assertEquals(originalDay.items.size, copiedDay.items.size)
            val originalItems = originalDay.items.map { it.id }.toSet()
            assertTrue(
                "los ítems comparten ids con el original",
                copiedDay.items.none { it.id in originalItems },
            )
            assertEquals(
                "la copia cambia ejercicios",
                originalDay.items.map { it.exerciseId },
                copiedDay.items.map { it.exerciseId },
            )
            assertEquals(
                "la copia cambia series y marcas",
                originalDay.items.map { Triple(it.sets, it.dropSet, it.supersetNext) },
                copiedDay.items.map { Triple(it.sets, it.dropSet, it.supersetNext) },
            )
        }

        // El original queda intacto.
        val original = requireNotNull(repository.observeRoutines().first().firstOrNull { it.id == ROUTINE_ID })
        assertEquals(source.days.size, original.days.size)
        assertEquals(source.days.flatMap { day -> day.items.map { it.id } }, original.days.flatMap { day -> day.items.map { it.id } })
    }

    @Test
    fun `renombrar se refleja en el flow`() = runBlocking {
        repository.renameRoutine(ROUTINE_ID, "Torso / pierna")

        val routine = repository.observeRoutines().first().single { it.id == ROUTINE_ID }
        assertEquals("Torso / pierna", routine.name)
    }

    @Test
    fun `eliminar el ultimo plan no se permite`() = runBlocking {
        assertFalse(repository.deleteRoutine(ROUTINE_ID))
        assertEquals(1, repository.observeRoutines().first().size)
    }

    @Test
    fun `eliminar un plan cuando hay dos si se permite`() = runBlocking {
        repository.createRoutine("Rutina B")

        assertTrue(repository.deleteRoutine(ROUTINE_ID))
        val routines = repository.observeRoutines().first()
        assertEquals(1, routines.size)
        assertNull(routines.firstOrNull { it.id == ROUTINE_ID })
    }

    @Test
    fun `duplicar un dia lo agrega al final con items nuevos`() = runBlocking {
        val source = repository.observeRoutines().first().single { it.id == ROUTINE_ID }.days.single()

        val copyId = requireNotNull(repository.duplicateDay(DAY_ID))
        val days = repository.observeRoutines().first().single { it.id == ROUTINE_ID }.days

        assertEquals(2, days.size)
        assertEquals(copyId, days.last().id)
        assertEquals(source.name, days.last().name)
        assertEquals(source.items.size, days.last().items.size)
        assertTrue(
            "el día copiado comparte ids de ítem",
            days.last().items.none { it.id in source.items.map { item -> item.id } },
        )
        assertTrue(
            "el día copiado comparte id con el original",
            days.last().id != source.id,
        )
    }
}
