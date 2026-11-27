package app.gimcheck.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Escalas de los gráficos: lo único de `ui/chart` que se puede probar sin render. */
class ChartScaleTest {

    @Test
    fun `el eje incluye los datos con aire y arranca en un numero lindo`() {
        val axis = niceAxis(listOf(116.66, 122.5, 126.66))
        assertTrue("el mínimo queda por debajo del dato", axis.min < 116.66)
        assertTrue("el máximo queda por arriba del dato", axis.max > 126.66)
        assertEquals(0.0, axis.min % 5.0, 1e-9)
    }

    @Test
    fun `un solo valor no deja el eje plano`() {
        val axis = niceAxis(listOf(100.0))
        assertTrue(axis.max > axis.min)
        assertEquals(0.5f, axis.fraction(axis.mid), 1e-6f)
    }

    @Test
    fun `el eje de barras incluye el cero`() {
        val axis = niceAxis(listOf(5000.0, 12000.0), includeZero = true)
        assertEquals(0.0, axis.min, 1e-9)
        assertTrue(axis.max >= 12000.0)
    }

    @Test
    fun `sin datos devuelve un eje seguro`() {
        val axis = niceAxis(emptyList())
        assertEquals(0.0, axis.min, 1e-9)
        assertEquals(1.0, axis.max, 1e-9)
    }

    @Test
    fun `los ticks usan coma y como maximo un decimal`() {
        assertEquals("82,5", formatTick(82.54))
        assertEquals("82,6", formatTick(82.56))
        assertEquals("100", formatTick(100.0))
        assertEquals("12,4 k", formatCompact(12400.0))
        assertEquals("950", formatCompact(950.0))
    }

    @Test
    fun `el paso es 1 2 o 5 por potencia de diez`() {
        assertEquals(1.0, niceStep(0.9), 1e-9)
        assertEquals(2.0, niceStep(1.4), 1e-9)
        assertEquals(5.0, niceStep(4.2), 1e-9)
        assertEquals(10.0, niceStep(8.0), 1e-9)
        assertEquals(0.5, niceStep(0.32), 1e-9)
    }
}
