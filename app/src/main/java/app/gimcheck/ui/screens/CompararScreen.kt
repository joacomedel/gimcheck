package app.gimcheck.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import app.gimcheck.R
import app.gimcheck.core.VolumeBand
import app.gimcheck.ui.LocalAppContainer
import app.gimcheck.ui.components.ChipFilter
import app.gimcheck.ui.components.EmptyState
import app.gimcheck.ui.components.Meter
import app.gimcheck.ui.components.SectionHeader
import app.gimcheck.ui.theme.GimRadius
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimStroke
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * **Comparar** — §6 fila 7 y `docs/diseno.md` §8 #8 (GIM-19).
 *
 * Paridad con la pestaña Análisis de la web: dos selectores (A/B), tabla resumen con
 * delta, duración por día y series por músculo en barras horizontales (`Meter`), con
 * el rango de referencia del plan. El delta se lee **B − A**; en conflictos y
 * superseries pesadas menos es mejor y el color lo refleja.
 *
 * Se abre desde Progreso. Si hay menos de dos planes, el estado vacío del contrato
 * manda a crear uno; no se inventa una comparación contra la nada.
 */
@Composable
fun CompararScreen(
    onCreateRoutine: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    if (container == null) {
        EmptyState(
            text = stringResource(R.string.empty_comparar_text),
            actionLabel = stringResource(R.string.empty_comparar_action),
            onAction = onCreateRoutine,
            modifier = modifier,
        )
        return
    }

    val scope = rememberCoroutineScope()
    val viewModel = remember(container) { CompararViewModel(container, scope) }
    LaunchedEffect(viewModel) { viewModel.start() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stop() }
    }

    val state by viewModel.state.collectAsState()
    if (!state.loading && state.routines.size < 2) {
        EmptyState(
            text = stringResource(R.string.empty_comparar_text),
            actionLabel = stringResource(R.string.empty_comparar_action),
            onAction = onCreateRoutine,
            modifier = modifier,
        )
        return
    }

    CompararContent(
        state = state,
        onA = viewModel::selectA,
        onB = viewModel::selectB,
        onToggleIndirect = viewModel::toggleIndirect,
        modifier = modifier,
    )
}

@Composable
private fun CompararContent(
    state: CompararUiState,
    onA: (String) -> Unit,
    onB: (String) -> Unit,
    onToggleIndirect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GimTheme.colors
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = GimSpacing.l),
        contentPadding = PaddingValues(top = GimSpacing.l, bottom = GimSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(GimSpacing.s),
    ) {
        item {
            Text(text = stringResource(R.string.nav_comparar), style = GimText.titulo, color = colors.ink)
            Text(
                text = stringResource(R.string.comparar_hint),
                style = GimText.detalle,
                color = colors.muted,
            )
        }

        item {
            Text(
                text = stringResource(R.string.comparar_plan_a),
                style = GimText.etiqueta,
                color = colors.muted,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(GimSpacing.s),
            ) {
                state.routines.forEach { routine ->
                    ChipFilter(
                        label = routine.name,
                        selected = routine.id == state.aId,
                        onClick = { onA(routine.id) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.comparar_plan_b),
                style = GimText.etiqueta,
                color = colors.muted,
                modifier = Modifier.padding(top = GimSpacing.s),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(GimSpacing.s),
            ) {
                state.routines.forEach { routine ->
                    ChipFilter(
                        label = routine.name,
                        selected = routine.id == state.bId,
                        onClick = { onB(routine.id) },
                    )
                }
            }
        }

        item {
            SectionHeader(title = stringResource(R.string.comparar_resumen))
            TableHeader()
        }
        items(state.metrics, key = { it.label }) { metric ->
            MetricRow(metric)
        }

        item {
            SectionHeader(title = stringResource(R.string.comparar_por_dia))
        }
        items(state.days) { day ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = GimSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GimSpacing.s),
            ) {
                Text(
                    text = day.label,
                    style = GimText.detalle,
                    color = colors.ink2,
                    maxLines = 1,
                    modifier = Modifier.weight(1.6f),
                )
                Text(day.a, style = GimText.detalle, color = colors.ink, modifier = Modifier.weight(0.7f), textAlign = TextAlign.End)
                Text(day.b, style = GimText.detalle, color = colors.ink, modifier = Modifier.weight(0.7f), textAlign = TextAlign.End)
                Text(day.delta, style = GimText.etiqueta, color = colors.muted, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(
                    title = stringResource(R.string.comparar_musculos),
                    modifier = Modifier.weight(1f),
                )
                ChipFilter(
                    label = stringResource(R.string.progreso_indirectas),
                    selected = state.includeIndirect,
                    onClick = onToggleIndirect,
                )
            }
        }

        items(state.muscles, key = { it.id }) { muscle ->
            Column(verticalArrangement = Arrangement.spacedBy(GimSpacing.xs)) {
                Text(text = muscle.name, style = GimText.cuerpo, color = colors.ink)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "A",
                        style = GimText.etiqueta,
                        color = colors.muted,
                        modifier = Modifier.padding(end = GimSpacing.s),
                    )
                    Meter(
                        value = muscle.aValue,
                        max = muscle.max,
                        bandMin = muscle.bandMin,
                        bandMax = muscle.bandMax,
                        color = bandColor(muscle.bandA),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = muscle.aLabel,
                        style = GimText.etiqueta,
                        color = colors.ink,
                        textAlign = TextAlign.End,
                        modifier = Modifier.padding(start = GimSpacing.s),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "B",
                        style = GimText.etiqueta,
                        color = colors.muted,
                        modifier = Modifier.padding(end = GimSpacing.s),
                    )
                    Meter(
                        value = muscle.bValue,
                        max = muscle.max,
                        bandMin = muscle.bandMin,
                        bandMax = muscle.bandMax,
                        color = bandColor(muscle.bandB),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = muscle.bLabel,
                        style = GimText.etiqueta,
                        color = colors.ink,
                        textAlign = TextAlign.End,
                        modifier = Modifier.padding(start = GimSpacing.s),
                    )
                }
            }
        }
    }
}

