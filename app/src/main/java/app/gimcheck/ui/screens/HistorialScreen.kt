package app.gimcheck.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import app.gimcheck.R
import app.gimcheck.ui.LocalAppContainer
import app.gimcheck.ui.components.ConfirmInline
import app.gimcheck.ui.components.EmptyState
import app.gimcheck.ui.components.SectionHeader
import app.gimcheck.ui.components.SetRow
import app.gimcheck.ui.components.SetStepper
import app.gimcheck.ui.components.StatTile
import app.gimcheck.ui.theme.GimHeight
import app.gimcheck.ui.theme.GimRadius
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimStroke
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * **Historial** — §6 fila 5 y `docs/diseno.md` §8 #7 (GIM-25).
 *
 * Dos vistas en el mismo destino, como el editor de Rutinas: la lista de sesiones
 * agrupada por semana ISO y el detalle de una sesión. Tocar una serie la corrige en el
 * lugar (peso/reps) o la borra con [ConfirmInline]; borrar el entrenamiento completo
 * pide confirmación en el mismo bloque. Nunca hay un `AlertDialog` para borrar (§5).
 *
 * Todo lo que se ve viene resuelto de [HistorialViewModel]: la pantalla no calcula
 * volumen, PR ni duración. Los números están formateados con `:core` (`formatNumber`,
 * `formatDuration`) y las fechas con [app.gimcheck.ui.UiFormat], no acá.
 */
@Composable
fun HistorialScreen(
    onStartSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    if (container == null) {
        EmptyState(
            text = stringResource(R.string.empty_historial_text),
            actionLabel = stringResource(R.string.empty_historial_action),
            onAction = onStartSession,
            modifier = modifier,
        )
        return
    }

    val scope = rememberCoroutineScope()
    val viewModel = remember(container) { HistorialViewModel(container, scope) }
    LaunchedEffect(viewModel) { viewModel.start() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stop() }
    }

    val state by viewModel.state.collectAsState()
    val detail = state.detail
    if (detail == null) {
        HistoryList(
            state = state,
            onOpen = viewModel::openSession,
            onStartSession = onStartSession,
            modifier = modifier,
        )
    } else {
        HistoryDetail(
            detail = detail,
            onBack = viewModel::closeDetail,
            onEditSet = viewModel::startEditSet,
            onCancelEdit = viewModel::cancelEditSet,
            onWeight = viewModel::setEditWeight,
            onReps = viewModel::setEditReps,
            onSaveSet = viewModel::saveSetEdit,
            onAskDeleteSet = viewModel::askDeleteSet,
            onCancelDeleteSet = viewModel::cancelDeleteSet,
            onDeleteSet = viewModel::deleteSet,
            onAskDeleteSession = viewModel::askDeleteSession,
            onCancelDeleteSession = viewModel::cancelDeleteSession,
            onDeleteSession = viewModel::deleteSession,
            modifier = modifier,
        )
    }
}

