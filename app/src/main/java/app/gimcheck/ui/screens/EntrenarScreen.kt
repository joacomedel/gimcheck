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
import androidx.compose.foundation.lazy.items
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
import app.gimcheck.rest.AndroidRestGateway
import app.gimcheck.ui.LocalAppContainer
import app.gimcheck.ui.components.ChipFilter
import app.gimcheck.ui.components.ConfirmInline
import app.gimcheck.ui.components.EmptyState
import app.gimcheck.ui.components.Meter
import app.gimcheck.ui.components.PrimaryButton
import app.gimcheck.ui.components.SectionHeader
import app.gimcheck.ui.components.SetRow
import app.gimcheck.ui.components.SetStepper
import app.gimcheck.ui.components.StatTile
import app.gimcheck.ui.components.TimerRing
import app.gimcheck.ui.theme.GimHeight
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme
import kotlinx.coroutines.delay

/**
 * **Entrenar** — el entrenamiento abierto del dueño (GIM-49).
 *
 * La pantalla ya no recorre un día planificado ni pide "empezar sesión": hay **un
 * entrenamiento** donde se cargan series del ejercicio elegido. El nombre del ejercicio
 * es un botón que abre el selector (rutina activa, con `+` para ver todas las rutinas) y
 * la superserie se avisa arriba: al anotar la serie, el cursor pasa una vez al siguiente
 * ejercicio sin descanso y se queda ahí.
 *
 * **Cero lógica de negocio acá**: el estado viene de [EntrenarViewModel]; el peso, las
 * reps y el descanso se dibujan tal cual. La pantalla no decide cuándo hay descanso ni a
 * qué ejercicio se salta.
 *
 * **Salir** no termina el entrenamiento (queda abierto). **Terminar** lo cierra y muestra
 * el resumen real (duración, volumen, series, PRs y volumen por músculo). **Descartar** lo
 * deja `ABANDONED` y no genera resumen.
 */
