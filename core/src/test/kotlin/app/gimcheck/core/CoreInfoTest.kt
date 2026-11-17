package app.gimcheck.core

import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class CoreInfoTest {

    @Test
    fun `describe incluye nombre y version del modulo`() {
        assertEquals("gimcheck-core", CoreInfo.MODULE)
        assertEquals("GimCheck core 0.1.0", CoreInfo.describe())
    }

    @Test
    fun `kotlinx-datetime resuelve y formatea fechas ISO`() {
        // Smoke test de la dependencia que usará el dominio para semanas ISO.
        val date = LocalDate(2026, 11, 17)
        assertEquals("2026-11-17", date.toString())
    }
}
