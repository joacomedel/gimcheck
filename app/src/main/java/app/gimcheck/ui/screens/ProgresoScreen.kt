package app.gimcheck.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import app.gimcheck.R
import app.gimcheck.core.ChartRange
import app.gimcheck.core.VolumeBand
import app.gimcheck.ui.LocalAppContainer
import app.gimcheck.ui.chart.BarChart
import app.gimcheck.ui.chart.LineChart
import app.gimcheck.ui.components.ChipFilter
import app.gimcheck.ui.components.ConfirmInline
import app.gimcheck.ui.components.EmptyState
import app.gimcheck.ui.components.Meter
import app.gimcheck.ui.components.PrimaryButton
import app.gimcheck.ui.components.SectionHeader
import app.gimcheck.ui.components.SetStepper
import app.gimcheck.ui.components.StatTile
import app.gimcheck.ui.shortDateYear
import app.gimcheck.ui.theme.GimHeight
import app.gimcheck.ui.theme.GimRadius
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimStroke
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme
import kotlinx.datetime.LocalDate

/**
 * **Progreso** — §6 fila 6 y `docs/diseno.md` §8 #6 (GIM-14/26/27/28).
 *
 * Tres vistas con chips en la misma pantalla: **PR** (línea de e1RM por sesión con la
 * tarjeta del récord), **Volumen** (barras por semana o sesión + series por músculo) y
 * **Peso** (línea con promedio de 7 días y registro rápido).
 *
 * Los gráficos son el motor de `ui/chart` (Canvas propio, sin librerías). La pantalla
 * no calcula: [ProgresoViewModel] entrega listas ya recortadas al rango y etiquetas
 * formateadas. Cuando no hay datos, cada vista muestra el estado vacío del contrato con
 * la acción que corresponde, no un gráfico en blanco.
 */
@Composable
fun ProgresoScreen(
    onGoHoy: () -> Unit,
    onOpenCompare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    if (container == null) {
        EmptyState(
            text = stringResource(R.string.empty_progreso_text),
            actionLabel = stringResource(R.string.empty_progreso_action),
            onAction = onGoHoy,
            modifier = modifier,
        )
        return
    }

    val scope = rememberCoroutineScope()
    val viewModel = remember(container) { ProgresoViewModel(container, scope) }
    LaunchedEffect(viewModel) { viewModel.start() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stop() }
    }

    val state by viewModel.state.collectAsState()
    ProgresoContent(
        state = state,
        onTab = viewModel::selectTab,
        onRange = viewModel::selectRange,
        onExercise = viewModel::selectExercise,
        onVolumeMode = viewModel::selectVolumeMode,
        onToggleIndirect = viewModel::toggleIndirect,
        onBodyWeight = viewModel::setBodyWeight,
        onEditRecord = viewModel::editBodyRecord,
        onToday = viewModel::newBodyRecordToday,
        onSaveBody = viewModel::saveBody,
        onDeleteRecord = viewModel::deleteBodyRecord,
        onGoHoy = onGoHoy,
        onOpenCompare = onOpenCompare,
        modifier = modifier,
    )
}