@Composable
private fun TableHeader() {
    val colors = GimTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = GimSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(GimSpacing.s),
    ) {
        Text(
            text = stringResource(R.string.comparar_metrica),
            style = GimText.etiqueta,
            color = colors.muted,
            modifier = Modifier.weight(1.6f),
        )
        Text(
            text = stringResource(R.string.comparar_plan_a_corto),
            style = GimText.etiqueta,
            color = colors.muted,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.7f),
        )
        Text(
            text = stringResource(R.string.comparar_plan_b_corto),
            style = GimText.etiqueta,
            color = colors.muted,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.7f),
        )
        Text(
            text = stringResource(R.string.comparar_delta),
            style = GimText.etiqueta,
            color = colors.muted,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.8f),
        )
    }
}

@Composable
private fun MetricRow(metric: CompararMetricUi) {
    val colors = GimTheme.colors
    val deltaColor = when {
        metric.deltaSign == 0 -> colors.muted
        metric.lowerIsBetter -> if (metric.deltaSign < 0) colors.good else colors.bad
        else -> if (metric.deltaSign > 0) colors.good else colors.bad
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(GimRadius.control))
            .border(GimStroke.border, colors.line, RoundedCornerShape(GimRadius.control))
            .padding(horizontal = GimSpacing.m, vertical = GimSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GimSpacing.s),
    ) {
        Text(
            text = metric.label,
            style = GimText.detalle,
            color = colors.ink2,
            maxLines = 2,
            modifier = Modifier.weight(1.6f),
        )
        Text(
            text = metric.a,
            style = GimText.detalle,
            color = colors.ink,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.7f),
        )
        Text(
            text = metric.b,
            style = GimText.detalle,
            color = colors.ink,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.7f),
        )
        Text(
            text = metric.delta ?: "—",
            style = GimText.etiqueta,
            color = deltaColor,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.8f),
        )
    }
}

@Composable
private fun bandColor(band: VolumeBand): androidx.compose.ui.graphics.Color {
    val colors = GimTheme.colors
    return when (band) {
        VolumeBand.VERY_LOW, VolumeBand.LOW -> colors.warn
        VolumeBand.IN_RANGE -> colors.good
        VolumeBand.HIGH -> colors.accent
    }
}
