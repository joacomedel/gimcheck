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
import androidx.compose.ui.geometry.Size
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
 * Gráfico de barras (§6): barras de 12 dp con 4 dp de separación, sin sombra ni
 * rejilla, `accent` como única serie. El encabezado muestra la barra tocada; la barra
 * más alta queda resaltada cuando no hay selección.
 *
 * Pensado para pocas barras (hasta ~12 semanas/sesiones). El ViewModel recorta el
 * rango; el gráfico no decide qué datos entran.
 */
@Composable
fun BarChart(
    labels: List<String>,
    values: List<Double>,
    modifier: Modifier = Modifier,
    unit: String = "",
    height: Dp = 160.dp,
    axisFormatter: (Double) -> String = { formatCompact(it) },
) {
    val colors = GimTheme.colors
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
    val axis = remember(values) { niceAxis(values, includeZero = true) }
    val maxIndex = values.indices.maxByOrNull { values[it] } ?: 0
    val highlight = selected ?: maxIndex

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
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(labels.size, values) {
                    detectTapGestures { offset ->
                        val count = values.size
                        if (count == 0) return@detectTapGestures
                        val slot = size.width.toFloat() / count
                        selected = (offset.x / slot).toInt().coerceIn(0, count - 1)
                    }
                },
        ) {
            val bottom = size.height - 4.dp.toPx()
            val usable = bottom.coerceAtLeast(1f)
            val count = values.size
            val slot = size.width / count
            val gap = 4.dp.toPx()
            val barWidth = minOf(12.dp.toPx(), (slot - gap).coerceAtLeast(2.dp.toPx()))
            fun yOf(value: Double): Float = bottom - axis.fraction(value) * usable

            drawLine(
                color = colors.line,
                start = Offset(0f, bottom),
                end = Offset(size.width, bottom),
                strokeWidth = 1.dp.toPx(),
            )

            values.forEachIndexed { index, value ->
                val x = slot * index + (slot - barWidth) / 2f
                val top = yOf(value)
                drawRect(
                    color = if (index == highlight) colors.accent else colors.accent.copy(alpha = 0.60f),
                    topLeft = Offset(x, top),
                    size = Size(barWidth, (bottom - top).coerceAtLeast(0f)),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(axisFormatter(axis.min), style = GimText.etiqueta, color = colors.muted)
            Text(axisFormatter(axis.mid), style = GimText.etiqueta, color = colors.muted)
            Text(axisFormatter(axis.max), style = GimText.etiqueta, color = colors.muted)
        }
    }
}
