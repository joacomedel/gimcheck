package app.gimcheck.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gimcheck.ui.theme.GimHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier as ReflectModifier

/**
 * El contrato de `docs/diseno.md` §5, verificado **sin render** (acá no hay emulador ni
 * SDK, §11): cada componente existe en su archivo y el arranque de su firma es el de la
 * tabla.
 *
 * Por qué reflexión y no un test de UI: un `createComposeRule` necesita emulador o
 * Robolectric con `ui-test`, y lo que este ticket promete es que **la firma** y las
 * alturas son las del contrato. Lo que se pinta se mira en el teléfono.
 *
 * Composable de archivo único: `StatTile.kt` compila a `StatTileKt` con `StatTile` como
 * método estático (y `StatTile$default` para los parámetros con default; se filtra por
 * nombre exacto). `Meter` es la excepción: recibe `Color` y `Dp`, que son `value class`,
 * así que el compilador le cambia el nombre por `Meter-<hash>`; se busca por prefijo.
 */
class ComponentContractTest {

    private fun composable(file: String, name: String): Method {
        val cls = Class.forName("app.gimcheck.ui.components.$file")
        val candidates = cls.methods.filter {
            ReflectModifier.isStatic(it.modifiers) && it.name == name
        }
        assertTrue("no existe el composable $name en $file.kt", candidates.isNotEmpty())
        return candidates.first()
    }

    private fun params(file: String, name: String): List<Class<*>> =
        composable(file, name).parameterTypes.toList()

    @Test
    fun `los siete componentes del contrato existen`() {
        // Cada nombre se busca en su archivo: si alguien renombra un archivo o un
        // composable, esto falla antes que el APK.
        val files = mapOf(
            "StatTile" to "StatTile",
            "SetStepper" to "SetStepper",
            "ChipFilter" to "ChipFilter",
            "EmptyState" to "EmptyState",
            "ConfirmInline" to "ConfirmInline",
            "SectionHeader" to "SectionHeader",
            "PrimaryButton" to "PrimaryButton",
        )
        files.forEach { (name, file) -> composable(file, name) }
    }

    @Test
    fun `StatTile recibe k v y s como pide el contrato`() {
        assertEquals(
            listOf(String::class.java, String::class.java, String::class.java, Modifier::class.java),
            params("StatTile", "StatTile").take(4),
        )
    }

    @Test
    fun `SetStepper recibe value step onValue label`() {
        assertEquals(
            listOf(
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                Function1::class.java,
                String::class.java,
            ),
            params("SetStepper", "SetStepper").take(4),
        )
    }

    @Test
    fun `ChipFilter recibe label selected onClick`() {
        assertEquals(
            listOf(
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Function1::class.java,
                Modifier::class.java,
            ),
            params("ChipFilter", "ChipFilter").take(4),
        )
    }

    @Test
    fun `EmptyState recibe text actionLabel y onAction`() {
        assertEquals(
            listOf(
                String::class.java,
                String::class.java,
                Function1::class.java,
                Modifier::class.java,
            ),
            params("EmptyState", "EmptyState").take(4),
        )
    }

    @Test
    fun `ConfirmInline recibe question onConfirm y onCancel`() {
        assertEquals(
            listOf(
                String::class.java,
                Function1::class.java,
                Function1::class.java,
                Modifier::class.java,
            ),
            params("ConfirmInline", "ConfirmInline").take(4),
        )
    }

    @Test
    fun `SectionHeader recibe title y una action opcional`() {
        assertEquals(
            listOf(
                String::class.java,
                Modifier::class.java,
                String::class.java,
                Function1::class.java,
            ),
            params("SectionHeader", "SectionHeader").take(4),
        )
    }

    @Test
    fun `Meter es una barra con value max banda y color`() {
        val cls = Class.forName("app.gimcheck.ui.components.MeterKt")
        val meter = cls.methods.firstOrNull {
            ReflectModifier.isStatic(it.modifiers) && it.name.startsWith("Meter")
        }
        assertTrue("no existe Meter en Meter.kt", meter != null)
        val types = meter!!.parameterTypes
        // value, max, bandMin, bandMax, color, modifier, height + Composer + changes
        assertTrue("Meter perdió parámetros: ${types.toList()}", types.size >= 7)
        assertEquals(Float::class.javaPrimitiveType, types[0])
        assertEquals(Float::class.javaPrimitiveType, types[1])
        assertEquals(Modifier::class.java, types[5])
    }

    @Test
    fun `el boton primario tiene label onClick y enabled`() {
        assertEquals(
            listOf(
                String::class.java,
                Function1::class.java,
                Modifier::class.java,
                Boolean::class.javaPrimitiveType,
            ),
            params("PrimaryButton", "PrimaryButton").take(4),
        )
    }

    @Test
    fun `las alturas son las de la tabla de tokens`() {
        assertEquals("botón primario (§3.2)", 52.dp, GimHeight.button)
        assertEquals("fila tocable", 48.dp, GimHeight.row)
        assertEquals("chip", 44.dp, GimHeight.chip)
        assertEquals("stepper chico", 40.dp, GimHeight.stepper)
        assertEquals("ExerciseRow", 56.dp, GimHeight.exerciseRow)
        assertEquals("Meter", 20.dp, GimHeight.meter)
    }
}