@Composable
fun EntrenarScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    if (container == null) {
        // Preview o test de composición sin app: no hay entrenamiento que mostrar.
        PlaceholderScreen(
            title = stringResource(R.string.nav_entrenar),
            hint = stringResource(R.string.ph_entrenar),
            modifier = modifier,
        )
        return
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // El cronómetro de afuera (servicio en primer plano + alarma exacta, GIM-22): la
    // pantalla le pasa el deadline, no un contador. Sin esto el descanso moría con la
    // pantalla.
    val restGateway = remember(context) { AndroidRestGateway(context.applicationContext) }
    val viewModel = remember(container, restGateway) {
        EntrenarViewModel(container, scope, restGateway)
    }
    LaunchedEffect(viewModel) { viewModel.start() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stop() }
    }

    val state by viewModel.state.collectAsState()

    RestPermissionPrompt(active = state.phase == EntrenarPhase.ACTIVE)

    KeepScreenOn(enabled = state.keepScreenOn && state.phase == EntrenarPhase.ACTIVE)

    // Pulso del cronómetro: el ViewModel decide si el descanso venció; acá sólo se pide.
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
        onOpenPicker = viewModel::openPicker,
        onClosePicker = viewModel::closePicker,
        onShowAllRoutines = viewModel::showAllRoutines,
        onShowActiveRoutine = viewModel::showActiveRoutine,
        onPickExercise = viewModel::pickExercise,
        onFinish = { viewModel.finish() },
        onExit = onExit,
        onAbandon = { viewModel.abandon(onExit) },
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
    onOpenPicker: () -> Unit,
    onClosePicker: () -> Unit,
    onShowAllRoutines: () -> Unit,
    onShowActiveRoutine: () -> Unit,
    onPickExercise: (String) -> Unit,
    onFinish: () -> Unit,
    onExit: () -> Unit,
    onAbandon: () -> Unit,
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
            state = state,
            onExit = onExit,
            modifier = modifier,
        )

        EntrenarPhase.ACTIVE -> {
            val picker = state.picker
            if (picker != null) {
                EntrenarPicker(
                    picker = picker,
                    onPick = onPickExercise,
                    onShowAll = onShowAllRoutines,
                    onShowActive = onShowActiveRoutine,
                    onClose = onClosePicker,
                    modifier = modifier,
                )
            } else {
                EntrenarActive(
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
                    onOpenPicker = onOpenPicker,
                    onFinish = onFinish,
                    onExit = onExit,
                    onAbandon = onAbandon,
                    modifier = modifier,
                )
            }
        }
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
    onOpenPicker: () -> Unit,
    onFinish: () -> Unit,
    onExit: () -> Unit,
    onAbandon: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GimTheme.colors
    var confirmingExit by remember { mutableStateOf(false) }
    var confirmingDiscard by remember { mutableStateOf(false) }

    // Salir no termina el entrenamiento: pide confirmación y vuelve a Hoy. Terminar cierra
    // la sesión (y muestra el resumen); descartar la deja ABANDONED.
    BackHandler {
        when {
            confirmingDiscard -> confirmingDiscard = false
            confirmingExit -> confirmingExit = false
            else -> confirmingExit = true
        }
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
            item { EntrenarExercise(state, onOpenPicker) }

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

            state.sets.forEach { group ->
                item(key = group.itemId) {
                    Column(verticalArrangement = Arrangement.spacedBy(GimSpacing.xs)) {
                        SectionHeader(title = group.exerciseName)
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
            when {
                confirmingDiscard -> ConfirmInline(
                    question = stringResource(R.string.ph_confirmar_abandono),
                    onConfirm = onAbandon,
                    onCancel = { confirmingDiscard = false },
                    confirmLabel = stringResource(R.string.entrenar_descartar),
                )

                confirmingExit -> {
                    ConfirmInline(
                        question = stringResource(R.string.ph_confirmar_salida),
                        onConfirm = onExit,
                        onCancel = { confirmingExit = false },
                        confirmLabel = stringResource(R.string.accion_salir),
                    )
                    Text(
                        text = stringResource(R.string.entrenar_terminar),
                        style = GimText.boton,
                        color = colors.accent,
                        maxLines = 1,
                        modifier = Modifier
                            .defaultMinSize(minHeight = GimHeight.row)
                            .clickable(role = Role.Button) { onFinish() },
                    )
                    Text(
                        text = stringResource(R.string.entrenar_descartar),
                        style = GimText.boton,
                        color = colors.bad,
                        maxLines = 1,
                        modifier = Modifier
                            .defaultMinSize(minHeight = GimHeight.row)
                            .clickable(role = Role.Button) { confirmingDiscard = true },
                    )
                }

                else -> Text(
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

/** Rutina del entrenamiento y cuántas series van. */
@Composable
private fun EntrenarHeader(state: EntrenarUiState) {
    val colors = GimTheme.colors
    val label = if (state.routineName.isBlank()) {
        stringResource(R.string.entrenar_libre)
    } else {
        state.routineName
    }
    Text(
        text = label,
        style = GimText.etiqueta,
        color = colors.muted,
        maxLines = 1,
    )
}

/** Series de trabajo anotadas en el entrenamiento. */
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
            max = state.setsDone.coerceAtLeast(1).toFloat(),
            color = colors.accent,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.entrenar_series_hechas, state.setsDone),
            style = GimText.etiqueta,
            color = colors.ink2,
            maxLines = 1,
        )
    }
}

/**
 * Ejercicio actual: el nombre es el botón del selector. Debajo, el músculo y el aviso de
 * superserie o el salto que acaba de hacer la app.
 */
@Composable
private fun EntrenarExercise(state: EntrenarUiState, onOpenPicker: () -> Unit) {
    val colors = GimTheme.colors
    val change = stringResource(R.string.entrenar_cambiar_ejercicio)
    Column(verticalArrangement = Arrangement.spacedBy(GimSpacing.xs)) {
        Text(
            text = state.exerciseName,
            style = GimText.titulo,
            color = colors.ink,
            modifier = Modifier.clickable(
                onClickLabel = change,
                role = Role.Button,
                onClick = onOpenPicker,
            ),
        )
        if (state.muscleName.isNotBlank()) {
            Text(
                text = state.muscleName,
                style = GimText.detalle,
                color = colors.muted,
                maxLines = 1,
            )
        }
        Text(
            text = change,
            style = GimText.etiqueta,
            color = colors.accent,
            maxLines = 1,
            modifier = Modifier.clickable(
                onClickLabel = change,
                role = Role.Button,
                onClick = onOpenPicker,
            ),
        )
        state.supersetNextName?.let { next ->
            Text(
                text = stringResource(R.string.entrenar_superserie_sigue, next),
                style = GimText.detalle,
                color = colors.ink2,
            )
        }
        state.jumpTo?.let { next ->
            // Feedback del salto de superserie: el cursor ya está en el próximo ejercicio.
            Text(
                text = stringResource(R.string.entrenar_seguir_con, next),
                style = GimText.cuerpo,
                color = colors.good,
            )
        }
        if (state.restDone) {
            Text(
                text = stringResource(R.string.entrenar_descanso_terminado_inline),
                style = GimText.cuerpo,
                color = colors.good,
            )
        }
    }
}

/**
 * Selector del nombre del ejercicio (GIM-49): la rutina activa agrupada por día y la
 * acción `+ Ver todas las rutinas`. Elegir un ejercicio lo deja como actual; no navega.
 */
@Composable
private fun EntrenarPicker(
    picker: EntrenarPickerUi,
    onPick: (String) -> Unit,
    onShowAll: () -> Unit,
    onShowActive: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GimTheme.colors
    BackHandler(onBack = onClose)
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GimSpacing.l)
                .padding(top = GimSpacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.entrenar_elegir_ejercicio),
                    style = GimText.titulo,
                    color = colors.ink,
                )
                Text(
                    text = stringResource(
                        if (picker.showingAll) R.string.entrenar_todas_rutinas else R.string.entrenar_tu_rutina,
                    ),
                    style = GimText.detalle,
                    color = colors.muted,
                )
            }
            ActionText(
                label = stringResource(R.string.accion_cancelar),
                color = colors.ink2,
                onClick = onClose,
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = GimSpacing.l),
            contentPadding = PaddingValues(top = GimSpacing.l, bottom = GimSpacing.l),
            verticalArrangement = Arrangement.spacedBy(GimSpacing.xs),
        ) {
            picker.groups.forEach { group ->
                item(key = "grupo-${group.routineId}-${group.dayName}") {
                    SectionHeader(
                        title = if (picker.showingAll) {
                            "${group.routineName} · ${group.dayName}"
                        } else {
                            group.dayName
                        },
                    )
                }
                items(
                    items = group.items,
                    key = { item -> "ej-${group.routineId}-${group.dayName}-${item.exerciseId}" },
                ) { item ->
                    Text(
                        text = item.name,
                        style = GimText.cuerpo,
                        color = colors.ink,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = GimHeight.row)
                            .clickable(role = Role.Button) { onPick(item.exerciseId) },
                    )
                }
            }
            item(key = "picker-mas") {
                ActionText(
                    label = stringResource(
                        if (picker.showingAll) R.string.entrenar_volver_rutina else R.string.entrenar_ver_todas,
                    ),
                    color = colors.accent,
                    onClick = if (picker.showingAll) onShowActive else onShowAll,
                )
            }
        }
    }
}

