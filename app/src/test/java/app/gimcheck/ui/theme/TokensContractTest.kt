package app.gimcheck.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * El contrato visual de `docs/diseno.md` §3 y §4, verificado sobre los tokens.
 *
 * Tres cosas se comprueban acá, y ninguna es cosmética:
 * 1. **Los valores son los del documento** (si alguien cambia un token sin actualizar
 *    `docs/diseno.md`, este test no lo detecta; si cambia el documento y no el token,
 *    tampoco — lo que detecta es un cambio de token sin decisión, y obliga a tocar la
 *    tabla de arriba a la vez).
 * 2. **Contraste WCAG 2.1 ≥ 4.5:1** de cada par texto/fondo, calculado con la fórmula
 *    real. El criterio del ticket es `ink`/`surface`; acá están todos los pares de texto
 *    porque `muted` a 13 sp o `accent` como texto de acción fallan a 4.0.
 * 3. **La proyección a Material 3**: un rol sin mapear cae al default de Material (lila,
 *    Roboto) y una pantalla que use `Button` se ve de otra app.
 *
 * Corre en la JVM: `Color`, `TextStyle` y `Typography` son Kotlin puro, no tocan Android.
 */
class TokensContractTest {

    // --- color: los valores del documento ------------------------------------

    /** Tabla `docs/diseno.md` §3.1. Oscuro primero, como el contrato. */
    private val contratoOscuro: Map<String, Color> = mapOf(
        "bg" to Color(0xFF121211),
        "surface" to Color(0xFF1A1A19),
        "surface2" to Color(0xFF262625),
        "line" to Color(0xFF34332F),
        "ink" to Color(0xFFF0EFEC),
        "ink2" to Color(0xFFC3C2B7),
        "muted" to Color(0xFF8F8E86),
        "accent" to Color(0xFF3987E5),
        "onAccent" to Color(0xFF121211),
        "good" to Color(0xFF4CC27F),
        "warn" to Color(0xFFE0A03A),
        "bad" to Color(0xFFEC7A78),
        "series2" to Color(0xFFD95926),
        "series3" to Color(0xFF199E70),
    )

    private val contratoClaro: Map<String, Color> = mapOf(
        "bg" to Color(0xFFF4F4F1),
        "surface" to Color(0xFFFCFCFB),
        "surface2" to Color(0xFFECECE7),
        "line" to Color(0xFFD9D8D1),
        "ink" to Color(0xFF0B0B0B),
        "ink2" to Color(0xFF52514E),
        "muted" to Color(0xFF706F6A),
        "accent" to Color(0xFF2770C7),
        "onAccent" to Color(0xFFFFFFFF),
        "good" to Color(0xFF0A7A3D),
        "warn" to Color(0xFFA35F00),
        "bad" to Color(0xFFC0302E),
        "series2" to Color(0xFFEB6834),
        "series3" to Color(0xFF1BAF7A),
    )

    private fun token(paleta: GimColors, nombre: String): Color = when (nombre) {
        "bg" -> paleta.bg
        "surface" -> paleta.surface
        "surface2" -> paleta.surface2
        "line" -> paleta.line
        "ink" -> paleta.ink
        "ink2" -> paleta.ink2
        "muted" -> paleta.muted
        "accent" -> paleta.accent
        "onAccent" -> paleta.onAccent
        "good" -> paleta.good
        "warn" -> paleta.warn
        "bad" -> paleta.bad
        "series1" -> paleta.series1
        "series2" -> paleta.series2
        "series3" -> paleta.series3
        else -> error("token desconocido: $nombre")
    }

    @Test
    fun `el modo oscuro es el de la tabla del contrato`() {
        contratoOscuro.forEach { (nombre, esperado) ->
            assertEquals("oscuro.$nombre", esperado, token(DarkColors, nombre))
        }
    }

    @Test
    fun `el modo claro es el de la tabla del contrato`() {
        contratoClaro.forEach { (nombre, esperado) ->
            assertEquals("claro.$nombre", esperado, token(LightColors, nombre))
        }
    }

