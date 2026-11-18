package app.gimcheck.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.gimcheck.R
import app.gimcheck.ui.LocalAppContainer
import app.gimcheck.ui.components.ChipFilter
import app.gimcheck.ui.components.ConfirmInline
import app.gimcheck.ui.components.EmptyState
import app.gimcheck.ui.components.Meter
import app.gimcheck.ui.components.PrimaryButton
import app.gimcheck.ui.components.SectionHeader
import app.gimcheck.ui.components.SetRow
import app.gimcheck.ui.components.SetStepper
import app.gimcheck.ui.components.TimerRing
import app.gimcheck.ui.theme.GimHeight
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme
import kotlinx.coroutines.delay

/**
 * **Entrenar** — §6 fila 2 y `docs/diseno.md` §8 #2. Es el corazón de la app: anotar una
 * serie y que la app lleve sola al siguiente ítem de la superserie, con el descanso
 * arrancando recién al cerrar la ronda.
 *
 * Jerarquía (de arriba abajo, la del contrato): bloque actual + progreso (`Meter` de
 * series hechas) · indicador "Superserie 1/2" · ejercicio en `titulo` + "Ronda 2 de 3" ·
 * cuenta regresiva (`TimerRing`) con "−15 s", "Saltar" y "+15 s" · dos `SetStepper`
 * grandes (peso con paso `weightStep`, reps de 1) con chip de calentamiento · series
 * anotadas del bloque (`SetRow`, tocables para corregir) · acción primaria "Serie hecha"
 * de 52 dp al pie.
 *
 * **Cero lógica de negocio acá**: el estado viene de [EntrenarViewModel] y el cursor lo
 * mueve la máquina de `:core`. Esta pantalla no sabe cuándo hay descanso; sólo dibuja
 * `state.rest`, que sale de `rest_ends_at`.
 *
 * **Fin de sesión**: al cerrar el último bloque la máquina emite `SessionFinished` y la
 * pantalla muestra el placeholder de fin con "Volver a Hoy". El resumen real (PRs del
 * día, volumen, guardado en historial) es GIM-24; no se agrega una décima ruta porque
 * §8 tiene nueve y el grafo está fijado por test.
 *
 * **Salir** pide confirmación (§7.7): la sesión sigue viva, sólo se cierra la pantalla.
 */
@Composable
fun EntrenarScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    if (container == null) {
        // Preview o test de composición sin app: no hay sesión que guiar (§9: no aplica).
        PlaceholderScreen(
            title = stringResource(R.string.nav_entrenar),
            hint = stringResource(R.string.ph_entrenar),
            modifier = modifier,
        )
        return
    }

    val scope = rememberCoroutineScope()
    val viewModel = remember(container) { EntrenarViewModel(container, scope) }
    LaunchedEffect(viewModel) { viewModel.start() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stop() }
    }

    val state by viewModel.state.collectAsState()

    KeepScreenOn(enabled = state.keepScreenOn && state.phase == EntrenarPhase.ACTIVE)

    // Pulso del cronómetro: la máquina decide si el descanso venció; acá sólo se pide.
    val resting = state.rest != null
    LaunchedEffect(resting) {
        if (!resting) return@LaunchedEffect
        while (true) {
            delay(REST_TICK_MS)
            viewModel.tick()
        }
    }

    EntrenarContent(
        state = state,
        onWeight = viewModel::setWeight,
        onReps = viewModel::setReps,
        onToggleWarmup = viewModel::toggleWarmup,
        onLogSet = viewModel::logSet,
        onSkipRest = viewModel::skipRest,
        onExtendRest = viewModel::extendRest,
        onEditSet = viewModel::editSet,
        onCancelEdit = viewModel::cancelEdit,
        onEditingWeight = viewModel::setEditingWeight,
        onEditingReps = viewModel::setEditingReps,
        onSaveCorrection = viewModel::saveCorrection,
        onAskDelete = viewModel::askDelete,
        onCancelDelete = viewModel::cancelDelete,
        onDelete = viewModel::deleteSet,
        onExit = onExit,
        modifier = modifier,
    )
}