@Composable
private fun ProgresoContent(
    state: ProgresoUiState,
    onTab: (ProgresoTab) -> Unit,
    onRange: (ChartRange) -> Unit,
    onExercise: (String) -> Unit,
    onVolumeMode: (VolumeMode) -> Unit,
    onToggleIndirect: () -> Unit,
    onBodyWeight: (Double) -> Unit,
    onEditRecord: (LocalDate, Double) -> Unit,
    onToday: () -> Unit,
    onSaveBody: () -> Unit,
    onDeleteRecord: (LocalDate) -> Unit,
    onGoHoy: () -> Unit,
    onOpenCompare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GimTheme.colors
    var confirmingDelete by remember { mutableStateOf<LocalDate?>(null) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = GimSpacing.l),
        contentPadding = PaddingValues(top = GimSpacing.l, bottom = GimSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(GimSpacing.s),
    ) {
        item {
            Text(text = stringResource(R.string.nav_progreso), style = GimText.titulo, color = colors.ink)
            Text(
                text = stringResource(R.string.ph_progreso),
                style = GimText.detalle,
                color = colors.muted,
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(GimSpacing.s),
            ) {
                ChipFilter(
                    label = stringResource(R.string.progreso_tab_pr),
                    selected = state.tab == ProgresoTab.PR,
                    onClick = { onTab(ProgresoTab.PR) },
                )
                ChipFilter(
                    label = stringResource(R.string.progreso_tab_volumen),
                    selected = state.tab == ProgresoTab.VOLUMEN,
                    onClick = { onTab(ProgresoTab.VOLUMEN) },
                )
                ChipFilter(
                    label = stringResource(R.string.progreso_tab_peso),
                    selected = state.tab == ProgresoTab.PESO,
                    onClick = { onTab(ProgresoTab.PESO) },
                )
            }
        }

        when (state.tab) {
            ProgresoTab.PR -> prSection(state, onRange, onExercise, onGoHoy, onOpenCompare)
            ProgresoTab.VOLUMEN -> volumeSection(state, onVolumeMode, onToggleIndirect, onGoHoy, onOpenCompare)
            ProgresoTab.PESO -> bodySection(
                state = state,
                onWeight = onBodyWeight,
                onEditRecord = onEditRecord,
                onToday = onToday,
                onSave = onSaveBody,
                onDelete = onDeleteRecord,
                confirmingDelete = confirmingDelete,
                onAskDelete = { confirmingDelete = it },
                onCancelDelete = { confirmingDelete = null },
            )
        }
    }
}

// --- PR ---------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.prSection(
    state: ProgresoUiState,
    onRange: (ChartRange) -> Unit,
    onExercise: (String) -> Unit,
    onGoHoy: () -> Unit,
    onOpenCompare: () -> Unit,
) {
    item {
        RangeChips(range = state.range, onRange = onRange)
    }
    if (state.exercises.isEmpty()) {
        item {
            EmptyState(
                text = stringResource(R.string.empty_progreso_text),
                actionLabel = stringResource(R.string.empty_progreso_action),
                onAction = onGoHoy,
            )
        }
        return
    }

    item {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(GimSpacing.s),
        ) {
            state.exercises.forEach { exercise ->
                ChipFilter(
                    label = exercise.name,
                    selected = exercise.id == state.selectedExerciseId,
                    onClick = { onExercise(exercise.id) },
                )
            }
        }
    }

    item {
        ChartCard {
            LineChart(
                labels = state.prLabels,
                values = state.prValues,
                unit = " kg",
                markIndices = state.prMarkIndices,
            )
        }
    }

    state.pr?.let { pr ->
        item {
            StatTile(
                k = stringResource(R.string.progreso_pr_titulo),
                v = pr.valueLabel,
                s = "${pr.dateLabel} · ${pr.setLabel}",
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = pr.deltaLabel ?: stringResource(R.string.progreso_pr_primero),
                style = GimText.detalle,
                color = GimTheme.colors.good,
                modifier = Modifier.padding(top = GimSpacing.xs),
            )
            Text(
                text = pr.formulaLabel,
                style = GimText.etiqueta,
                color = GimTheme.colors.muted,
            )
        }
    }

    item {
        SectionHeader(
            title = stringResource(R.string.progreso_planes),
            action = stringResource(R.string.nav_comparar),
            onAction = onOpenCompare,
        )
    }
}

