package app.gimcheck.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parseo del valor escrito en el stepper (GIM-48).
 *
 * La UI es de Compose y no se renderiza en este equipo (`docs/build.md`), pero la regla que
 * puede romper el dato —coma decimal, redondeo, rango— es una función pura y se prueba acá.
 */
class SetStepperTest {

    @Test
    fun `acepta coma y punto decimal`() {
        assertEquals(82.5, requireNotNull(parseStepperInput("82,5", 0.0, 999.0)), 1e-9)
        assertEquals(82.5, requireNotNull(parseStepperInput("82.5", 0.0, 999.0)), 1e-9)
        assertEquals(100.0, requireNotNull(parseStepperInput(" 100 ", 0.0, 999.0)), 1e-9)
    }

    @Test
    fun `un texto que no es numero no cambia el valor`() {
        assertNull(parseStepperInput("", 0.0, 999.0))
        assertNull(parseStepperInput("abc", 0.0, 999.0))
        assertNull(parseStepperInput("8,2,5", 0.0, 999.0))
    }

    @Test
    fun `recorta al rango en vez de descartar`() {
        assertEquals(0.0, requireNotNull(parseStepperInput("-5", 0.0, 999.0)), 1e-9)
        assertEquals(999.0, requireNotNull(parseStepperInput("1200", 0.0, 999.0)), 1e-9)
    }

    @Test
    fun `las reps se redondean a entero`() {
        assertEquals(8.0, requireNotNull(parseStepperInput("7,5", 0.0, 100.0, wholeNumbers = true)), 1e-9)
        assertEquals(8.0, requireNotNull(parseStepperInput("7.8", 0.0, 100.0, wholeNumbers = true)), 1e-9)
        assertEquals(100.0, requireNotNull(parseStepperInput("180", 0.0, 100.0, wholeNumbers = true)), 1e-9)
    }

    @Test
    fun `el peso queda con un decimal, que es la precision del contrato`() {
        assertEquals(82.5, requireNotNull(parseStepperInput("82,54", 0.0, 999.0)), 1e-9)
        assertEquals(82.6, requireNotNull(parseStepperInput("82,56", 0.0, 999.0)), 1e-9)
    }

    @Test
    fun `el formateo usa coma y sin ceros de relleno`() {
        assertEquals("82,5", formatStepperValue(82.5))
        assertEquals("82", formatStepperValue(82.0))
        assertEquals("0", formatStepperValue(0.0))
    }
}