@Composable
private fun EntrenarContent(
    state: EntrenarUiState,
    onWeight: (Double) -> Unit,
    onReps: (Double) -> Unit,
    onToggleWarmup: () -> Unit,
    onLogSet: () -> Unit,
    onSkipRest: () -> Unit,
    onExtendRest: (Int) -> Unit,
    onEditSet: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onEditingWeight: (Double) -> Unit,
    onEditingReps: (Double) -> Unit,
    onSaveCorrection: () -> Unit,
    onAskDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onDelete: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state.phase) {
        EntrenarPhase.NO_SESSION -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                text = stringResource(R.string.entrenar_sin_sesion),
                actionLabel = stringResource(R.string.accion_volver_hoy),
                onAction = onExit,
            )
        }

        EntrenarPhase.FINISHED -> EntrenarFinished(
            sets = state.finishedSets,
            onExit = onExit,
            modifier = modifier,
        )

        EntrenarPhase.ACTIVE -> EntrenarActive(
            state = state,
            onWeight = onWeight,
            onReps = onReps,
            onToggleWarmup = onToggleWarmup,
            onLogSet = onLogSet,
            onSkipRest = onSkipRest,
            onExtendRest = onExtendRest,
            onEditSet = onEditSet,
            onCancelEdit = onCancelEdit,
            onEditingWeight = onEditingWeight,
            onEditingReps = onEditingReps,
            onSaveCorrection = onSaveCorrection,
            onAskDelete = onAskDelete,
            onCancelDelete = onCancelDelete,
            onDelete = onDelete,
            onExit = onExit,
            modifier = modifier,
        )
    }
}

@Composable
private fun EntrenarActive(
    state: EntrenarUiState,
    onWeight: (Double) -> Unit,
    onReps: (Double) -> Unit,
    onToggleWarmup: () -> Unit,
    onLogSet: () -> Unit,
    onSkipRest: () -> Unit,
    onExtendRest: (Int) -> Unit,
    onEditSet: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onEditingWeight: (Double) -> Unit,
    onEditingReps: (Double) -> Unit,
    onSaveCorrection: () -> Unit,
    onAskDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onDelete: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GimTheme.colors
    var confirmingExit by remember { mutableStateOf(false) }

    // Salir no termina la sesión: pide confirmación y vuelve a Hoy (§7.7).
    BackHandler {
        if (confirmingExit) confirmingExit = false else confirmingExit = true
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = GimSpacing.l),
            contentPadding = PaddingValues(top = GimSpacing.l, bottom = GimSpacing.l),
            verticalArrangement = Arrangement.spacedBy(GimSpacing.s),
        ) {
            item { EntrenarHeader(state) }
            item { EntrenarProgress(state) }
            item { EntrenarExercise(state) }

            val rest = state.rest
            if (rest != null) {
                item {
                    EntrenarRest(
                        rest = rest,
                        onSkip = onSkipRest,
                        onExtend = onExtendRest,
                    )
                }
            }

            item {
                EntrenarInputs(
                    state = state,
                    onWeight = onWeight,
                    onReps = onReps,
                    onToggleWarmup = onToggleWarmup,
                )
            }

            item { SectionHeader(title = state.blockName) }

            state.sets.forEach { group ->
                item(key = group.itemId) {
                    Column(verticalArrangement = Arrangement.spacedBy(GimSpacing.xs)) {
                        Text(
                            text = group.exerciseName,
                            style = GimText.detalle,
                            color = colors.muted,
                            maxLines = 1,
                        )
                        group.sets.forEach { row ->
                            SetRow(
                                index = row.number,
                                weightLabel = row.weightLabel,
                                repsLabel = row.repsLabel,
                                onEdit = { onEditSet(row.id) },
                                isWarmup = row.isWarmup,
                                selected = state.editingSetId == row.id,
                            )
                            if (state.editingSetId == row.id) {
                                SetEditor(
                                    state = state,
                                    onWeight = onEditingWeight,
                                    onReps = onEditingReps,
                                    onSave = onSaveCorrection,
                                    onCancel = onCancelEdit,
                                    onAskDelete = onAskDelete,
                                    onCancelDelete = onCancelDelete,
                                    onDelete = onDelete,
                                )
                            }
                        }
                    }
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = GimSpacing.l)) {
            Spacer(modifier = Modifier.height(GimSpacing.s))
            if (confirmingExit) {
                ConfirmInline(
                    question = stringResource(R.string.ph_confirmar_salida),
                    onConfirm = onExit,
                    onCancel = { confirmingExit = false },
                    confirmLabel = stringResource(R.string.accion_salir),
                )
            } else {
                Text(
                    text = stringResource(R.string.accion_salir),
                    style = GimText.boton,
                    color = colors.muted,
                    maxLines = 1,
                    modifier = Modifier
                        .defaultMinSize(minHeight = GimHeight.row)
                        .clickable(role = Role.Button) { confirmingExit = true },
                )
            }
            PrimaryButton(
                label = stringResource(R.string.accion_serie_hecha),
                onClick = onLogSet,
                enabled = state.canLogSet,
            )
            Spacer(modifier = Modifier.height(GimSpacing.l))
        }
    }
}