@Composable
private fun HistoryList(
    state: HistorialUiState,
    onOpen: (String) -> Unit,
    onStartSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GimTheme.colors
    val total = state.weeks.sumOf { it.sessions.size }
    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = GimSpacing.l),
            contentPadding = PaddingValues(top = GimSpacing.l, bottom = GimSpacing.l),
            verticalArrangement = Arrangement.spacedBy(GimSpacing.s),
        ) {
            item {
                Text(text = stringResource(R.string.nav_historial), style = GimText.titulo, color = colors.ink)
                Text(
                    text = if (total == 1) {
                        stringResource(R.string.hist_total_uno)
                    } else {
                        stringResource(R.string.hist_total, total)
                    },
                    style = GimText.detalle,
                    color = colors.muted,
                )
            }

            if (state.weeks.isEmpty() && !state.loading) {
                item {
                    EmptyState(
                        text = stringResource(R.string.empty_historial_text),
                        actionLabel = stringResource(R.string.empty_historial_action),
                        onAction = onStartSession,
                    )
                }
            }

            state.weeks.forEach { week ->
                item(key = "week-${week.key}") {
                    Spacer(modifier = Modifier.padding(top = GimSpacing.s))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = week.label.uppercase(),
                            style = GimText.section,
                            color = colors.ink,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = week.volumeLabel,
                            style = GimText.etiqueta,
                            color = colors.muted,
                        )
                    }
                }
                items(week.sessions, key = { it.id }) { session ->
                    HistoryRow(session = session, onClick = { onOpen(session.id) })
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(session: HistorialSessionUi, onClick: () -> Unit) {
    val colors = GimTheme.colors
    val shape = RoundedCornerShape(GimRadius.control)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(GimStroke.border, colors.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = GimSpacing.m, vertical = GimSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GimSpacing.m),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = session.dateLabel, style = GimText.etiqueta, color = colors.muted, maxLines = 1)
            Text(text = session.title, style = GimText.cuerpo, color = colors.ink, maxLines = 1)
            Text(text = session.subtitle, style = GimText.detalle, color = colors.muted, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(text = session.volumeLabel, style = GimText.subtitulo, color = colors.ink, maxLines = 1)
            if (session.prCount > 0) {
                Text(
                    text = if (session.prCount == 1) {
                        stringResource(R.string.hist_pr_uno)
                    } else {
                        stringResource(R.string.hist_prs, session.prCount)
                    },
                    style = GimText.etiqueta,
                    color = colors.good,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun HistoryDetail(
    detail: HistorialDetailUi,
    onBack: () -> Unit,
    onEditSet: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onWeight: (Double) -> Unit,
    onReps: (Double) -> Unit,
    onSaveSet: () -> Unit,
    onAskDeleteSet: () -> Unit,
    onCancelDeleteSet: () -> Unit,
    onDeleteSet: () -> Unit,
    onAskDeleteSession: () -> Unit,
    onCancelDeleteSession: () -> Unit,
    onDeleteSession: () -> Unit,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.hist_volver),
                    style = GimText.boton,
                    color = colors.accent,
                    modifier = Modifier
                        .defaultMinSize(minHeight = GimHeight.row)
                        .clickable(role = Role.Button, onClick = onBack)
                        .padding(end = GimSpacing.l),
                )
                Text(
                    text = stringResource(R.string.nav_historial),
                    style = GimText.etiqueta,
                    color = colors.muted,
                )
            }
            Text(text = detail.dateLabel, style = GimText.etiqueta, color = colors.muted)
            Text(text = detail.title, style = GimText.titulo, color = colors.ink)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
                StatTile(
                    k = stringResource(R.string.entrenar_fin_duracion),
                    v = detail.durationLabel,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    k = stringResource(R.string.entrenar_fin_series),
                    v = detail.setsLabel,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    k = stringResource(R.string.entrenar_fin_volumen),
                    v = detail.volumeLabel,
                    modifier = Modifier.weight(1f),
                )
            }
            if (detail.prCount > 0) {
                Text(
                    text = if (detail.prCount == 1) {
                        stringResource(R.string.hist_pr_uno)
                    } else {
                        stringResource(R.string.hist_prs, detail.prCount)
                    },
                    style = GimText.detalle,
                    color = colors.good,
                    modifier = Modifier.padding(top = GimSpacing.s),
                )
            }
        }

        if (detail.exercises.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.hist_sin_series),
                    style = GimText.detalle,
                    color = colors.muted,
                )
            }
        }

        detail.exercises.forEach { exercise ->
            item(key = "ex-${exercise.exerciseId}") {
                SectionHeader(title = exercise.name)
                Text(
                    text = exercise.setsLabel,
                    style = GimText.etiqueta,
                    color = colors.muted,
                )
            }
            items(exercise.sets, key = { it.id }) { set ->
                Column(verticalArrangement = Arrangement.spacedBy(GimSpacing.xs)) {
                    SetRow(
                        index = set.number,
                        weightLabel = set.weightLabel,
                        repsLabel = set.repsLabel,
                        isWarmup = set.isWarmup,
                        onEdit = { onEditSet(set.id) },
                    )
                    if (detail.editingSetId == set.id) {
                        SetEditor(
                            weight = detail.editWeight,
                            reps = detail.editReps,
                            confirmingDelete = detail.confirmingDeleteSet,
                            onWeight = onWeight,
                            onReps = onReps,
                            onSave = onSaveSet,
                            onCancel = onCancelEdit,
                            onAskDelete = onAskDeleteSet,
                            onCancelDelete = onCancelDeleteSet,
                            onDelete = onDeleteSet,
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.padding(top = GimSpacing.l))
            if (detail.confirmingDeleteSession) {
                ConfirmInline(
                    question = stringResource(R.string.hist_borrar_sesion_pregunta),
                    onConfirm = onDeleteSession,
                    onCancel = onCancelDeleteSession,
                    confirmLabel = stringResource(R.string.accion_borrar),
                )
            } else {
                Text(
                    text = stringResource(R.string.hist_borrar_sesion),
                    style = GimText.boton,
                    color = colors.bad,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = GimHeight.row)
                        .clickable(role = Role.Button, onClick = onAskDeleteSession),
                )
            }
        }
    }
}

/**
 * Corrección de una serie: peso y reps con [SetStepper] (se puede escribir el número) y
 * guardar/borrar. El borrado reemplaza la acción por [ConfirmInline] en el mismo lugar.
 */
@Composable
private fun SetEditor(
    weight: Double,
    reps: Double,
    confirmingDelete: Boolean,
    onWeight: (Double) -> Unit,
    onReps: (Double) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onAskDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = GimTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = GimSpacing.m, end = GimSpacing.m, bottom = GimSpacing.s),
        verticalArrangement = Arrangement.spacedBy(GimSpacing.s),
    ) {
        SetStepper(
            value = weight,
            step = 2.5,
            onValue = onWeight,
            label = stringResource(R.string.ph_peso_serie),
            modifier = Modifier.fillMaxWidth(),
        )
        SetStepper(
            value = reps,
            step = 1.0,
            onValue = onReps,
            label = stringResource(R.string.ph_reps),
            wholeNumbers = true,
            max = 100.0,
            modifier = Modifier.fillMaxWidth(),
        )
        if (confirmingDelete) {
            ConfirmInline(
                question = stringResource(R.string.hist_borrar_serie_pregunta),
                onConfirm = onDelete,
                onCancel = onCancelDelete,
                confirmLabel = stringResource(R.string.accion_borrar),
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.l)) {
                ActionText(stringResource(R.string.accion_guardar), colors.accent, onSave)
                ActionText(stringResource(R.string.accion_cancelar), colors.muted, onCancel)
                ActionText(stringResource(R.string.accion_borrar), colors.bad, onAskDelete)
            }
        }
    }
}

/** Acción de texto de 48 dp de alto (mismo patrón que el resto de las pantallas). */
@Composable
private fun ActionText(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        text = label,
        style = GimText.boton,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .defaultMinSize(minHeight = GimHeight.row)
            .clickable(role = Role.Button, onClick = onClick),
    )
}
