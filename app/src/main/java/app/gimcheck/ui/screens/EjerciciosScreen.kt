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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import app.gimcheck.R
import app.gimcheck.core.LoadType
import app.gimcheck.ui.LocalAppContainer
import app.gimcheck.ui.components.ChipFilter
import app.gimcheck.ui.components.ConfirmInline
import app.gimcheck.ui.components.EmptyState
import app.gimcheck.ui.components.PrimaryButton
import app.gimcheck.ui.components.SectionHeader
import app.gimcheck.ui.components.SetStepper
import app.gimcheck.ui.components.ToggleRow
import app.gimcheck.ui.theme.GimHeight
import app.gimcheck.ui.theme.GimRadius
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimStroke
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * **Ejercicios** — §6 fila 8 y `docs/diseno.md` §8 #5 (GIM-18).
 *
 * Lista con buscador y filtro por músculo, y editor inline para alta/edición: nombre,
 * principal, multiarticular, tipo de carga, tiempos y secundarios con factor. El
 * borrado avisa en cuántos lugares se usa y reemplaza el botón por [ConfirmInline].
 *
 * Agregar un músculo es parte de la misma pantalla (sección "Músculos"): el catálogo
 * de la web arranca con 15 y permite sumar más; acá no se duplican por nombre.
 */
@Composable
fun EjerciciosScreen(
    onClearFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    if (container == null) {
        PlaceholderScreen(
            title = stringResource(R.string.nav_ejercicios),
            hint = stringResource(R.string.ph_ejercicios),
            modifier = modifier,
        )
        return
    }

    val scope = rememberCoroutineScope()
    val viewModel = remember(container) { EjerciciosViewModel(container, scope) }
    LaunchedEffect(viewModel) { viewModel.start() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stop() }
    }

    val state by viewModel.state.collectAsState()
    val editing = state.editing
    if (editing == null) {
        ExerciseList(
            state = state,
            onQuery = viewModel::setQuery,
            onMuscle = viewModel::selectMuscle,
            onOpen = viewModel::startEdit,
            onCreate = viewModel::startCreate,
            onNewMuscleName = viewModel::setNewMuscleName,
            onAddMuscle = viewModel::addMuscle,
            onClearFilter = {
                viewModel.clearFilters()
                if (state.rows.isEmpty() && state.allCount == 0) onClearFilter()
            },
            modifier = modifier,
        )
    } else {
        ExerciseEditor(
            state = state,
            edit = editing,
            onBack = viewModel::cancelEdit,
            onName = viewModel::setName,
            onMuscle = viewModel::setPrimaryMuscle,
            onCompound = viewModel::setCompound,
            onLoadType = viewModel::setLoadType,
            onSeconds = viewModel::setSecondsPerSet,
            onRest = viewModel::setRestSeconds,
            onPickSecondary = viewModel::openSecondaryPicker,
            onClosePicker = viewModel::closeSecondaryPicker,
            onAddSecondary = viewModel::addSecondary,
            onFactor = viewModel::setSecondaryFactor,
            onRemoveSecondary = viewModel::removeSecondary,
            onSave = viewModel::save,
            onAskDelete = viewModel::askDelete,
            onCancelDelete = viewModel::cancelDelete,
            onDelete = viewModel::delete,
            modifier = modifier,
        )
    }
}

