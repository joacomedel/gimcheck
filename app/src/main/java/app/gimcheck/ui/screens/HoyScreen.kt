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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.gimcheck.R
import app.gimcheck.ui.LocalAppContainer
import app.gimcheck.ui.components.ChipFilter
import app.gimcheck.ui.components.ConfirmInline
import app.gimcheck.ui.components.EmptyState
import app.gimcheck.ui.components.PrimaryButton
import app.gimcheck.ui.components.SectionHeader
import app.gimcheck.ui.components.SetStepper
import app.gimcheck.ui.components.StatTile
import app.gimcheck.ui.theme.GimHeight
import app.gimcheck.ui.theme.GimRadius
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimStroke
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * **Hoy** — §6 fila 1 y `docs/diseno.md` §8 #1.
 *
 * Jerarquía (de arriba abajo): saludo con la fecha en español · rutina y `ChipFilter`
 * de días · `StatTile` de duración estimada, series y nombre del día · peso corporal de
 * hoy (`SetStepper` de 0,1 kg + "Anotar"/"Corregir") · aviso de sesión abierta (si la
 * hay) · `SectionHeader` "Últimos" con las 3 últimas sesiones · acción primaria de 52 dp
 * ("Empezar sesión" / "Continuar sesión (N min)").
 *
 * **Cero lógica de dominio acá**: el estado viene de [HoyViewModel] y los números ya
 * formateados (`dayStats`, `sessionVolume`). La pantalla sólo elige qué composable
 * dibuja cada campo. Los colores salen de [GimTheme] (`ui/theme`), nunca literales.
 */
@Composable
fun HoyScreen(
    onStartSession: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenRutinas: () -> Unit = {},
) {
    val container = LocalAppContainer.current
    if (container == null) {
        // Preview o test de composición sin app: el contrato vacío de §9.
        EmptyState(
            text = stringResource(R.string.empty_hoy_text),
            actionLabel = stringResource(R.string.empty_hoy_action),
            onAction = onOpenRutinas,
            modifier = modifier,
        )
        return
    }

    val scope = rememberCoroutineScope()
    val viewModel = remember(container) { HoyViewModel(container, scope) }
    LaunchedEffect(viewModel) { viewModel.start() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stop() }
    }

    val state by viewModel.state.collectAsState()
    HoyContent(
        state = state,
        onSelectDay = viewModel::selectDay,
        onBodyWeight = viewModel::setBodyWeight,
        onSaveBodyWeight = viewModel::saveBodyWeight,
        onStartSession = {
            if (state.active != null) {
                onStartSession()
            } else {
                viewModel.startSession { started -> if (started) onStartSession() }
            }
        },
        onDiscardSession = viewModel::discardActiveSession,
        onOpenRutinas = onOpenRutinas,
        modifier = modifier,
    )
}