/** Acción de texto alineada a la izquierda (compartida por el picker). */
@Composable
private fun ActionText(label: String, color: Color, onClick: () -> Unit) {
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

/** Peso y reps con `SetStepper` grandes (el número también se escribe) + calentamiento. */
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
                wholeNumbers = true,
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
 * Corrección de una serie anotada. Es `UPDATE` de `session_set`, no un evento del
 * entrenamiento: **el ejercicio actual no cambia**. El borrado pide confirmación en el
 * lugar ([ConfirmInline]), nunca un diálogo.
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
                wholeNumbers = true,
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
 * **Fin de entrenamiento**: resumen de lo que quedó guardado.
 *
 * Un solo scroll con la jerarquía del contrato: título + rutina, duración y series en
 * `StatTile`, volumen total, `SectionHeader` "PRs del día" y `SectionHeader` "Volumen por
 * músculo" con `Meter` por músculo (top 5). La acción primaria "Listo" vuelve a Hoy.
 */
@Composable
private fun EntrenarFinished(
    state: EntrenarUiState,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GimTheme.colors
    val summary = state.summary
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
                Column {
                    Text(
                        text = stringResource(R.string.entrenar_fin_titulo),
                        style = GimText.titulo,
                        color = colors.ink,
                    )
                    Text(
                        text = summary?.dayName ?: state.routineName,
                        style = GimText.detalle,
                        color = colors.muted,
                        maxLines = 1,
                    )
                }
            }

            if (summary == null) {
                item {
                    Text(
                        text = pluralStringResource(
                            R.plurals.hoy_series,
                            state.finishedSets,
                            state.finishedSets,
                        ),
                        style = GimText.detalle,
                        color = colors.muted,
                    )
                }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
                        StatTile(
                            k = stringResource(R.string.entrenar_fin_duracion),
                            v = summary.durationLabel,
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            k = stringResource(R.string.entrenar_fin_series),
                            v = summary.setsLabel,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                item {
                    StatTile(
                        k = stringResource(R.string.entrenar_fin_volumen),
                        v = summary.volumeLabel,
                        s = summary.dayName,
                    )
                }

                item { SectionHeader(title = stringResource(R.string.entrenar_fin_prs)) }
                if (summary.prs.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.entrenar_fin_sin_prs),
                            style = GimText.detalle,
                            color = colors.muted,
                        )
                    }
                } else {
                    items(summary.prs) { pr -> PrRow(pr) }
                }

                if (summary.muscles.isNotEmpty()) {
                    item { SectionHeader(title = stringResource(R.string.entrenar_fin_musculos)) }
                    items(summary.muscles) { muscle -> MuscleVolumeRow(muscle) }
                }

                item {
                    Text(
                        text = stringResource(R.string.entrenar_fin_guardado),
                        style = GimText.etiqueta,
                        color = colors.muted,
                    )
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = GimSpacing.l)) {
            PrimaryButton(
                label = stringResource(R.string.accion_listo),
                onClick = onExit,
            )
            Spacer(modifier = Modifier.height(GimSpacing.l))
        }
    }
}