@Composable
private fun ExerciseList(
    state: EjerciciosUiState,
    onQuery: (String) -> Unit,
    onMuscle: (String?) -> Unit,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onNewMuscleName: (String) -> Unit,
    onAddMuscle: () -> Unit,
    onClearFilter: () -> Unit,
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
            Text(text = stringResource(R.string.nav_ejercicios), style = GimText.titulo, color = colors.ink)
            Text(
                text = if (state.rows.size == state.allCount) {
                    stringResource(R.string.ejercicios_total, state.allCount)
                } else {
                    stringResource(R.string.ejercicios_filtrados, state.rows.size, state.allCount)
                },
                style = GimText.detalle,
                color = colors.muted,
            )
        }

        item {
            SearchField(
                value = state.query,
                onValue = onQuery,
                placeholder = stringResource(R.string.ejercicios_buscar),
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
                    label = stringResource(R.string.ejercicios_todos),
                    selected = state.filterMuscleId == null,
                    onClick = { onMuscle(null) },
                )
                state.muscles.forEach { muscle ->
                    ChipFilter(
                        label = muscle.name,
                        selected = muscle.selected,
                        onClick = { onMuscle(muscle.id) },
                    )
                }
            }
        }

        if (state.rows.isEmpty() && !state.loading) {
            item {
                EmptyState(
                    text = stringResource(R.string.empty_ejercicios_text),
                    actionLabel = stringResource(R.string.empty_ejercicios_action),
                    onAction = onClearFilter,
                )
            }
        }

        items(state.rows, key = { it.id }) { row ->
            ExerciseRowCard(row = row, onClick = { onOpen(row.id) })
        }

        item {
            Spacer(modifier = Modifier.padding(top = GimSpacing.s))
            PrimaryButton(
                label = stringResource(R.string.ejercicios_nuevo),
                onClick = onCreate,
            )
        }

        item {
            SectionHeader(title = stringResource(R.string.ejercicios_musculos))
            Text(
                text = stringResource(R.string.ejercicios_musculos_nota),
                style = GimText.detalle,
                color = colors.muted,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GimSpacing.s),
            ) {
                BasicTextField(
                    value = state.newMuscleName,
                    onValueChange = onNewMuscleName,
                    singleLine = true,
                    textStyle = GimText.cuerpo.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier
                        .weight(1f)
                        .background(colors.surface2, RoundedCornerShape(GimRadius.control))
                        .border(GimStroke.border, colors.line, RoundedCornerShape(GimRadius.control))
                        .padding(horizontal = GimSpacing.m, vertical = GimSpacing.s)
                        .defaultMinSize(minHeight = GimHeight.row),
                )
                ActionText(
                    label = stringResource(R.string.ejercicios_agregar_musculo),
                    color = colors.accent,
                    onClick = onAddMuscle,
                )
            }
            MuscleError(state.muscleError)
        }
    }
}

@Composable
private fun ExerciseRowCard(row: EjercicioRowUi, onClick: () -> Unit) {
    val colors = GimTheme.colors
    val shape = RoundedCornerShape(GimRadius.control)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(GimStroke.border, colors.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(GimSpacing.m),
        verticalArrangement = Arrangement.spacedBy(GimSpacing.xs),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.name,
                style = GimText.cuerpo,
                color = colors.ink,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(text = row.muscleName, style = GimText.etiqueta, color = colors.muted, maxLines = 1)
        }
        Text(text = row.detail, style = GimText.etiqueta, color = colors.muted, maxLines = 1)
        Text(text = row.usageLabel, style = GimText.etiqueta, color = colors.accent, maxLines = 1)
    }
}

