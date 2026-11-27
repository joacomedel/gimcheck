package app.gimcheck.ui

import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fechas de Historial y Progreso: lo único que se puede probar sin render. */
class UiFormatTest {

    @Test
    fun `una fecha corta y una larga en castellano`() {
        val date = LocalDate(2026, 11, 18)
        assertEquals("18 nov", shortDate(date))
        assertEquals("mié 18 nov", weekdayDate(date))
        assertEquals("miércoles 18 de noviembre", longDate(date))
        assertEquals("18 nov 2026", shortDateYear(date))
        assertEquals("nov 2026", monthYear(date))
    }

    @Test
    fun `la semana ISO va de lunes a domingo`() {
        assertEquals("16–22 nov", weekRangeLabel(LocalDate(2026, 11, 18)))
        assertEquals("16–22 nov", weekRangeLabel(LocalDate(2026, 11, 16)))
        assertEquals("16–22 nov", weekRangeLabel(LocalDate(2026, 11, 22)))
    }

    @Test
    fun `la semana que cruza de mes muestra los dos meses`() {
        assertEquals("28 dic – 3 ene", weekRangeLabel(LocalDate(2026, 12, 31)))
    }

    @Test
    fun `la hora de una serie no lleva cero a la izquierda`() {
        assertEquals("9:05", timeLabel(9, 5))
        assertEquals("10:30", timeLabel(10, 30))
    }
}