    @Test
    fun `series1 es el acento y no un color propio`() {
        // `Color` es un value class: `assertSame` compara cajas, así que la única forma
        // de verificarlo es por valor (`series1` es un getter de `accent`, no un campo).
        assertEquals(LightColors.accent, LightColors.series1)
        assertEquals(DarkColors.accent, DarkColors.series1)
    }

    @Test
    fun `los dos modos no comparten ningun color`() {
        // Si un token quedó igual en claro y oscuro es un copy/paste: el oscuro no es el
        // claro invertido, es una paleta propia (§3.1).
        contratoClaro.keys.forEach { nombre ->
            assertNotEquals("claro.$nombre == oscuro.$nombre", token(LightColors, nombre), token(DarkColors, nombre))
        }
    }

    // --- contraste ----------------------------------------------------------

    /** Luminancia relativa WCAG 2.1 (sRGB → lineal). */
    private fun luminancia(color: Color): Double {
        fun canal(valor: Float): Double {
            val c = valor.toDouble()
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * canal(color.red) + 0.7152 * canal(color.green) + 0.0722 * canal(color.blue)
    }

    private fun contraste(a: Color, b: Color): Double {
        val la = luminancia(a)
        val lb = luminancia(b)
        val claro = maxOf(la, lb)
        val oscuro = minOf(la, lb)
        return (claro + 0.05) / (oscuro + 0.05)
    }

    /**
     * Todos los pares que son **texto sobre un fondo** en alguna pantalla de §8.
     *
     * `line` queda afuera a propósito: es un borde de 1 dp, no texto (WCAG 1.4.11 le
     * pediría 3:1 como elemento no textual, y el contrato lo quiere discreto).
     * `series2`/`series3` también: son trazos de gráfico, no etiquetas.
     */
    private val paresDeTexto: List<Pair<String, Pair<String, String>>> = listOf(
        "ink sobre surface" to ("ink" to "surface"),
        "ink sobre bg" to ("ink" to "bg"),
        "ink sobre surface2" to ("ink" to "surface2"),
        "ink2 sobre surface" to ("ink2" to "surface"),
        "ink2 sobre bg" to ("ink2" to "bg"),
        "muted sobre surface" to ("muted" to "surface"),
        "muted sobre bg" to ("muted" to "bg"),
        "accent sobre surface" to ("accent" to "surface"),
        "accent sobre bg" to ("accent" to "bg"),
        "onAccent sobre accent" to ("onAccent" to "accent"),
        "good sobre surface" to ("good" to "surface"),
        "warn sobre surface" to ("warn" to "surface"),
        "bad sobre surface" to ("bad" to "surface"),
    )

    @Test
    fun `todo par de texto llega a 4 punto 5 a 1 en los dos modos`() {
        val fallas = mutableListOf<String>()
        listOf("claro" to LightColors, "oscuro" to DarkColors).forEach { (modo, paleta) ->
            paresDeTexto.forEach { (nombre, par) ->
                val ratio = contraste(token(paleta, par.first), token(paleta, par.second))
                if (ratio < MINIMO_TEXTO) {
                    fallas += "$modo: $nombre = %.2f:1".format(ratio)
                }
            }
        }
        assertTrue("pares por debajo de %.1f:1 → %s".format(MINIMO_TEXTO, fallas), fallas.isEmpty())
    }

    @Test
    fun `el par del criterio ink sobre surface pasa con margen`() {
        val claro = contraste(LightColors.ink, LightColors.surface)
        val oscuro = contraste(DarkColors.ink, DarkColors.surface)
        assertTrue("claro ink/surface = %.2f:1".format(claro), claro >= 12.0)
        assertTrue("oscuro ink/surface = %.2f:1".format(oscuro), oscuro >= 12.0)
    }

    // --- tipografía ---------------------------------------------------------

    /** Tabla `docs/diseno.md` §4, tamaño y familia. */
    private val escalaDelContrato: List<Triple<String, TextStyle, Float>> = listOf(
        Triple("display", GimText.display, 40f),
        Triple("stat", GimText.stat, 30f),
        Triple("titulo", GimText.titulo, 26f),
        Triple("section", GimText.section, 22f),
        Triple("subtitulo", GimText.subtitulo, 18f),
        Triple("cuerpo", GimText.cuerpo, 15f),
        Triple("detalle", GimText.detalle, 13f),
        Triple("etiqueta", GimText.etiqueta, 12f),
    )

    @Test
    fun `la escala tipografica es la del contrato`() {
        escalaDelContrato.forEach { (nombre, estilo, tamano) ->
            assertEquals("$nombre: tamaño", tamano.sp, estilo.fontSize)
        }
    }

    @Test
    fun `los numeros son tabulares`() {
        // §4: cifras tabulares en los números grandes (Barlow) y en el cuerpo que lleva
        // números en tablas (Comparar, Historial).
        listOf(GimText.display, GimText.stat, GimText.titulo, GimText.section, GimText.cuerpo, GimText.detalle)
            .forEach { estilo ->
                assertEquals(TABULAR_NUMBERS, estilo.fontFeatureSettings)
            }
    }

    @Test
    fun `solo se usan las dos familias y todos los roles de M3 estan mapeados`() {
        val familias = setOf<FontFamily?>(BarlowCondensed, IbmPlexSans)
        assertsRolesMapeados(GimTypography, familias)
    }

    /**
     * Los 15 roles de `Typography`. Si alguno quedó sin asignar, `fontFamily` es `null`
     * (hereda la default de Material) y el test falla: así se evita el `AlertDialog` en
     * Roboto dentro de una app de dos familias.
     */
    private fun assertsRolesMapeados(t: Typography, familias: Set<FontFamily?>) {
        val roles = mapOf(
            "displayLarge" to t.displayLarge,
            "displayMedium" to t.displayMedium,
            "displaySmall" to t.displaySmall,
            "headlineLarge" to t.headlineLarge,
            "headlineMedium" to t.headlineMedium,
            "headlineSmall" to t.headlineSmall,
            "titleLarge" to t.titleLarge,
            "titleMedium" to t.titleMedium,
            "titleSmall" to t.titleSmall,
            "bodyLarge" to t.bodyLarge,
            "bodyMedium" to t.bodyMedium,
            "bodySmall" to t.bodySmall,
            "labelLarge" to t.labelLarge,
            "labelMedium" to t.labelMedium,
            "labelSmall" to t.labelSmall,
        )
        roles.forEach { (rol, estilo) ->
            assertTrue("$rol sin familia del contrato (${estilo.fontFamily})", estilo.fontFamily in familias)
        }
    }

    // --- proyección a Material 3 --------------------------------------------

    @Test
    fun `el ColorScheme de Material3 sale de los tokens`() {
        listOf(LightColors, DarkColors).forEach { paleta ->
            val scheme = paleta.toColorScheme()
            val modo = if (paleta.isDark) "oscuro" else "claro"
            assertEquals("$modo primary", paleta.accent, scheme.primary)
            assertEquals("$modo onPrimary", paleta.onAccent, scheme.onPrimary)
            assertEquals("$modo background", paleta.bg, scheme.background)
            assertEquals("$modo onBackground", paleta.ink, scheme.onBackground)
            assertEquals("$modo surface", paleta.surface, scheme.surface)
            assertEquals("$modo onSurface", paleta.ink, scheme.onSurface)
            assertEquals("$modo outline", paleta.line, scheme.outline)
            assertEquals("$modo error", paleta.bad, scheme.error)
            // Las hojas (§5) usan surfaceContainerLow: si cae al default de Material, la
            // hoja sale de otro color que la tarjeta de atrás.
            assertEquals("$modo surfaceContainerLow", paleta.surface, scheme.surfaceContainerLow)
            assertEquals("$modo surfaceContainerHighest", paleta.surface2, scheme.surfaceContainerHighest)
            // Sin elevación tonal (§3.2): el tinte no puede cambiar el fondo.
            assertEquals("$modo surfaceTint", paleta.surface, scheme.surfaceTint)
        }
    }

    private companion object {
        /** WCAG 2.1 AA para texto normal (también sirve de base para el semáforo). */
        const val MINIMO_TEXTO: Double = 4.5
    }
}