/** Día y bloque actual: "Bloque 2 de 4". */
@Composable
private fun EntrenarHeader(state: EntrenarUiState) {
    val colors = GimTheme.colors
    Column {
        Text(
            text = state.dayName,
            style = GimText.etiqueta,
            color = colors.muted,
            maxLines = 1,
        )
        Text(
            text = stringResource(R.string.entrenar_bloque, state.blockNumber, state.blockCount),
            style = GimText.detalle,
            color = colors.muted,
            maxLines = 1,
        )
    }
}

/** Series hechas / totales del día sobre la barra del contrato (§5 `Meter`). */
@Composable
private fun EntrenarProgress(state: EntrenarUiState) {
    val colors = GimTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GimSpacing.m),
    ) {
        Meter(
            value = state.setsDone.toFloat(),
            max = state.setsTotal.toFloat(),
            color = colors.accent,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.entrenar_progreso, state.setsDone, state.setsTotal),
            style = GimText.etiqueta,
            color = colors.ink2,
            maxLines = 1,
        )
    }
}

/** Indicador de superserie, ejercicio actual y ronda. */
@Composable
private fun EntrenarExercise(state: EntrenarUiState) {
    val colors = GimTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(GimSpacing.xs)) {
        if (state.supersetSize > 1) {
            Text(
                text = stringResource(
                    R.string.entrenar_superserie,
                    state.supersetPosition,
                    state.supersetSize,
                ),
                style = GimText.etiqueta,
                color = colors.ink2,
                maxLines = 1,
            )
        }
        Text(
            text = state.exerciseName,
            style = GimText.titulo,
            color = colors.ink,
        )
        Text(
            text = stringResource(R.string.entrenar_ronda, state.round, state.rounds),
            style = GimText.detalle,
            color = colors.muted,
            maxLines = 1,
        )
        state.jumpTo?.let { next ->
            // Feedback del salto de superserie: el cursor ya está en el próximo ítem.
            Text(
                text = stringResource(R.string.entrenar_seguir_con, next),
                style = GimText.cuerpo,
                color = colors.good,
            )
        }
        if (state.restDone) {
            Text(
                text = stringResource(R.string.entrenar_descanso_terminado),
                style = GimText.cuerpo,
                color = colors.good,
            )
        }
    }
}

/** Cuenta regresiva desde el deadline absoluto, con "Saltar" y "±15 s". */
@Composable
private fun EntrenarRest(
    rest: EntrenarRestUi,
    onSkip: () -> Unit,
    onExtend: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GimSpacing.s),
    ) {
        TimerRing(remaining = rest.remainingSeconds, total = rest.totalSeconds)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GimSpacing.s),
        ) {
            RestAction(
                label = stringResource(R.string.entrenar_restar_15),
                onClick = { onExtend(-15) },
                modifier = Modifier.weight(1f),
            )
            RestAction(
                label = stringResource(R.string.entrenar_saltar),
                onClick = onSkip,
                modifier = Modifier.weight(1f),
            )
            RestAction(
                label = stringResource(R.string.entrenar_sumar_15),
                onClick = { onExtend(15) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Acción de texto del descanso: 48 dp de alto, sin relleno (§3.2). */
@Composable
private fun RestAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = GimTheme.colors
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = GimHeight.row)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = GimText.boton,
            color = colors.ink2,
            maxLines = 1,
        )
    }
}

