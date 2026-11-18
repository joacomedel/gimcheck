package app.gimcheck.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gimcheck.data.settings.ThemeMode
import app.gimcheck.ui.theme.GimCheckTheme
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * `TimerRing` — `docs/diseno.md` §5: arco `accent` de 8 dp sobre `surface2`, **sin
 * animación de entrada**, tiempo en `display`.
 *
 * Es la pieza de la cuenta regresiva de Entrenar (§8 #2) y de Descanso terminado
 * (§8 #3). El arco se dibuja con `Canvas`, no con una librería: son dos `drawArc` y
 * una `Text` centrada, y así no entra una dependencia de gráficos por una circunferencia.
 *
 * `remaining`/`total` son segundos. La fracción es `remaining / total` y el arco se
 * dibuja **en sentido horario desde las 12** (−90°), igual que cualquier cronómetro. Si
 * `total <= 0` no se dibuja el arco de progreso: dividir por cero no es un estado.
 *
 * El tiempo se formatea acá con [formatRestClock] (`1:30`, nunca `01:30`): es el mismo
 * formato de §4 y el único lugar del código que decide cómo se ve un descanso.
 */
@Composable
fun TimerRing(
    remaining: Int,
    total: Int,
    modifier: Modifier = Modifier,
    size: Dp = 148.dp,
) {
    val colors = GimTheme.colors
    val fraction = if (total > 0) (remaining.toFloat() / total).coerceIn(0f, 1f) else 0f

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = 8.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)

            drawArc(
                color = colors.surface2,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            if (fraction > 0f) {
                drawArc(
                    color = colors.accent,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke),
                )
            }
        }
        Text(
            text = formatRestClock(remaining),
            style = GimText.display,
            color = colors.ink,
            maxLines = 1,
        )
    }
}

/**
 * Segundos → `m:ss` (`90 → 1:30`, `5 → 0:05`). Puro y separado del composable para
 * poder probarlo en la JVM: el contrato de §4 fija este formato y no un contador con
 * ceros a la izquierda en los minutos.
 *
 * Nunca imprime negativos: un descanso vencido se muestra `0:00`, no `-0:01`.
 */
internal fun formatRestClock(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val minutes = safe / 60
    val rest = safe % 60
    return "$minutes:${rest.toString().padStart(2, '0')}"
}

/** Documentación viva: no se renderiza acá (§11). */
@Preview(showBackground = true, backgroundColor = 0xFF121211)
@Composable
private fun TimerRingPreview() {
    GimCheckTheme(mode = ThemeMode.DARK) {
        TimerRing(remaining = 75, total = 120)
    }
}
