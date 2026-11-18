package app.gimcheck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariantes del editor: cada regla con su caso de error y su caso válido (el válido no
 * debe devolver nada). Los errores se comparan por tipo y datos, nunca por mensaje.
 */
class EditorValidationTest {

    private fun exercise(
        id: String,
        primary: String = "Pecho",
        vararg secondary: Pair<String, Double>,
    ) = Exercise(
        id = id,
        name = id,
        primaryMuscleId = primary,
        secondaries = secondary.map { Secondary(it.first, it.second) },
        secondsPerSet = 45,
        restSeconds = 120,
    )

    private fun item(
        id: String,
        exercise: Exercise,
        sets: Int = 3,
        supersetNext: Boolean = false,
        restOverrideSeconds: Int? = null,
    ) = DayItem(
        id = id,
        exercise = exercise,
        sets = sets,
        supersetNext = supersetNext,
        restOverrideSeconds = restOverrideSeconds,
    )

    private fun day(vararg items: DayItem) = RoutineDay(id = "d", name = "Día", items = items.toList())

    // --- sets 1..20 ----------------------------------------------------------

    @Test
    fun `sets en los extremos del rango es valido`() {
        val ex = exercise("ok")
        assertTrue(validateItem(item("a", ex, sets = 1)).isEmpty())
        assertTrue(validateItem(item("b", ex, sets = 20)).isEmpty())
    }

    @Test
    fun `sets fuera de 1 a 20 es error tipado`() {
        val ex = exercise("mal")
        assertEquals(listOf(SetsOutOfRange("a", 0)), validateItem(item("a", ex, sets = 0)))
        assertEquals(listOf(SetsOutOfRange("b", 21)), validateItem(item("b", ex, sets = 21)))
        val error = validateItem(item("c", ex, sets = -3)).single() as SetsOutOfRange
        assertEquals(ITEM_SETS_RANGE, error.allowed)
        assertEquals(EditorErrorCode.SETS_OUT_OF_RANGE, error.code)
    }

    // --- factor de secundario 0..1 ------------------------------------------

    @Test
    fun `factor en los extremos del rango es valido`() {
        val ex = exercise("ok", "Pecho", "Tríceps" to 0.0, "Hombro anterior" to 1.0)
        assertTrue(validateExercise(ex).isEmpty())
    }

    @Test
    fun `factor fuera de 0 a 1 es error tipado`() {
        val low = exercise("bajo", "Pecho", "Tríceps" to -0.01)
        val high = exercise("alto", "Pecho", "Tríceps" to 1.01)

        val lowError = validateExercise(low).single() as SecondaryFactorOutOfRange
        assertEquals(SecondaryFactorOutOfRange("bajo", "Tríceps", -0.01), lowError)
        assertEquals(EditorErrorCode.SECONDARY_FACTOR_OUT_OF_RANGE, lowError.code)

        val highError = validateExercise(high).single() as SecondaryFactorOutOfRange
        assertEquals(1.01, highError.factor, 1e-9)
        assertEquals(SECONDARY_FACTOR_RANGE, highError.allowed)
    }

    // --- secundarios repetidos ----------------------------------------------

    @Test
    fun `dos secundarios con el mismo musculo es error`() {
        val ex = exercise("rep", "Pecho", "Bíceps" to 0.5, "Bíceps" to 0.25)
        assertEquals(listOf(DuplicateSecondary("rep", "Bíceps", 2)), validateExercise(ex))
    }

    @Test
    fun `secundarios distintos no son error`() {
        val ex = exercise("ok", "Pecho", "Bíceps" to 0.5, "Antebrazo" to 0.25)
        assertTrue(validateExercise(ex).isEmpty())
    }

    // --- último ítem del día ------------------------------------------------

    @Test
    fun `el ultimo item del dia no puede tener superserie`() {
        val ex = exercise("ok")
        val d = day(
            item("a", ex, supersetNext = true),
            item("b", ex, supersetNext = true),
        )
        assertEquals(listOf(SupersetOnLastItem("b")), validateDay(d))
    }

    @Test
    fun `dia sin superserie en el ultimo item es valido`() {
        val ex = exercise("ok")
        val d = day(
            item("a", ex, supersetNext = true),
            item("b", ex),
        )
        assertTrue(validateDay(d).isEmpty())
    }

    // --- override de descanso ----------------------------------------------

    @Test
    fun `override de descanso cero o ausente es valido`() {
        val ex = exercise("ok")
        assertTrue(validateItem(item("a", ex, restOverrideSeconds = 0)).isEmpty())
        assertTrue(validateItem(item("b", ex, restOverrideSeconds = null)).isEmpty())
        assertTrue(validateItem(item("c", ex, restOverrideSeconds = 90)).isEmpty())
    }

    @Test
    fun `override de descanso negativo es error tipado`() {
        val ex = exercise("ok")
        val error = validateItem(item("a", ex, restOverrideSeconds = -1)).single() as NegativeRestOverride
        assertEquals(NegativeRestOverride("a", -1), error)
        assertEquals(EditorErrorCode.NEGATIVE_REST_OVERRIDE, error.code)
    }

    // --- composición --------------------------------------------------------

    @Test
    fun `validateDay junta los errores del dia y valida cada ejercicio una sola vez`() {
        val malo = exercise("malo", "Pecho", "Tríceps" to 1.5)

        val errors = validateDay(
            day(
                item("a", malo, sets = 0, restOverrideSeconds = -5),
                item("b", malo, sets = 21),
            ),
        )

        assertEquals(
            listOf(
                SetsOutOfRange("a", 0),
                NegativeRestOverride("a", -5),
                SetsOutOfRange("b", 21),
                SecondaryFactorOutOfRange("malo", "Tríceps", 1.5),
            ),
            errors,
        )
    }

    @Test
    fun `un dia completo valido no devuelve errores`() {
        val press = exercise("press", "Pecho", "Tríceps" to 0.5)
        val curl = exercise("curl", "Bíceps", "Antebrazo" to 0.25)
        val d = day(
            item("a", press, sets = 20, supersetNext = true, restOverrideSeconds = 60),
            item("b", curl, sets = 1, restOverrideSeconds = 0),
        )
        assertTrue(validateDay(d).isEmpty())
    }

    @Test
    fun `los codigos de error son estables`() {
        assertEquals(
            listOf(
                EditorErrorCode.SUPERSET_ON_LAST_ITEM,
                EditorErrorCode.SETS_OUT_OF_RANGE,
                EditorErrorCode.SECONDARY_FACTOR_OUT_OF_RANGE,
                EditorErrorCode.DUPLICATE_SECONDARY,
                EditorErrorCode.NEGATIVE_REST_OVERRIDE,
            ),
            listOf(
                SupersetOnLastItem("a").code,
                SetsOutOfRange("a", 0).code,
                SecondaryFactorOutOfRange("e", "m", 2.0).code,
                DuplicateSecondary("e", "m", 2).code,
                NegativeRestOverride("a", -1).code,
            ),
        )
        // `subjectId` es el ítem o el ejercicio que origina el error.
        assertEquals("a", SupersetOnLastItem("a").subjectId)
        assertEquals("e", duplicateSubject())
    }

    private fun duplicateSubject(): String = DuplicateSecondary("e", "m", 2).subjectId
}
