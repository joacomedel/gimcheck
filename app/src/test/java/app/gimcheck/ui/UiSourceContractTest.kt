package app.gimcheck.ui

import app.gimcheck.ui.nav.GimDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Las dos reglas de `docs/diseno.md` que no se pueden verificar desde una firma ni desde
 * un recurso:
 *
 * 1. **Ningún color literal fuera de `ui/theme/Tokens.kt`** (§3: "`Color(0xFF…)` fuera de
 *    ese archivo es bug de review"). Se leen las fuentes; si el árbol no está a mano (otro
 *    layout del repo) el test se saltea en vez de fallar por el path.
 * 2. **El grafo tiene las nueve rutas**, y el botón primario usa la altura del contrato en
 *    vez de un `52.dp` suelto.
 *
 * Es un test de texto a propósito: la alternativa (render) no existe en este equipo
 * (§11) y el criterio del ticket es justamente "sin colores literales" y "52 dp".
 */
class UiSourceContractTest {

    private val uiDir: File? = listOf(
        "src/main/java/app/gimcheck/ui",
        "app/src/main/java/app/gimcheck/ui",
    ).map { File(it) }.firstOrNull { it.isDirectory }

    private fun sources(dir: File): List<File> = dir.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    @Test
    fun `ninguna pantalla ni componente escribe un color literal`() {
        val dir = uiDir
        assumeTrue("no encuentro app/src/main/java/app/gimcheck/ui desde ${File(".").absolutePath}", dir != null)
        if (dir == null) return

        val offenders = sources(dir)
            // `ui/theme/` es el único lugar con colores literales (Tokens.kt).
            .filterNot { it.path.contains("${File.separator}theme${File.separator}") }
            .filter { it.readText().contains("Color(0x") }
            .map { it.name }

        assertEquals(
            "colores literales fuera de ui/theme (usá GimTheme.colors): $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `el NavHost declara las nueve rutas del enum`() {
        val dir = uiDir
        assumeTrue("no encuentro las fuentes de ui", dir != null)
        if (dir == null) return

        val navHost = File(dir, "nav/GimNavHost.kt")
        assertTrue("falta ui/nav/GimNavHost.kt", navHost.isFile)

        val declared = Regex("""composable\(GimDestination\.(\w+)\.route\)""")
            .findAll(navHost.readText())
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            "el grafo no coincide con GimDestination",
            GimDestination.entries.map { it.name }.toSet(),
            declared,
        )
    }

    @Test
    fun `el boton primario usa la altura de 52 dp del token`() {
        val dir = uiDir
        assumeTrue("no encuentro las fuentes de ui", dir != null)
        if (dir == null) return

        val button = File(dir, "components/PrimaryButton.kt")
        assertTrue("falta ui/components/PrimaryButton.kt", button.isFile)

        val text = button.readText()
        assertTrue(
            "PrimaryButton debe usar heightIn(min = GimHeight.button), no un dp suelto",
            text.contains("heightIn(min = GimHeight.button)"),
        )
        assertTrue("PrimaryButton no debe hardcodear 52.dp", !text.contains("52.dp"))
    }
}