/** Un PR del día: ejercicio y peso×reps a la izquierda, e1RM y badge a la derecha. */
@Composable
private fun PrRow(pr: EntrenarPrUi) {
    val colors = GimTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = GimHeight.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pr.exerciseName,
                style = GimText.cuerpo,
                color = colors.ink,
                maxLines = 1,
            )
            Text(
                text = pr.setLabel,
                style = GimText.detalle,
                color = colors.muted,
                maxLines = 1,
            )
        }
        Text(
            text = pr.e1rmLabel,
            style = GimText.subtitulo,
            color = colors.ink,
            maxLines = 1,
        )
        Text(
            text = stringResource(R.string.entrenar_fin_pr_badge),
            style = GimText.etiqueta,
            color = colors.good,
            maxLines = 1,
            modifier = Modifier.padding(start = GimSpacing.s),
        )
    }
}

/** Una fila de volumen por músculo: nombre, series efectivas y `Meter` (§6 #6). */
@Composable
private fun MuscleVolumeRow(muscle: EntrenarMuscleUi) {
    val colors = GimTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(GimSpacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = muscle.name,
                style = GimText.detalle,
                color = colors.ink2,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = muscle.setsLabel,
                style = GimText.detalle,
                color = colors.ink,
                maxLines = 1,
            )
        }
        Meter(
            value = muscle.fraction,
            max = 1f,
            color = colors.accent,
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
