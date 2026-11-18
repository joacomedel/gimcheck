package app.gimcheck.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import app.gimcheck.data.settings.ThemeMode
import app.gimcheck.ui.theme.GimCheckTheme
import app.gimcheck.ui.theme.GimHeight
import app.gimcheck.ui.theme.GimRadius
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimTheme

/**
 * `Meter` — `docs/diseno.md` §5: barra de 20 dp sobre `surface2`, **banda de referencia
 * detrás**, esquinas redondeadas **sólo a la derecha**.
 *
 * Se reusa para todo lo que es "una cantidad sobre un máximo": series hechas del bloque
 * (§8 #2), volumen por músculo en Progreso y Comparar (§6: "barras horizontales por
 * músculo: reusar `Meter`, no un gráfico nuevo").
 *
 * La banda (`bandMin`..`bandMax`) es el rango objetivo del plan: se pinta en `line`
 * debajo del relleno, así el ojo ve a la vez el valor y el objetivo. Si `max <= 0` la
 * barra queda vacía en vez de dividir por cero.
 *
 * Implementación sin `Canvas`: dos `Row` con `weight` sobre la pista. Una barra de 20 dp
 * no necesita shader, y así no hay `drawPhase` ni animación de entrada (§6).
 */
@Composable
fun Meter(
    value: Float,
    max: Float,
    bandMin: Float? = null,
    bandMax: Float? = null,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = GimHeight.meter,
) {
    val colors = GimTheme.colors
    val shape = RoundedCornerShape(topEnd = GimRadius.control, bottomEnd = GimRadius.control)
    val total = if (max > 0f) max else 0f
    val filled = if (total > 0f) (value / total).coerceIn(0f, 1f) else 0f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(colors.surface2),
    ) {
        if (bandMin != null && bandMax != null && total > 0f) {
            val from = (bandMin / total).coerceIn(0f, 1f)
            val width = ((bandMax / total).coerceIn(0f, 1f) - from).coerceAtLeast(0f)
            if (width > 0f) {
                Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                    if (from > 0f) Spacer(modifier = Modifier.weight(from))
                    Box(modifier = Modifier.weight(width).fillMaxHeight().background(colors.line))
                    val rest = 1f - from - width
                    if (rest > 0f) Spacer(modifier = Modifier.weight(rest))
                }
            }
        }

        if (filled > 0f) {
            Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                Box(modifier = Modifier.weight(filled).fillMaxHeight().background(color))
                if (filled < 1f) Spacer(modifier = Modifier.weight(1f - filled))
            }
        }
    }
}

/** Documentación viva: no se renderiza acá (§11). */
@Preview(showBackground = true, backgroundColor = 0xFF121211)
@Composable
private fun MeterPreview() {
    val colors = GimTheme.colors
    GimCheckTheme(mode = ThemeMode.DARK) {
        Box(modifier = Modifier.fillMaxWidth().padding(GimSpacing.l)) {
            Meter(value = 7f, max = 12f, bandMin = 8f, bandMax = 12f, color = colors.accent)
        }
    }
}