@Composable
private fun ExerciseEditor(
    state: EjerciciosUiState,
    edit: EjercicioEditUi,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onMuscle: (String) -> Unit,
    onCompound: (Boolean) -> Unit,
    onLoadType: (LoadType) -> Unit,
    onSeconds: (Double) -> Unit,
    onRest: (Double) -> Unit,
    onPickSecondary: () -> Unit,
    onClosePicker: () -> Unit,
    onAddSecondary: (String) -> Unit,
    onFactor: (String, Double) -> Unit,
    onRemoveSecondary: (String) -> Unit,
    onSave: () -> Unit,
    onAskDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GimTheme.colors
    val muscleNames = state.muscles.associate { it.id to it.name }
    val usage = state.rows.firstOrNull { it.id == edit.id }?.usageLabel ?: ""
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = GimSpacing.l),
        contentPadding = PaddingValues(top = GimSpacing.l, bottom = GimSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(GimSpacing.s),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ejercicios_volver),
                    style = GimText.boton,
                    color = colors.accent,
                    modifier = Modifier
                        .defaultMinSize(minHeight = GimHeight.row)
                        .clickable(role = Role.Button, onClick = onBack)
                        .padding(end = GimSpacing.l),
                )
                Text(
                    text = stringResource(R.string.nav_ejercicios),
                    style = GimText.etiqueta,
                    color = colors.muted,
                )
            }
            Text(
                text = if (edit.id == null) {
                    stringResource(R.string.ejercicios_nuevo)
                } else {
                    stringResource(R.string.ejercicios_editar)
                },
                style = GimText.titulo,
                color = colors.ink,
            )
        }

        item {
            Text(
                text = stringResource(R.string.ejercicios_nombre),
                style = GimText.etiqueta,
                color = colors.muted,
            )
            EditField(value = edit.name, onValue = onName)
        }

        item {
            Text(
                text = stringResource(R.string.ejercicios_musculo_principal),
                style = GimText.etiqueta,
                color = colors.muted,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(GimSpacing.s),
            ) {
                state.muscles.forEach { muscle ->
                    ChipFilter(
                        label = muscle.name,
                        selected = muscle.id == edit.primaryMuscleId,
                        onClick = { onMuscle(muscle.id) },
                    )
                }
            }
        }

        item {
            ToggleRow(
                label = stringResource(R.string.ejercicios_multiarticular),
                checked = edit.compound,
                onChange = onCompound,
            )
            Text(
                text = stringResource(R.string.ejercicios_carga),
                style = GimText.etiqueta,
                color = colors.muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
                ChipFilter(
                    label = stringResource(R.string.ejercicios_carga_externa),
                    selected = edit.loadType == LoadType.EXTERNAL,
                    onClick = { onLoadType(LoadType.EXTERNAL) },
                )
                ChipFilter(
                    label = stringResource(R.string.ejercicios_carga_corporal),
                    selected = edit.loadType == LoadType.BODYWEIGHT,
                    onClick = { onLoadType(LoadType.BODYWEIGHT) },
                )
            }
        }

        item {
            SetStepper(
                value = edit.secondsPerSet,
                step = 5.0,
                onValue = onSeconds,
                label = stringResource(R.string.ejercicios_segundos),
                wholeNumbers = true,
                max = 600.0,
                modifier = Modifier.fillMaxWidth(),
            )
            SetStepper(
                value = edit.restSeconds,
                step = 15.0,
                onValue = onRest,
                label = stringResource(R.string.ejercicios_descanso),
                wholeNumbers = true,
                max = 900.0,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            SectionHeader(title = stringResource(R.string.ejercicios_secundarios))
            if (edit.secondaries.isEmpty()) {
                Text(
                    text = stringResource(R.string.ejercicios_sin_secundarios),
                    style = GimText.detalle,
                    color = colors.muted,
                )
            }
        }

        items(edit.secondaries, key = { it.muscleId }) { secondary ->
            Column(verticalArrangement = Arrangement.spacedBy(GimSpacing.xs)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = muscleNames[secondary.muscleId] ?: secondary.muscleId,
                        style = GimText.cuerpo,
                        color = colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    ActionText(
                        label = stringResource(R.string.ejercicios_quitar),
                        color = colors.bad,
                        onClick = { onRemoveSecondary(secondary.muscleId) },
                    )
                }
                SetStepper(
                    value = secondary.factor,
                    step = 0.1,
                    onValue = { onFactor(secondary.muscleId, it) },
                    label = stringResource(R.string.ejercicios_factor),
                    min = 0.1,
                    max = 1.0,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            if (edit.pickingSecondary) {
                Text(
                    text = stringResource(R.string.ejercicios_elegir_musculo),
                    style = GimText.etiqueta,
                    color = colors.muted,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(GimSpacing.s),
                ) {
                    state.muscles
                        .filter { it.id != edit.primaryMuscleId && edit.secondaries.none { s -> s.muscleId == it.id } }
                        .forEach { muscle ->
                            ChipFilter(label = muscle.name, selected = false, onClick = { onAddSecondary(muscle.id) })
                        }
                }
                ActionText(
                    label = stringResource(R.string.accion_cancelar),
                    color = colors.muted,
                    onClick = onClosePicker,
                )
            } else {
                ActionText(
                    label = stringResource(R.string.ejercicios_agregar_secundario),
                    color = colors.accent,
                    onClick = onPickSecondary,
                )
            }
            EditError(edit.error)
        }

        item {
            Spacer(modifier = Modifier.padding(top = GimSpacing.s))
            PrimaryButton(
                label = stringResource(R.string.ejercicios_guardar),
                onClick = onSave,
            )
        }

        if (edit.id != null) {
            item {
                if (edit.confirmingDelete) {
                    ConfirmInline(
                        question = stringResource(
                            R.string.ejercicios_borrar_pregunta,
                            edit.name,
                            usage,
                        ),
                        onConfirm = onDelete,
                        onCancel = onCancelDelete,
                        confirmLabel = stringResource(R.string.accion_borrar),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.ejercicios_borrar),
                        style = GimText.boton,
                        color = colors.bad,
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = GimHeight.row)
                            .clickable(role = Role.Button, onClick = onAskDelete),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValue: (String) -> Unit, placeholder: String) {
    val colors = GimTheme.colors
    val shape = RoundedCornerShape(GimRadius.control)
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = GimText.cuerpo.copy(color = colors.ink),
        cursorBrush = SolidColor(colors.accent),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface2, shape)
            .border(GimStroke.border, colors.line, shape)
            .padding(horizontal = GimSpacing.m, vertical = GimSpacing.s)
            .defaultMinSize(minHeight = GimHeight.row),
        decorationBox = { inner ->
            androidx.compose.foundation.layout.Box {
                if (value.isEmpty()) {
                    Text(text = placeholder, style = GimText.cuerpo, color = colors.muted)
                }
                inner()
            }
        },
    )
}

@Composable
private fun EditField(value: String, onValue: (String) -> Unit) {
    val colors = GimTheme.colors
    val shape = RoundedCornerShape(GimRadius.control)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = GimText.cuerpo.copy(color = colors.ink),
        cursorBrush = SolidColor(colors.accent),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface2, shape)
            .border(GimStroke.border, colors.line, shape)
            .padding(horizontal = GimSpacing.m, vertical = GimSpacing.s)
            .defaultMinSize(minHeight = GimHeight.row)
            .focusRequester(focus),
    )
}

