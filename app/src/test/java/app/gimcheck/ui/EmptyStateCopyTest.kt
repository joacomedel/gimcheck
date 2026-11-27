package app.gimcheck.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gimcheck.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * El copy de los estados vacíos es el de `docs/diseno.md` §9, **carácter por carácter**
 * (incluida la tilde de "Todavía" y el punto final). Es el único pedido del contrato que
 * vive en `strings.xml` y no en Kotlin, así que sin este test se puede "mejorar" el copy
 * sin darse cuenta de que el contrato lo fija.
 *
 * Robolectric sólo para leer recursos (ya está en el módulo por Room): no renderiza nada.
 */
@RunWith(RobolectricTestRunner::class)
class EmptyStateCopyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun copy(id: Int): String = context.getString(id)

    @Test
    fun `la frase y la accion de cada pantalla son las del contrato`() {
        assertEquals("Todavía no hay rutinas.", copy(R.string.empty_hoy_text))
        assertEquals("Crear rutina", copy(R.string.empty_hoy_action))

        assertEquals("Este día no tiene ejercicios.", copy(R.string.empty_rutinas_text))
        assertEquals("Agregar ejercicio", copy(R.string.empty_rutinas_action))

        assertEquals("No hay ejercicios con ese músculo.", copy(R.string.empty_ejercicios_text))
        assertEquals("Limpiar filtro", copy(R.string.empty_ejercicios_action))

        assertEquals("Sin datos todavía. Anotá una sesión.", copy(R.string.empty_progreso_text))
        assertEquals("Ir a Hoy", copy(R.string.empty_progreso_action))

        assertEquals("No hay sesiones anotadas.", copy(R.string.empty_historial_text))
        assertEquals("Entrenar", copy(R.string.empty_historial_action))

        assertEquals("Necesitás dos rutinas para comparar.", copy(R.string.empty_comparar_text))
        assertEquals("Crear rutina", copy(R.string.empty_comparar_action))
    }

    @Test
    fun `las nueve pantallas tienen nombre en la barra o en el titulo`() {
        // §12: los nombres son los de la tabla de §8, en sentence case y sin "Pantalla de".
        assertEquals("Hoy", copy(R.string.nav_hoy))
        assertEquals("Rutinas", copy(R.string.nav_rutinas))
        assertEquals("Progreso", copy(R.string.nav_progreso))
        assertEquals("Historial", copy(R.string.nav_historial))
        assertEquals("Ajustes", copy(R.string.nav_ajustes))
        assertEquals("Entrenar", copy(R.string.nav_entrenar))
        assertEquals("Descanso terminado", copy(R.string.nav_descanso))
        assertEquals("Ejercicios", copy(R.string.nav_ejercicios))
        assertEquals("Comparar", copy(R.string.nav_comparar))
    }
}