@Composable
private fun HoyContent(
    state: HoyUiState,
    onSelectDay: (String) -> Unit,
    onBodyWeight: (Double) -> Unit,
    onSaveBodyWeight: () -> Unit,
    onStartSession: () -> Unit,
    onDiscardSession: () -> Unit,
    onOpenRutinas: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingDiscard by remember { mutableStateOf(false) }
    val colors = GimTheme.colors

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
                Text(text = state.greeting, style = GimText.titulo, color = colors.ink)
                Text(text = state.dateLabel, style = GimText.detalle, color = colors.muted)
            }

            if (!state.hasRoutine) {
                item {
                    EmptyState(
                        text = stringResource(R.string.empty_hoy_text),
                        actionLabel = stringResource(R.string.empty_hoy_action),
                        onAction = onOpenRutinas,
                    )
                }
            } else {
                item {
                    Text(
                        text = state.routineName,
                        style = GimText.etiqueta,
                        color = colors.muted,
                        maxLines = 1,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(GimSpacing.s),
                    ) {
                        state.days.forEach { day ->
                            ChipFilter(
                                label = day.name,
                                selected = day.selected,
                                onClick = { onSelectDay(day.id) },
                            )
                        }
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
                        StatTile(
                            k = stringResource(R.string.ph_duracion),
                            v = state.durationLabel,
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            k = stringResource(R.string.ph_series),
                            v = state.setsLabel,
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            k = stringResource(R.string.hoy_dia),
                            v = state.selectedDayName,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                item {
                    SetStepper(
                        value = state.bodyWeightDraft,
                        step = 0.1,
                        onValue = onBodyWeight,
                        label = stringResource(R.string.ph_peso_corporal),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val saved = state.bodyWeightLabel
                        Text(
                            text = if (saved != null) {
                                stringResource(R.string.hoy_peso_anotado, saved)
                            } else {
                                stringResource(R.string.hoy_peso_sin_anotar)
                            },
                            style = GimText.detalle,
                            color = colors.muted,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (state.bodyWeightSaved) {
                                stringResource(R.string.accion_corregir)
                            } else {
                                stringResource(R.string.accion_anotar)
                            },
                            style = GimText.boton,
                            color = colors.accent,
                            maxLines = 1,
                            modifier = Modifier
                                .defaultMinSize(minHeight = GimHeight.row)
                                .clickable(role = Role.Button, onClick = onSaveBodyWeight)
                                .padding(start = GimSpacing.m),
                        )
                    }
                }

                val active = state.active
                if (active != null) {
                    item {
                        ActiveSessionRow(
                            active = active,
                            otherDay = state.activeOtherDay,
                            confirmingDiscard = confirmingDiscard,
                            onAskDiscard = { confirmingDiscard = true },
                            onCancelDiscard = { confirmingDiscard = false },
                            onConfirmDiscard = {
                                confirmingDiscard = false
                                onDiscardSession()
                            },
                        )
                    }
                }

                item {
                    SectionHeader(title = stringResource(R.string.hoy_ultimos))
                }

                if (state.recent.isEmpty()) {
                    item {
                        EmptyState(
                            text = stringResource(R.string.empty_historial_text),
                            actionLabel = stringResource(R.string.empty_historial_action),
                            onAction = onStartSession,
                        )
                    }
                } else {
                    items(state.recent) { recent ->
                        RecentSessionRow(recent = recent)
                    }
                }
            }
        }

        if (state.hasRoutine) {
            Column(modifier = Modifier.padding(horizontal = GimSpacing.l)) {
                Spacer(modifier = Modifier.height(GimSpacing.s))
                val active = state.active
                PrimaryButton(
                    label = if (active != null) {
                        stringResource(R.string.accion_continuar_sesion_min, active.minutesFromStart)
                    } else {
                        stringResource(R.string.accion_empezar_sesion)
                    },
                    onClick = onStartSession,
                )
                Spacer(modifier = Modifier.height(GimSpacing.l))
            }
        }
    }
}

/**
 * Fila de la sesión abierta: qué día es y, si es de otro día, el aviso en `warn`.
 * "Descartar" reemplaza al texto por [ConfirmInline] en el mismo lugar (§5).
 */
@Composable
private fun ActiveSessionRow(
    active: HoyActiveUi,
    otherDay: Boolean,
    confirmingDiscard: Boolean,
    onAskDiscard: () -> Unit,
    onCancelDiscard: () -> Unit,
    onConfirmDiscard: () -> Unit,
) {
    val colors = GimTheme.colors
    val shape = RoundedCornerShape(GimRadius.control)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(GimStroke.border, colors.line, shape)
            .padding(GimSpacing.m),
        verticalArrangement = Arrangement.spacedBy(GimSpacing.xs),
    ) {
        Text(
            text = stringResource(R.string.hoy_sesion_abierta) + " · " + active.dayName,
            style = GimText.subtitulo,
            color = colors.ink,
            maxLines = 1,
        )
        if (otherDay) {
            Text(
                text = stringResource(R.string.hoy_sesion_otro_dia, active.dayName),
                style = GimText.detalle,
                color = colors.warn,
            )
        }
        if (confirmingDiscard) {
            ConfirmInline(
                question = stringResource(R.string.hoy_descartar_pregunta),
                onConfirm = onConfirmDiscard,
                onCancel = onCancelDiscard,
                confirmLabel = stringResource(R.string.accion_descartar),
            )
        } else {
            Text(
                text = stringResource(R.string.accion_descartar),
                style = GimText.boton,
                color = colors.bad,
                maxLines = 1,
                modifier = Modifier
                    .defaultMinSize(minHeight = GimHeight.row)
                    .clickable(role = Role.Button, onClick = onAskDiscard),
            )
        }
    }
}

/** Una sesión de "Últimos": fecha, volumen y series (fila tocable, 48 dp). */
@Composable
private fun RecentSessionRow(recent: HoyRecentUi) {
    val colors = GimTheme.colors
    val shape = RoundedCornerShape(GimRadius.control)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(GimStroke.border, colors.line, shape)
            .padding(horizontal = GimSpacing.m, vertical = GimSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GimSpacing.m),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = recent.dateLabel, style = GimText.cuerpo, color = colors.ink, maxLines = 1)
            Text(
                text = pluralStringResource(R.plurals.hoy_series, recent.setCount, recent.setCount),
                style = GimText.detalle,
                color = colors.muted,
                maxLines = 1,
            )
        }
        Text(
            text = recent.volumeLabel,
            style = GimText.subtitulo,
            color = colors.ink,
            maxLines = 1,
        )
    }
}
