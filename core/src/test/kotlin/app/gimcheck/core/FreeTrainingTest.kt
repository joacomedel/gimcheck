package app.gimcheck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reglas del entrenamiento libre (GIM-49): superserie como salto puntual y descanso del
 * ejercicio. Son funciones puras, así que la regla del producto se prueba sin Android.
 */
class FreeTrainingTest {

    private val press = Exercise(
        id = "press",
        name = "Press plano",
        primaryMuscleId = "pecho",
        secondsPerSet = 40,
        restSeconds = 90,
    )
    private val forearm = Exercise(
        id = "antebrazo",
        name = "Antebrazo",
        primaryMuscleId = "antebrazo",
        secondsPerSet = 30,
        restSeconds = 60,
    )
    private val row = Exercise(
        id = "remo",
        name = "Remo",
        primaryMuscleId = "espalda",
        secondsPerSet = 40,
        restSeconds = 120,
    )

    @Test
    fun `el primero de una superserie salta al siguiente del dia`() {
        val routine = routine(
            day(
                DayItem(id = "i1", exercise = press, sets = 3, supersetNext = true),
                DayItem(id = "i2", exercise = forearm, sets = 3),
            ),
        )

        assertEquals(forearm, supersetNextExercise(routine, "press"))
    }

    @Test
    fun `el segundo de la superserie no vuelve al primero`() {
        val routine = routine(
            day(
                DayItem(id = "i1", exercise = press, sets = 3, supersetNext = true),
                DayItem(id = "i2", exercise = forearm, sets = 3),
            ),
        )

        assertNull("después de antebrazo no hay saltos: descansa y se queda ahí", supersetNextExercise(routine, "antebrazo"))
    }

    @Test
    fun `un ejercicio suelto no salta a ningun lado`() {
        val routine = routine(day(DayItem(id = "i1", exercise = row, sets = 3)))

        assertNull(supersetNextExercise(routine, "remo"))
    }

    @Test
    fun `la cadena de tres sigue al siguiente aunque el actual no sea el primero`() {
        val curl = Exercise(
            id = "curl",
            name = "Curl",
            primaryMuscleId = "biceps",
            secondsPerSet = 30,
            restSeconds = 60,
        )
        val routine = routine(
            day(
                DayItem(id = "i1", exercise = press, sets = 3, supersetNext = true),
                DayItem(id = "i2", exercise = forearm, sets = 3, supersetNext = true),
                DayItem(id = "i3", exercise = curl, sets = 3),
            ),
        )

        assertEquals(curl, supersetNextExercise(routine, "antebrazo"))
    }

    @Test
    fun `la superserie se encuentra aunque este en otro dia de la rutina`() {
        val routine = routine(
            day(DayItem(id = "i1", exercise = row, sets = 3)),
            day(
                DayItem(id = "i2", exercise = press, sets = 3, supersetNext = true),
                DayItem(id = "i3", exercise = forearm, sets = 3),
            ),
        )

        assertEquals(forearm, supersetNextExercise(routine, "press"))
    }

    @Test
    fun `el descanso usa el override del plan y si no el del catalogo`() {
        val routine = routine(
            day(
                DayItem(id = "i1", exercise = press, sets = 3, restOverrideSeconds = 45),
                DayItem(id = "i2", exercise = row, sets = 3),
            ),
        )

        assertEquals(45, restSecondsFor(routine, press))
        assertEquals(120, restSecondsFor(routine, row))
        assertEquals("sin plan queda el del catálogo", 90, restSecondsFor(null, press))
    }

    private fun day(vararg items: DayItem) = RoutineDay(id = "d-${items.first().id}", name = "Día", items = items.toList())

    private fun routine(vararg days: RoutineDay) = Routine(id = "r1", name = "Rutina", days = days.toList())
}
