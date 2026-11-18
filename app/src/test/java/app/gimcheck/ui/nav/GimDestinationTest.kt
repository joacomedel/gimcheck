package app.gimcheck.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las nueve rutas de `docs/diseno.md` §8, sin render: el grafo se arma desde
 * [GimDestination], así que si la tabla y el enum se desincronizan esto falla antes que
 * el APK.
 *
 * Test JVM puro (no Robolectric): el enum no toca recursos ni Android, sólo ids.
 */
class GimDestinationTest {

    @Test
    fun `el grafo tiene las nueve rutas de la tabla`() {
        assertEquals(9, GimDestination.entries.size)
        assertEquals(
            listOf(
                "hoy",
                "rutinas",
                "progreso",
                "historial",
                "ajustes",
                "entrenar",
                "descanso",
                "ejercicios",
                "comparar",
            ),
            GimDestination.entries.map { it.route },
        )
    }

    @Test
    fun `la barra inferior son cinco destinos en el orden del contrato`() {
        assertEquals(5, GimDestination.bottomBar.size)
        assertEquals(
            listOf(
                GimDestination.HOY,
                GimDestination.RUTINAS,
                GimDestination.PROGRESO,
                GimDestination.HISTORIAL,
                GimDestination.AJUSTES,
            ),
            GimDestination.bottomBar,
        )
    }

    @Test
    fun `entrenar y descanso terminado quedan fuera de la barra`() {
        val fuera = GimDestination.entries.filterNot { it.inBottomBar }
        assertEquals(
            listOf(
                GimDestination.ENTRENAR,
                GimDestination.DESCANSO,
                GimDestination.EJERCICIOS,
                GimDestination.COMPARAR,
            ),
            fuera,
        )
    }

    @Test
    fun `las rutas son unicas y no llevan barras`() {
        val routes = GimDestination.entries.map { it.route }
        assertEquals("hay rutas repetidas", routes.size, routes.toSet().size)
        routes.forEach { route ->
            assertTrue(
                "la ruta debe ser una sola palabra en minúsculas: $route",
                Regex("[a-z]+").matches(route),
            )
        }
    }

    @Test
    fun `cada destino tiene label y el arranque es Hoy`() {
        GimDestination.entries.forEach { destination ->
            assertTrue("falta labelRes en $destination", destination.labelRes != 0)
        }
        assertEquals(GimDestination.HOY, GimDestination.START)
        assertEquals(GimDestination.HOY, GimDestination.byRoute("hoy"))
        assertEquals(GimDestination.DESCANSO, GimDestination.byRoute("descanso"))
        assertNull(GimDestination.byRoute("no-existe"))
        assertNull(GimDestination.byRoute(null))
    }
}
