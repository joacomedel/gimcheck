package app.gimcheck.ui.components

import androidx.compose.ui.unit.dp
import app.gimcheck.ui.nav.GimDestination
import app.gimcheck.ui.theme.GimHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * El contrato de `docs/diseno.md` §5, verificado **sin render** (§11: acá no hay
 * emulador) y **sin reflexión sobre clases generadas**.
 *
 * La versión anterior usaba `Class.forName("...StatTile")` para leer las firmas de los
 * `@Composable`. Eso depende de nombres que genera el compilador (`StatTileKt`,
 * `Meter-<hash>` cuando hay `value class`), así que el test se rompía por un detalle del
 * compilador y no por el contrato: rojo en CI, verde en la cabeza de quien lo escribió.
 * Lo que el ticket promete y es estable se verifica así:
 *
 * 1. **Las alturas de `GimHeight`** (§3.2), que es de donde salen los 52 dp del botón.
 * 2. **Las nueve rutas de `GimDestination`** (§8), el enum puro que arma el grafo.
 * 3. **`EmptyState` usa el copy del contrato**: los textos viven en `strings.xml` (§9) y
 *    el composable recibe `text`/`actionLabel`, no los escribe adentro.
 *
 * La firma exacta de cada composable y lo que se pinta es review + GIM-15 (verificación
 * visual en el teléfono), no un test que adivina nombres de bytecode.
 */
class ComponentContractTest {

    private val uiDir: File? = listOf(
        "src/main/java/app/gimcheck/ui",
        "app/src/main/java/app/gimcheck/ui",
    ).map { File(it) }.firstOrNull { it.isDirectory }

    private val stringsXml: File? = listOf(
        "src/main/res/values/strings.xml",
        "app/src/main/res/values/strings.xml",
    ).map { File(it) }.firstOrNull { it.isFile }

    @Test
    fun `las alturas son las de la tabla de tokens`() {
        assertEquals("botón primario (§3.2)", 52.dp, GimHeight.button)
        assertEquals("fila tocable", 48.dp, GimHeight.row)
        assertEquals("chip", 44.dp, GimHeight.chip)
        assertEquals("stepper chico", 40.dp, GimHeight.stepper)
        assertEquals("ExerciseRow", 56.dp, GimHeight.exerciseRow)
        assertEquals("Meter", 20.dp, GimHeight.meter)
    }

    @Test
    fun `el grafo conserva las nueve rutas del contrato`() {
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
    fun `cada componente del contrato vive en su archivo`() {
        val dir = uiDir
        assumeTrue("no encuentro ui/components desde ${File(".").absolutePath}", dir != null)
        if (dir == null) return

        listOf(
            "StatTile",
            "Meter",
            "SetStepper",
            "ChipFilter",
            "EmptyState",
            "ConfirmInline",
            "SectionHeader",
            "PrimaryButton",
        ).forEach { name ->
            val file = File(dir, "components/$name.kt")
            assertTrue("falta ui/components/$name.kt", file.isFile)
            assertTrue(
                "ui/components/$name.kt no declara el composable $name",
                file.readText().contains("fun $name("),
            )
        }
    }

    @Test
    fun `EmptyState recibe el copy de la pantalla en vez de escribirlo`() {
        val dir = uiDir
        assumeTrue("no encuentro ui/components", dir != null)
        if (dir == null) return

        val text = File(dir, "components/EmptyState.kt").readText()
        // §9: 1 frase + 1 acción, las dos las pasa la pantalla.
        assertTrue("EmptyState debe recibir text: String", text.contains("text: String"))
        assertTrue(
            "EmptyState debe recibir actionLabel: String",
            text.contains("actionLabel: String"),
        )
        // La acción es el botón primario de 52 dp, no un link (§7.3).
        assertTrue(
            "EmptyState debe usar PrimaryButton(label = actionLabel, …)",
            text.contains("PrimaryButton(label = actionLabel"),
        )
    }

    @Test
    fun `el copy de los seis estados vacios es el de la tabla de §9`() {
        val xml = stringsXml
        assumeTrue("no encuentro res/values/strings.xml", xml != null)
        if (xml == null) return

        val text = xml.readText()
        val esperado = mapOf(
            "empty_hoy_text" to "Todavía no hay rutinas.",
            "empty_hoy_action" to "Crear rutina",
            "empty_rutinas_text" to "Este día no tiene ejercicios.",
            "empty_rutinas_action" to "Agregar ejercicio",
            "empty_ejercicios_text" to "No hay ejercicios con ese músculo.",
            "empty_ejercicios_action" to "Limpiar filtro",
            "empty_progreso_text" to "Sin datos todavía. Anotá una sesión.",
            "empty_progreso_action" to "Ir a Hoy",
            "empty_historial_text" to "No hay sesiones anotadas.",
            "empty_historial_action" to "Entrenar",
            "empty_comparar_text" to "Necesitás dos rutinas para comparar.",
            "empty_comparar_action" to "Crear rutina",
        )
        esperado.forEach { (name, copy) ->
            val regex = Regex("""<string name="$name"[^>]*>(.*?)</string>""")
            val real = regex.find(text)?.groupValues?.get(1)
            assertEquals("copy de $name (§9)", copy, real)
        }
    }
}