/** Peso y reps con `SetStepper` grandes + chip de calentamiento. */
@Composable
private fun EntrenarInputs(
    state: EntrenarUiState,
    onWeight: (Double) -> Unit,
    onReps: (Double) -> Unit,
    onToggleWarmup: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GimSpacing.m),
        ) {
            SetStepper(
                value = state.weightDraft,
                step = state.weightStep,
                onValue = onWeight,
                label = stringResource(R.string.ph_peso_serie),
                modifier = Modifier.weight(1f),
                large = true,
            )
            SetStepper(
                value = state.repsDraft,
                step = 1.0,
                onValue = onReps,
                label = stringResource(R.string.ph_reps),
                modifier = Modifier.weight(1f),
                large = true,
                max = MAX_REPS.toDouble(),
            )
        }
        ChipFilter(
            label = stringResource(R.string.entrenar_calentamiento),
            selected = state.isWarmup,
            onClick = onToggleWarmup,
        )
    }
}

/**
 * Corrección de una serie anotada. Es `UPDATE` de `session_set`, no un evento de la
 * máquina: **el cursor no se mueve** (§4). El borrado pide confirmación en el lugar
 * ([ConfirmInline]), nunca un diálogo.
 */
@Composable
private fun SetEditor(
    state: EntrenarUiState,
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
            .padding(vertical = GimSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(GimSpacing.s),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GimSpacing.m),
        ) {
            SetStepper(
                value = state.editingWeight,
                step = state.weightStep,
                onValue = onWeight,
                label = stringResource(R.string.ph_peso_serie),
                modifier = Modifier.weight(1f),
            )
            SetStepper(
                value = state.editingReps,
                step = 1.0,
                onValue = onReps,
                label = stringResource(R.string.ph_reps),
                modifier = Modifier.weight(1f),
                max = MAX_REPS.toDouble(),
            )
        }
        if (state.confirmingDelete) {
            ConfirmInline(
                question = stringResource(R.string.entrenar_borrar_pregunta),
                onConfirm = onDelete,
                onCancel = onCancelDelete,
                confirmLabel = stringResource(R.string.accion_borrar),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GimSpacing.m),
            ) {
                EditorAction(
                    label = stringResource(R.string.entrenar_guardar),
                    color = colors.accent,
                    onClick = onSave,
                )
                EditorAction(
                    label = stringResource(R.string.accion_cancelar),
                    color = colors.muted,
                    onClick = onCancel,
                )
                Spacer(modifier = Modifier.weight(1f))
                EditorAction(
                    label = stringResource(R.string.accion_borrar),
                    color = colors.bad,
                    onClick = onAskDelete,
                )
            }
        }
    }
}

@Composable
private fun EditorAction(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = GimText.boton,
        color = color,
        maxLines = 1,
        modifier = modifier
            .defaultMinSize(minHeight = GimHeight.row)
            .clickable(role = Role.Button, onClick = onClick),
    )
}

/**
 * Placeholder del **fin de sesión**: series anotadas y vuelta a Hoy.
 *
 * El resumen real —PRs del día, volumen, duración y guardado en historial— es GIM-24.
 */
@Composable
private fun EntrenarFinished(
    sets: Int,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GimTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(GimSpacing.l),
        verticalArrangement = Arrangement.spacedBy(GimSpacing.m),
    ) {
        Text(
            text = stringResource(R.string.entrenar_fin_titulo),
            style = GimText.titulo,
            color = colors.ink,
        )
        Text(
            text = pluralStringResource(R.plurals.hoy_series, sets, sets),
            style = GimText.detalle,
            color = colors.muted,
        )
        Text(
            text = stringResource(R.string.entrenar_fin_detalle),
            style = GimText.cuerpo,
            color = colors.ink2,
        )
        Spacer(modifier = Modifier.weight(1f))
        PrimaryButton(
            label = stringResource(R.string.accion_volver_hoy),
            onClick = onExit,
        )
    }
}

/**
 * `keepScreenOn` de §10/§8: flag de **ventana**, sin servicios. Se saca al salir de la
 * pantalla para no dejar el equipo despierto en el resto de la app.
 */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    if (!enabled) return
    val view = LocalView.current
    if (view.isInEditMode) return
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivityWindow()
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/** La activity detrás del contexto de composición, si la hay (preview: no). */
private fun Context.findActivityWindow(): Window? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current.window
        current = current.baseContext
    }
    return (current as? Activity)?.window
}

/** Reps máximas del stepper: el editor de la web topa en 20 series, esto es por serie. */
private const val MAX_REPS: Int = 100

/** Pulso de la cuenta regresiva: dos veces por segundo alcanza para un número en segundos. */
private const val REST_TICK_MS: Long = 500