// --- Volumen ----------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.volumeSection(
    state: ProgresoUiState,
    onVolumeMode: (VolumeMode) -> Unit,
    onToggleIndirect: () -> Unit,
    onGoHoy: () -> Unit,
    onOpenCompare: () -> Unit,
) {
    item {
        SectionHeader(
            title = stringResource(R.string.progreso_volumen),
            action = stringResource(R.string.nav_comparar),
            onAction = onOpenCompare,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
            ChipFilter(
                label = stringResource(R.string.progreso_por_semana),
                selected = state.volumeMode == VolumeMode.SEMANA,
                onClick = { onVolumeMode(VolumeMode.SEMANA) },
            )
            ChipFilter(
                label = stringResource(R.string.progreso_por_sesion),
                selected = state.volumeMode == VolumeMode.SESION,
                onClick = { onVolumeMode(VolumeMode.SESION) },
            )
        }
    }

    if (!state.volumeHasData) {
        item {
            EmptyState(
                text = stringResource(R.string.empty_progreso_text),
                actionLabel = stringResource(R.string.empty_progreso_action),
                onAction = onGoHoy,
            )
        }
        return
    }

    item {
        ChartCard {
            BarChart(
                labels = state.volumeLabels,
                values = state.volumeValues,
                unit = " kg",
            )
        }
    }

    item {
        Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
            StatTile(
                k = stringResource(R.string.progreso_semana_actual),
                v = state.currentWeekLabel,
                s = state.currentWeekDelta,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                k = stringResource(R.string.progreso_rango_visible),
                v = state.volumeValues.size.toString(),
                s = stringResource(R.string.progreso_barras),
                modifier = Modifier.weight(1f),
            )
        }
    }

    item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(
                title = stringResource(R.string.progreso_series_musculo),
                modifier = Modifier.weight(1f),
            )
            ChipFilter(
                label = stringResource(R.string.progreso_indirectas),
                selected = state.includeIndirect,
                onClick = onToggleIndirect,
            )
        }
    }

    if (state.muscles.isEmpty()) {
        item {
            Text(
                text = stringResource(R.string.progreso_musculos_vacio),
                style = GimText.detalle,
                color = GimTheme.colors.muted,
            )
        }
    } else {
        items(state.muscles, key = { it.id }) { muscle ->
            val colors = GimTheme.colors
            val color = when (muscle.band) {
                VolumeBand.VERY_LOW, VolumeBand.LOW -> colors.warn
                VolumeBand.IN_RANGE -> colors.good
                VolumeBand.HIGH -> colors.accent
            }
            Column(verticalArrangement = Arrangement.spacedBy(GimSpacing.xs)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = muscle.name,
                        style = GimText.cuerpo,
                        color = colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = muscle.totalLabel,
                        style = GimText.subtitulo,
                        color = colors.ink,
                        maxLines = 1,
                    )
                }
                Meter(
                    value = muscle.value,
                    max = muscle.max,
                    bandMin = muscle.bandMin,
                    bandMax = muscle.bandMax,
                    color = color,
                )
                Text(
                    text = muscle.band.label + " · " + muscle.detailLabel,
                    style = GimText.etiqueta,
                    color = color,
                )
            }
        }
    }
}