@Composable
private fun EditError(error: EjercicioError?) {
    if (error == null) return
    val text = when (error) {
        EjercicioError.NOMBRE_VACIO -> R.string.ejercicios_error_nombre
        EjercicioError.NOMBRE_DUPLICADO -> R.string.ejercicios_error_duplicado
        EjercicioError.SIN_MUSCULO -> R.string.ejercicios_error_musculo
        EjercicioError.TIEMPO_INVALIDO -> R.string.ejercicios_error_tiempo
        EjercicioError.FACTOR_INVALIDO -> R.string.ejercicios_error_factor
        EjercicioError.MUSCULO_DUPLICADO -> R.string.ejercicios_musculo_duplicado
    }
    Text(
        text = stringResource(text),
        style = GimText.detalle,
        color = GimTheme.colors.bad,
        modifier = Modifier.padding(top = GimSpacing.xs),
    )
}

@Composable
private fun MuscleError(error: EjercicioError?) {
    if (error == null) return
    val text = when (error) {
        EjercicioError.NOMBRE_VACIO -> R.string.ejercicios_musculo_vacio
        EjercicioError.NOMBRE_DUPLICADO -> R.string.ejercicios_musculo_duplicado
        else -> R.string.ejercicios_musculo_vacio
    }
    Text(
        text = stringResource(text),
        style = GimText.detalle,
        color = GimTheme.colors.bad,
    )
}

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
