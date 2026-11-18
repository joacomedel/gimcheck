package app.gimcheck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paridad de `ssConflicts` / `heavyPairs` con `Rutinas de Gimnasio.html` (líneas 329-354).
 *
 * El catálogo sale de `HtmlSeed` (los 25 ejercicios de la Rutina A) más `fon`
 * (Fondos lastrados), que el seed no usa pero `planBDays()` sí agrega: es el ejemplo
 * canónico del ticket y el literal está en el HTML línea 208.
 *
 * Los casos esperados están derivados de la regla de la web, no de la implementación.
 */
class SupersetsTest {

    private val exercises = HtmlSeed.exercises
    private fun ex(id: String): Exercise = exercises.getValue(id)

    /** `['fon','Fondos lastrados','Pecho',[['Tríceps',.5],['Hombro anterior',.5]],45,120,1]` */
    private val fon = Exercise(
        id = "fon",
        name = "Fondos lastrados",
        primaryMuscleId = "Pecho",
        secondaries = listOf(Secondary("Tríceps", 0.5), Secondary("Hombro anterior", 0.5)),
        secondsPerSet = 45,
        restSeconds = 120,
        compound = true,
    )

    private fun item(
        id: String,
        exercise: Exercise,
        supersetNext: Boolean = false,
        sets: Int = 3,
    ) = DayItem(id = id, exercise = exercise, sets = sets, supersetNext = supersetNext)

    private fun day(vararg items: DayItem) = RoutineDay(id = "d", name = "Día", items = items.toList())

    @Test
    fun `superserie de 2 comparte los tres musculos de fondos y press plano`() {
        val block = listOf(item("a", fon, supersetNext = true), item("b", ex("pp")))

        val conflicts = supersetConflicts(block)

        assertEquals(1, conflicts.size)
        val conflict = conflicts.single()
        assertEquals("a", conflict.a.id)
        assertEquals("b", conflict.b.id)
        // Orden de la web: musclesOf(a) = [principal, secundarios en orden declarado].
        assertEquals(listOf("Pecho", "Tríceps", "Hombro anterior"), conflict.sharedMuscleIds)
        // El principal pesa 1 y el secundario su factor; `overlap` es informativo.
        assertEquals(1.0, conflict.shared[0].overlap, 1e-9)
        assertEquals(0.25, conflict.shared[1].overlap, 1e-9)
        assertEquals(0.25, conflict.shared[2].overlap, 1e-9)
        assertEquals(0.5, conflict.shared[1].aFactor, 1e-9)
        assertEquals(0.5, conflict.shared[1].bFactor, 1e-9)
    }

    @Test
    fun `un bloque de 3 deja un conflicto por par`() {
        val block = listOf(
            item("a", ex("pp"), supersetNext = true),
            item("b", ex("tpa"), supersetNext = true),
            item("c", ex("tpb")),
        )
        assertEquals(1, groups(block).size)
        assertEquals(3, groups(block).single().size)

        val conflicts = supersetConflicts(block)

        // La web compara todos los pares del grupo: 3 para un bloque de 3.
        assertEquals(3, conflicts.size)
        assertEquals(listOf("a" to "b", "a" to "c", "b" to "c"), conflicts.map { it.a.id to it.b.id })
        assertTrue(conflicts.all { it.sharedMuscleIds == listOf("Tríceps") })
    }

    @Test
    fun `conflicto solo por secundario entre dominadas y biceps martillo`() {
        // Ninguno es principal del otro: Espalda/Bíceps y Bíceps/Antebrazo.
        val block = listOf(item("a", ex("dl"), supersetNext = true), item("b", ex("bm")))

        val conflict = supersetConflicts(block).single()

        assertEquals(listOf("Bíceps", "Antebrazo"), conflict.sharedMuscleIds)
        assertEquals(0.5, conflict.shared[0].aFactor, 1e-9)
        assertEquals(1.0, conflict.shared[0].bFactor, 1e-9)
    }

    @Test
    fun `dos multiarticulares en el mismo bloque son un par pesado`() {
        val block = listOf(item("a", ex("pp"), supersetNext = true), item("b", ex("pin")))

        val heavy = heavyPairs(block)

        assertEquals(1, heavy.size)
        assertEquals("a", heavy.single().a.id)
        assertEquals("b", heavy.single().b.id)
        // También comparten músculo: las dos advertencias de la web conviven.
        assertEquals(listOf("Pecho", "Hombro anterior", "Tríceps"), supersetConflicts(block).single().sharedMuscleIds)
    }

    @Test
    fun `bloque limpio no tiene conflictos ni pares pesados`() {
        // Press plano (Pecho + Hombro ant + Tríceps) con Antebrazo.
        val block = listOf(item("a", ex("pp"), supersetNext = true), item("b", ex("ant")))

        assertTrue(supersetConflicts(block).isEmpty())
        assertTrue(heavyPairs(block).isEmpty())
    }

    @Test
    fun `no hay conflicto ni par pesado entre bloques distintos`() {
        // Mismos dos multiarticulares que el test de par pesado, pero sueltos.
        val d = day(item("a", ex("pp")), item("b", ex("pin")))

        assertEquals(2, groups(d.items).size)
        assertTrue(supersetConflicts(d).isEmpty())
        assertTrue(heavyPairs(d).isEmpty())
        assertTrue(supersetBlocks(d).isEmpty())
    }

    @Test
    fun `el ultimo item con superserie queda solo en su bloque`() {
        val d = day(
            item("a", ex("pp"), supersetNext = true),
            item("b", ex("tpa")),
            item("c", ex("tpb"), supersetNext = true),
        )

        assertEquals(listOf(2, 1), groups(d.items).map { it.size })
        val conflicts = supersetConflicts(d)
        assertEquals(1, conflicts.size)
        assertEquals("a" to "b", conflicts.single().a.id to conflicts.single().b.id)
        assertEquals(listOf("a", "b"), supersetBlocks(d).single().map { it.id })
        assertTrue(heavyPairs(d).isEmpty())
    }

    @Test
    fun `un secundario con factor 0 no participa`() {
        val zero = Exercise(
            id = "cero",
            name = "Remo con tríceps a cero",
            primaryMuscleId = "Espalda",
            secondaries = listOf(Secondary("Tríceps", 0.0)),
            secondsPerSet = 40,
            restSeconds = 90,
        )
        val block = listOf(item("a", zero, supersetNext = true), item("b", ex("tpa")))

        assertEquals(listOf("Espalda"), participatingMuscles(zero).map { it.muscleId })
        assertTrue(supersetConflicts(block).isEmpty())
    }

    @Test
    fun `el atajo por dia junta los conflictos de todos los bloques`() {
        val d = day(
            item("a", fon, supersetNext = true),
            item("b", ex("pp")),
            item("c", ex("dl"), supersetNext = true),
            item("d", ex("bm")),
        )

        val conflicts = supersetConflicts(d)

        assertEquals(2, conflicts.size)
        assertEquals(listOf("a" to "b", "c" to "d"), conflicts.map { it.a.id to it.b.id })
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), supersetBlocks(d).map { block -> block.map { it.id } })
    }

    @Test
    fun `supersetBlocks descarta los items sueltos`() {
        val d = day(
            item("a", ex("pp"), supersetNext = true),
            item("b", ex("ant")),
            item("c", ex("pin"), supersetNext = true),
            item("d", ex("bmq")),
            item("e", ex("crun"), supersetNext = true), // último: grupo de 1
        )

        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), supersetBlocks(d).map { block -> block.map { it.id } })
    }
}