// --- Peso corporal ----------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.bodySection(
    state: ProgresoUiState,
    onWeight: (Double) -> Unit,
    onEditRecord: (LocalDate, Double) -> Unit,
    onToday: () -> Unit,
    onSave: () -> Unit,
    onDelete: (LocalDate) -> Unit,
    confirmingDelete: LocalDate?,
    onAskDelete: (LocalDate) -> Unit,
    onCancelDelete: () -> Unit,
) {
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
            StatTile(
                k = stringResource(R.string.progreso_peso_ultimo),
                v = state.bodyLatestLabel,
                s = state.bodyDeltaLabel,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                k = stringResource(R.string.progreso_peso_registros),
                v = state.bodyRecords.size.toString(),
                s = stringResource(R.string.progreso_peso_dias),
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (state.bodyValues.isNotEmpty()) {
        item {
            ChartCard {
                LineChart(
                    labels = state.bodyLabels,
                    values = state.bodyValues,
                    secondary = state.bodyAverage,
                    unit = " kg",
                    secondaryLabel = stringResource(R.string.progreso_peso_promedio),
                )
            }
        }
    } else {
        item {
            Text(
                text = stringResource(R.string.progreso_peso_sin_datos),
                style = GimText.detalle,
                color = GimTheme.colors.muted,
            )
        }
    }

    item {
        SetStepper(
            value = state.bodyDraft,
            step = 0.1,
            onValue = onWeight,
            label = stringResource(R.string.ph_peso_corporal),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = if (state.bodyEditing) {
                stringResource(R.string.progreso_peso_editando, shortDateYear(state.bodyDate))
            } else {
                stringResource(R.string.progreso_peso_hoy)
            },
            style = GimText.detalle,
            color = GimTheme.colors.muted,
        )
        PrimaryButton(
            label = stringResource(R.string.progreso_peso_guardar),
            onClick = onSave,
        )
        if (state.bodyEditing) {
            Text(
                text = stringResource(R.string.progreso_peso_anotar_hoy),
                style = GimText.boton,
                color = colors.accent,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = GimHeight.row)
                    .clickable(role = Role.Button, onClick = onToday)
                    .padding(top = GimSpacing.xs),
            )
        }
    }

    if (state.bodyRecords.isNotEmpty()) {
        item {
            SectionHeader(title = stringResource(R.string.progreso_peso_registros_titulo))
        }
        items(state.bodyRecords, key = { it.date.toString() }) { record ->
            val colors = GimTheme.colors
            Column(verticalArrangement = Arrangement.spacedBy(GimSpacing.xs)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = GimHeight.row)
                        .clickable(role = Role.Button) { onEditRecord(record.date, record.weight) }
                        .padding(vertical = GimSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = record.dateLabel,
                        style = GimText.detalle,
                        color = colors.muted,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = record.weightLabel,
                        style = GimText.subtitulo,
                        color = colors.ink,
                        maxLines = 1,
                    )
                }
                if (confirmingDelete == record.date) {
                    ConfirmInline(
                        question = stringResource(R.string.progreso_peso_borrar_pregunta, record.dateLabel),
                        onConfirm = { onDelete(record.date) },
                        onCancel = onCancelDelete,
                        confirmLabel = stringResource(R.string.accion_borrar),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.accion_borrar),
                        style = GimText.etiqueta,
                        color = colors.bad,
                        modifier = Modifier
                            .defaultMinSize(minHeight = GimHeight.row)
                            .clickable(role = Role.Button) { onAskDelete(record.date) },
                    )
                }
            }
        }
    }
}

// --- piezas comunes ---------------------------------------------------------------

@Composable
private fun RangeChips(
    range: ChartRange,
    onRange: (ChartRange) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(GimSpacing.s),
    ) {
        ChipFilter(
            label = stringResource(R.string.ph_rango_3m),
            selected = range == ChartRange.THREE_MONTHS,
            onClick = { onRange(ChartRange.THREE_MONTHS) },
        )
        ChipFilter(
            label = stringResource(R.string.ph_rango_6m),
            selected = range == ChartRange.SIX_MONTHS,
            onClick = { onRange(ChartRange.SIX_MONTHS) },
        )
        ChipFilter(
            label = stringResource(R.string.ph_rango_1a),
            selected = range == ChartRange.ONE_YEAR,
            onClick = { onRange(ChartRange.ONE_YEAR) },
        )
        ChipFilter(
            label = stringResource(R.string.progreso_rango_todo),
            selected = range == ChartRange.ALL,
            onClick = { onRange(ChartRange.ALL) },
        )
    }
}

/**
 * Tarjeta plana de un gráfico: una sola superficie (`surface`, borde `line`, radio 10)
 * y padding 12. No hay sombra: es el mismo tratamiento que el resto de las tarjetas.
 */
@Composable
private fun ChartCard(content: @Composable () -> Unit) {
    val colors = GimTheme.colors
    val shape = RoundedCornerShape(GimRadius.control)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(GimStroke.border, colors.line, shape)
            .padding(GimSpacing.m),
    ) {
        content()
    }
}
