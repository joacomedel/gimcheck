package app.gimcheck.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gimcheck.R
import app.gimcheck.core.formatNumber
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * Gráfico de línea con área al 10 % (§6): línea de 2 dp, puntos de 4 dp **sólo** en el
 * último valor y en los índices marcados (los PR), eje con tres marcas y sin rejilla.
 *
 * La interacción es tocar el gráfico: se elige el punto más cercano en X y el
 * encabezado muestra `fecha · valor`. No hay zoom ni animación de entrada; el Canvas se
 * redibuja con los datos que ya vienen calculados por el ViewModel.
 *
 * @param labels etiqueta corta de cada punto (`18 nov`), mismo tamaño que [values].
 * @param secondary serie tenue opcional (promedio de 7 días del peso corporal).
 * @param unit sufijo del valor (` kg`); vacío para PR.
 * @param markIndices índices que llevan punto además del último (PRs).
 */
@Composable
fun LineChart(
    labels: List<String>,
    values: List<Double>,
    modifier: Modifier = Modifier,
    secondary: List<Double?> = emptyList(),
    unit: String = "",
    height: Dp = 160.dp,
    includeZero: Boolean = false,
    markIndices: Set<Int> = emptySet(),
    secondaryLabel: String? = null,
) {
    val colors = GimTheme.colors
    val darkSurface = colors.surface
    if (values.isEmpty()) {
        Text(
            text = stringResource(R.string.chart_sin_datos),
            style = GimText.detalle,
            color = colors.muted,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }

    var selected by remember(labels, values) { mutableStateOf<Int?>(null) }
    val axis = remember(values, secondary, includeZero) {
        val all = values + secondary.filterNotNull()
        niceAxis(all, includeZero = includeZero)
    }
    val highlight = selected ?: values.lastIndex
    val average = secondary.getOrNull(highlight)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GimSpacing.s),
        ) {
            Text(
                text = labels.getOrElse(highlight) { "" },
                style = GimText.etiqueta,
                color = colors.muted,
                maxLines = 1,
            )
            Text(
                text = formatNumber(values.getOrElse(highlight) { 0.0 }) + unit,
                style = GimText.subtitulo,
                color = colors.ink,
                maxLines = 1,
            )
            if (average != null && secondaryLabel != null) {
                Text(
                    text = "$secondaryLabel ${formatNumber(average)}$unit",
                    style = GimText.etiqueta,
                    color = colors.muted,
                    maxLines = 1,
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(labels.size, values) {
                    detectTapGestures { offset ->
                        val count = values.size
                        if (count == 0) return@detectTapGestures
                        if (count == 1) {
                            selected = 0
                            return@detectTapGestures
                        }
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        selected = (fraction * (count - 1)).toInt().coerceIn(0, count - 1)
                    }
                },
        ) {
            val top = 8.dp.toPx()
            val bottom = size.height - 8.dp.toPx()
            val usable = (bottom - top).coerceAtLeast(1f)
            fun xOf(index: Int): Float = xFraction(index, values.size) * size.width
            fun yOf(value: Double): Float = bottom - axis.fraction(value) * usable

            // Línea base: la única horizontal permitida (§6).
            drawLine(
                color = colors.line,
                start = Offset(0f, bottom),
                end = Offset(size.width, bottom),
                strokeWidth = 1.dp.toPx(),
            )

            // Área al 10 %.
            if (values.size > 1) {
                val area = Path().apply {
                    moveTo(xOf(0), bottom)
                    values.forEachIndexed { index, value -> lineTo(xOf(index), yOf(value)) }
                    lineTo(xOf(values.lastIndex), bottom)
                    close()
                }
                drawPath(area, color = colors.accent.copy(alpha = 0.10f))
            }

            // Serie secundaria (promedio).
            if (secondary.size == values.size && secondary.count { it != null } > 1) {
                val path = Path()
                var started = false
                secondary.forEachIndexed { index, value ->
                    if (value == null) return@forEachIndexed
                    val x = xOf(index)
                    val y = yOf(value)
                    if (!started) {
                        path.moveTo(x, y)
                        started = true
                    } else {
                        path.lineTo(x, y)
                    }
                }
                drawPath(path, color = colors.muted.copy(alpha = 0.55f), style = Stroke(width = 2.dp.toPx()))
            }

            // Serie principal.
            if (values.size > 1) {
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = xOf(index)
                    val y = yOf(value)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = colors.accent, style = Stroke(width = 2.dp.toPx()))
            }

            // Puntos: último, seleccionado y PRs.
            val marked = markIndices + values.lastIndex + highlight
            marked.forEach { index ->
                if (index !in values.indices) return@forEach
                drawCircle(
                    color = if (index == highlight) colors.accent else darkSurface,
                    radius = 4.dp.toPx(),
                    center = Offset(xOf(index), yOf(values[index])),
                )
                if (index == highlight) {
                    drawCircle(
                        color = colors.accent,
                        radius = 4.dp.toPx(),
                        center = Offset(xOf(index), yOf(values[index])),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTick(axis.min), style = GimText.etiqueta, color = colors.muted)
            Text(formatTick(axis.mid), style = GimText.etiqueta, color = colors.muted)
            Text(formatTick(axis.max), style = GimText.etiqueta, color = colors.muted)
        }
    }
}
