package app.gimcheck.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * **Rutinas** — §6 fila 4 y `docs/diseno.md` §8 #4 (GIM-16).
 *
 * De arriba abajo: título con la puerta a Ejercicios · `SectionHeader` "Planes" con
 * "Nuevo plan" · una tarjeta por plan (nombre, activo marcado, días/series/duración y
 * las acciones crear, duplicar, renombrar, exportar, eliminar) · `SectionHeader` "Días"
 * con "Agregar día" · chips de día · `StatTile` de duración y series del día · ítems en
 * orden con drop set y superserie · advertencias en `warn` · acciones del día.
 *
 * **"Editar día" convierte el bloque del día en el editor de GIM-17** (misma pantalla,
 * mismo grafo): métricas del borrador, ítems con `SetStepper` y toggles, mover/quitar,
 * selector de ejercicio y guardado explícito. En modo edición el detalle solo lectura de
 * GIM-16 se reemplaza, así que no hay dos verdades en pantalla.
 *
 * El detalle solo lectura no calcula nada; el editor tampoco: todo (métricas del
 * borrador, advertencias, errores tipados y textos) viene resuelto de
 * [RutinasViewModel]. Los colores salen de [GimTheme], nunca literales.
 *
 * **Exportar** arma el JSON de la web en el ViewModel (`exportPlan` de `:core`) y acá
 * sólo se comparte con `Intent.ACTION_SEND`: no hay permiso de storage ni archivo
 * intermedio.
 */
@Composable
fun RutinasScreen(
    onOpenExercises: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    if (container == null) {
        PlaceholderScreen(
            title = stringResource(R.string.nav_rutinas),
            hint = stringResource(R.string.ph_rutinas),
            modifier = modifier,
        )
        return
    }

    val scope = rememberCoroutineScope()
    val viewModel = remember(container) { RutinasViewModel(container, scope) }
    LaunchedEffect(viewModel) { viewModel.start() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stop() }
    }

    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var pendingExport by remember { mutableStateOf<RoutineExportReady?>(null) }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text == null) {
                viewModel.readFailed()
            } else {
                viewModel.previewImport(displayName(context, uri), text)
            }
        }
    }

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val ready = pendingExport
        pendingExport = null
        if (uri == null || ready == null) return@rememberLauncherForActivityResult
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                        writer.write(ready.json)
                    } != null
                }.getOrDefault(false)
            }
            if (written) viewModel.exportWritten() else viewModel.exportFailed()
        }
    }

    RutinasContent(
        state = state,
        onImportRoutines = {
            openFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        },
        onExportAll = {
            viewModel.exportAll { ready ->
                pendingExport = ready
                createFile.launch(ready.name)
            }
        },
        onConfirmImport = viewModel::confirmImport,
        onDismissTransfer = viewModel::dismissTransfer,
        onSelectPlan = viewModel::selectPlan,
        onCreatePlan = viewModel::createPlan,
        onDuplicatePlan = viewModel::duplicatePlan,
        onRenamePlan = viewModel::renamePlan,
        onDeletePlan = viewModel::deletePlan,
        onSetActivePlan = viewModel::setActivePlan,
        onExportPlan = { planId ->
            val planName = state.plans.firstOrNull { it.id == planId }?.name.orEmpty()
            viewModel.exportPlanJson(planId) { json ->
                if (json != null) sharePlan(context, planName, json)
            }
        },
        onSelectDay = viewModel::selectDay,
        onAddDay = viewModel::addDay,
        onDuplicateDay = viewModel::duplicateDay,
        onRenameDay = viewModel::renameDay,
        onDeleteDay = viewModel::deleteDay,
        onAddExercise = viewModel::startEditWithPicker,
        onOpenExercises = onOpenExercises,
        editor = DiaEditorActions(
            onStart = viewModel::startEdit,
            onCancel = viewModel::cancelEdit,
            onSave = viewModel::saveEdit,
            onOpenPicker = viewModel::openPicker,
            onClosePicker = viewModel::closePicker,
            onAdd = viewModel::addExercise,
            onMove = viewModel::moveItem,
            onRemove = viewModel::removeItem,
            onSets = viewModel::setSets,
            onDropSet = viewModel::toggleDropSet,
            onSuperset = viewModel::toggleSupersetNext,
        ),
        modifier = modifier,
    )
}

/**
 * Los eventos del editor del día (GIM-17), agrupados para no arrastrar diez lambdas por
 * dos composables. Todos terminan en [RutinasViewModel]: la pantalla no decide nada.
 */
private data class DiaEditorActions(
    val onStart: () -> Unit,
    val onCancel: () -> Unit,
    val onSave: () -> Unit,
    val onOpenPicker: () -> Unit,
    val onClosePicker: () -> Unit,
    val onAdd: (String) -> Unit,
    val onMove: (String, Int) -> Unit,
    val onRemove: (String) -> Unit,
    val onSets: (String, Int) -> Unit,
    val onDropSet: (String) -> Unit,
    val onSuperset: (String) -> Unit,
)

@Composable
private fun RutinasContent(
    state: RutinasUiState,
    onImportRoutines: () -> Unit,
    onExportAll: () -> Unit,
    onConfirmImport: () -> Unit,
    onDismissTransfer: () -> Unit,
    onSelectPlan: (String) -> Unit,
    onCreatePlan: () -> Unit,
    onDuplicatePlan: (String) -> Unit,
    onRenamePlan: (String, String) -> Unit,
    onDeletePlan: (String) -> Unit,
    onSetActivePlan: (String) -> Unit,
    onExportPlan: (String) -> Unit,
    onSelectDay: (String) -> Unit,
    onAddDay: () -> Unit,
    onDuplicateDay: (String) -> Unit,
    onRenameDay: (String, String) -> Unit,
    onDeleteDay: (String) -> Unit,
    onAddExercise: () -> Unit,
    onOpenExercises: () -> Unit,
    editor: DiaEditorActions,
    modifier: Modifier = Modifier,
) {
    val colors = GimTheme.colors

    (state.transfer as? RutinasTransferUi.Confirm)?.let { confirm ->
        AlertDialog(
            onDismissRequest = onDismissTransfer,
            title = { Text(stringResource(R.string.rutinas_importar_titulo, confirm.fileName)) },
            text = {
                Text(
                    stringResource(
                        R.string.rutinas_importar_texto,
                        confirm.routines,
                        confirm.days,
                        confirm.exercisesCreated,
                        confirm.exercisesReused,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmImport) {
                    Text(stringResource(R.string.rutinas_importar_confirmar))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissTransfer) {
                    Text(stringResource(R.string.rutinas_importar_cancelar))
                }
            },
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = GimSpacing.l),
        contentPadding = PaddingValues(top = GimSpacing.l, bottom = GimSpacing.l),
        verticalArrangement = Arrangement.spacedBy(GimSpacing.s),
    ) {
        item(key = "titulo") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.nav_rutinas),
                    style = GimText.titulo,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                ActionText(
                    label = stringResource(R.string.nav_ejercicios),
                    color = colors.accent,
                    onClick = onOpenExercises,
                )
            }
        }

        if (state.loading) return@LazyColumn

        item(key = "transfer-header") {
            SectionHeader(
                title = stringResource(R.string.rutinas_transferir),
                action = stringResource(R.string.rutinas_importar),
                onAction = onImportRoutines,
            )
        }

        item(key = "transfer-actions") {
            Column(verticalArrangement = Arrangement.spacedBy(GimSpacing.xs)) {
                ActionText(
                    label = stringResource(R.string.rutinas_exportar_todas),
                    color = colors.accent,
                    onClick = onExportAll,
                )
                Text(
                    text = stringResource(R.string.rutinas_transferir_nota),
                    style = GimText.detalle,
                    color = colors.muted,
                )
            }
        }

        val notice = state.transfer
        if (notice != null && notice !is RutinasTransferUi.Confirm) {
            item(key = "transfer-notice") {
                TransferNotice(notice = notice, onDismiss = onDismissTransfer)
            }
        }

        item(key = "planes-header") {
            SectionHeader(
                title = stringResource(R.string.rutinas_planes),
                action = stringResource(R.string.rutinas_nuevo_plan),
                onAction = onCreatePlan,
            )
        }

        if (!state.hasPlans) {
            item(key = "sin-planes") {
                EmptyState(
                    text = stringResource(R.string.empty_hoy_text),
                    actionLabel = stringResource(R.string.empty_hoy_action),
                    onAction = onCreatePlan,
                )
            }
            return@LazyColumn
        }

        items(state.plans, key = { "plan-${it.id}" }) { plan ->
            PlanCard(
                plan = plan,
                canDelete = state.canDeletePlan,
                onSelect = { onSelectPlan(plan.id) },
                onDuplicate = { onDuplicatePlan(plan.id) },
                onRename = { name -> onRenamePlan(plan.id, name) },
                onDelete = { onDeletePlan(plan.id) },
                onSetActive = { onSetActivePlan(plan.id) },
                onExport = { onExportPlan(plan.id) },
            )
        }

        item(key = "dias-header") {
            SectionHeader(
                title = stringResource(R.string.rutinas_dias),
                action = stringResource(R.string.rutinas_agregar_dia),
                onAction = onAddDay,
            )
        }

        val day = state.day
        if (state.days.isEmpty() || day == null) {
            item(key = "sin-dias") {
                EmptyState(
                    text = stringResource(R.string.rutinas_sin_dias),
                    actionLabel = stringResource(R.string.rutinas_agregar_dia),
                    onAction = onAddDay,
                )
            }
            return@LazyColumn
        }

        item(key = "chips-dias") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(GimSpacing.s),
            ) {
                state.days.forEach { chip ->
                    ChipFilter(
                        label = chip.name,
                        selected = chip.selected,
                        onClick = { onSelectDay(chip.id) },
                    )
                }
            }
        }

        // Modo edición (GIM-17): reemplaza el detalle solo lectura de abajo. Las métricas
        // y las advertencias son del borrador y se recalculan en el ViewModel.
        val draft = state.editor
        if (draft != null) {
            diaEditor(draft, editor)
            return@LazyColumn
        }

        item(key = "stats-dia") {
            Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
                StatTile(
                    k = stringResource(R.string.ph_duracion),
                    v = day.durationLabel,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    k = stringResource(R.string.ph_series),
                    v = day.setsLabel,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (day.items.isEmpty()) {
            item(key = "dia-vacio") {
                EmptyState(
                    text = stringResource(R.string.empty_rutinas_text),
                    actionLabel = stringResource(R.string.empty_rutinas_action),
                    onAction = onAddExercise,
                )
            }
        } else {
            items(day.items, key = { "item-${it.id}" }) { item ->
                DayItemRow(item = item)
            }
        }

        if (day.warnings.isNotEmpty()) {
            item(key = "advertencias") {
                WarningBlock(warnings = day.warnings)
            }
        }

        item(key = "acciones-dia") {
            DayActions(
                day = day,
                onAddExercise = onAddExercise,
                onDuplicate = { onDuplicateDay(day.id) },
                onRename = { name -> onRenameDay(day.id, name) },
                onDelete = { onDeleteDay(day.id) },
                onEdit = editor.onStart,
            )
        }
    }
}

/**
 * Editor del día dentro del `LazyColumn` de Rutinas: métricas del borrador arriba,
 * ítems editables, advertencias y errores en vivo, selector de ejercicio y guardado al
 * final (la acción primaria va abajo, `docs/diseno.md` §5).
 */
private fun LazyListScope.diaEditor(
    editor: DiaEditorUiState,
    actions: DiaEditorActions,
) {
    item(key = "editor-stats") {
        Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
            StatTile(
                k = stringResource(R.string.ph_duracion),
                v = editor.durationLabel,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                k = stringResource(R.string.ph_series),
                v = editor.setsLabel,
                modifier = Modifier.weight(1f),
            )
        }
    }

    item(key = "editor-workrest") {
        Text(
            text = editor.workRestLabel,
            style = GimText.detalle,
            color = GimTheme.colors.muted,
        )
    }

    if (editor.items.isEmpty()) {
        item(key = "editor-vacio") {
            EmptyState(
                text = stringResource(R.string.rutinas_editor_vacio),
                actionLabel = stringResource(R.string.rutinas_editor_agregar),
                onAction = actions.onOpenPicker,
            )
        }
    }

    items(editor.items, key = { "editor-${it.id}" }) { item ->
        EditorItemRow(
            item = item,
            isFirst = item.position == 1,
            actions = actions,
        )
    }

    if (editor.warnings.isNotEmpty()) {
        item(key = "editor-advertencias") { WarningBlock(warnings = editor.warnings) }
    }

    if (editor.problems.isNotEmpty()) {
        item(key = "editor-errores") { ProblemBlock(problems = editor.problems) }
    }

    if (editor.rejected) {
        item(key = "editor-rechazado") {
            Text(
                text = stringResource(R.string.rutinas_editor_rechazado),
                style = GimText.cuerpo,
                color = GimTheme.colors.bad,
            )
        }
    }

    item(key = "editor-nota") {
        Text(
            text = stringResource(R.string.rutinas_editor_nota),
            style = GimText.detalle,
            color = GimTheme.colors.muted,
        )
    }

    if (editor.picking) {
        item(key = "editor-picker-header") {
            SectionHeader(
                title = stringResource(R.string.rutinas_editor_elegir),
                action = stringResource(R.string.rutinas_editor_cerrar),
                onAction = actions.onClosePicker,
            )
        }
        if (editor.catalog.isEmpty()) {
            item(key = "editor-picker-vacio") {
                Text(
                    text = stringResource(R.string.rutinas_editor_sin_catalogo),
                    style = GimText.detalle,
                    color = GimTheme.colors.muted,
                )
            }
        }
        items(editor.catalog, key = { "catalogo-${it.id}" }) { option ->
            ExerciseOptionRow(option = option, onClick = { actions.onAdd(option.id) })
        }
    }

    item(key = "editor-acciones") {
        Column(verticalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
            if (!editor.picking) {
                ActionText(
                    label = stringResource(R.string.rutinas_editor_agregar),
                    color = GimTheme.colors.accent,
                    onClick = actions.onOpenPicker,
                )
            }
            PrimaryButton(
                label = stringResource(R.string.rutinas_editor_guardar),
                onClick = actions.onSave,
                enabled = editor.canSave,
            )
            ActionText(
                label = stringResource(R.string.rutinas_editor_descartar),
                color = GimTheme.colors.muted,
                onClick = actions.onCancel,
            )
        }
    }
}

/**
 * Ítem editable: número, ejercicio, subir/bajar, `SetStepper` de series, toggles de drop
 * set y superserie (deshabilitado en el último) y quitar con `ConfirmInline`.
 */
@Composable
private fun EditorItemRow(
    item: DiaEditorItemUi,
    isFirst: Boolean,
    actions: DiaEditorActions,
) {
    val colors = GimTheme.colors
    val shape = RoundedCornerShape(GimRadius.control)
    var confirming by remember(item.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(
                GimStroke.border,
                if (item.problemCodes.isEmpty()) colors.line else colors.bad,
                shape,
            )
            .padding(GimSpacing.m),
        verticalArrangement = Arrangement.spacedBy(GimSpacing.s),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GimSpacing.m),
        ) {
            Text(text = item.position.toString(), style = GimText.etiqueta, color = colors.muted)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = GimText.cuerpo,
                    color = colors.ink,
                    maxLines = 2,
                )
                Text(
                    text = item.muscle,
                    style = GimText.detalle,
                    color = colors.muted,
                    maxLines = 1,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(GimSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorAction(
                label = stringResource(R.string.rutinas_editor_subir),
                enabled = !isFirst,
                color = colors.ink2,
                onClick = { actions.onMove(item.id, -1) },
            )
            EditorAction(
                label = stringResource(R.string.rutinas_editor_bajar),
                enabled = item.canSuperset,
                color = colors.ink2,
                onClick = { actions.onMove(item.id, 1) },
            )
            EditorAction(
                label = stringResource(R.string.rutinas_editor_quitar),
                enabled = true,
                color = colors.bad,
                onClick = { confirming = true },
            )
        }

        SetStepper(
            value = item.sets.toDouble(),
            step = 1.0,
            min = 1.0,
            max = 20.0,
            label = stringResource(R.string.rutinas_editor_series),
            onValue = { actions.onSets(item.id, it.roundToInt()) },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(GimSpacing.s),
        ) {
            EditorToggle(
                label = stringResource(R.string.rutinas_editor_drop_set),
                on = item.dropSet,
                onClick = { actions.onDropSet(item.id) },
            )
            EditorToggle(
                label = stringResource(R.string.rutinas_editor_superserie),
                on = item.supersetNext,
                enabled = item.canSuperset,
                onClick = { actions.onSuperset(item.id) },
            )
        }

        Text(text = item.detail, style = GimText.detalle, color = colors.muted)
        if (!item.canSuperset) {
            Text(
                text = stringResource(R.string.rutinas_editor_superserie_ultimo),
                style = GimText.detalle,
                color = colors.muted,
            )
        }
        item.problemTexts.forEach { problem ->
            Text(text = problem, style = GimText.detalle, color = colors.bad)
        }

        if (confirming) {
            ConfirmInline(
                question = stringResource(R.string.rutinas_editor_quitar_pregunta, item.name),
                onConfirm = {
                    confirming = false
                    actions.onRemove(item.id)
                },
                onCancel = { confirming = false },
                confirmLabel = stringResource(R.string.rutinas_editor_quitar),
            )
        }
    }
}

/** Fila del selector de ejercicio: nombre arriba, músculo principal abajo. */
@Composable
private fun ExerciseOptionRow(option: DiaExerciseOptionUi, onClick: () -> Unit) {
    val colors = GimTheme.colors
    val shape = RoundedCornerShape(GimRadius.control)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = GimHeight.row)
            .background(colors.surface2, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = GimSpacing.m, vertical = GimSpacing.s),
        verticalArrangement = Arrangement.spacedBy(GimSpacing.xs),
    ) {
        Text(text = option.name, style = GimText.cuerpo, color = colors.ink, maxLines = 2)
        Text(text = option.muscle, style = GimText.detalle, color = colors.muted, maxLines = 1)
    }
}

/** Toggle de texto (`Drop set`, superserie): mismo alto táctil que un chip. */
@Composable
private fun EditorToggle(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = GimTheme.colors
    val shape = RoundedCornerShape(GimRadius.chip)
    val content = when {
        !enabled -> colors.muted
        on -> colors.accent
        else -> colors.ink2
    }
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = GimHeight.chip)
            .background(if (on) colors.surface2 else Color.Transparent, shape)
            .border(GimStroke.border, if (on) colors.accent else colors.line, shape)
            .selectable(selected = on, enabled = enabled, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = GimSpacing.m, vertical = GimSpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = GimText.etiqueta, color = content, maxLines = 1)
    }
}

/** Errores de validación del borrador: el texto viene armado del ViewModel. */
@Composable
private fun ProblemBlock(problems: List<DiaEditorProblemUi>) {
    val colors = GimTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(GimSpacing.xs)) {
        Text(
            text = stringResource(R.string.rutinas_editor_error_titulo).uppercase(),
            style = GimText.etiqueta,
            color = colors.bad,
        )
        problems.forEach { problem ->
            Text(text = problem.text, style = GimText.detalle, color = colors.bad)
        }
    }
}

/** Acción de texto habilitable, mismo alto táctil que [ActionText]. */
@Composable
private fun EditorAction(label: String, enabled: Boolean, color: Color, onClick: () -> Unit) {
    val colors = GimTheme.colors
    Text(
        text = label,
        style = GimText.boton,
        color = if (enabled) color else colors.muted,
        maxLines = 1,
        modifier = Modifier
            .defaultMinSize(minHeight = GimHeight.row)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
}

/**
 * Tarjeta de un plan: nombre, totales y acciones.
 *
 * Tocar la tarjeta **selecciona** el plan (muestra sus días); "Hacer activo" cambia el
 * plan activo persistido, que es otra cosa. "Eliminar" no aparece cuando es el único y
 * se explica por qué (§1.1).
 */
@Composable
private fun PlanCard(
    plan: RutinaPlanUi,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onSetActive: () -> Unit,
    onExport: () -> Unit,
) {
    val colors = GimTheme.colors
    val shape = RoundedCornerShape(GimRadius.control)
    var editing by remember(plan.id) { mutableStateOf(false) }
    var confirmingDelete by remember(plan.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(GimStroke.border, if (plan.active) colors.accent else colors.line, shape)
            .clickable(role = Role.Button, onClick = onSelect)
            .padding(GimSpacing.m),
        verticalArrangement = Arrangement.spacedBy(GimSpacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plan.name,
                    style = GimText.subtitulo,
                    color = colors.ink,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(
                        R.string.rutinas_plan_meta,
                        pluralStringResource(
                            R.plurals.rutinas_dias_count,
                            plan.daysCount,
                            plan.daysCount,
                        ),
                        plan.setsLabel,
                        plan.durationLabel,
                    ),
                    style = GimText.detalle,
                    color = colors.muted,
                    maxLines = 2,
                )
            }
            if (plan.active) {
                Text(
                    text = stringResource(R.string.rutinas_activo),
                    style = GimText.etiqueta,
                    color = colors.accent,
                    maxLines = 1,
                )
            }
        }

        when {
            editing -> RenameField(
                initial = plan.name,
                onConfirm = { name ->
                    editing = false
                    onRename(name)
                },
                onCancel = { editing = false },
            )

            confirmingDelete -> ConfirmInline(
                question = stringResource(R.string.rutinas_eliminar_plan_pregunta, plan.name),
                onConfirm = {
                    confirmingDelete = false
                    onDelete()
                },
                onCancel = { confirmingDelete = false },
                confirmLabel = stringResource(R.string.rutinas_eliminar),
            )

            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(GimSpacing.m),
                ) {
                    if (!plan.active) {
                        ActionText(
                            label = stringResource(R.string.rutinas_hacer_activo),
                            color = colors.accent,
                            onClick = onSetActive,
                        )
                    }
                    ActionText(stringResource(R.string.rutinas_duplicar), colors.ink2, onDuplicate)
                    ActionText(
                        label = stringResource(R.string.rutinas_renombrar),
                        color = colors.ink2,
                        onClick = { editing = true },
                    )
                    ActionText(stringResource(R.string.rutinas_exportar), colors.ink2, onExport)
                    if (canDelete) {
                        ActionText(
                            label = stringResource(R.string.rutinas_eliminar),
                            color = colors.bad,
                            onClick = { confirmingDelete = true },
                        )
                    }
                }
                if (!canDelete) {
                    Text(
                        text = stringResource(R.string.rutinas_plan_unico),
                        style = GimText.detalle,
                        color = colors.muted,
                    )
                }
            }
        }
    }
}

/** Acciones del día elegido: editar, duplicar, renombrar y eliminar (con confirmación). */
@Composable
private fun DayActions(
    day: RutinaDayDetailUi,
    onAddExercise: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    val colors = GimTheme.colors
    var editing by remember(day.id) { mutableStateOf(false) }
    var confirmingDelete by remember(day.id) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(GimSpacing.xs)) {
        when {
            editing -> RenameField(
                initial = day.name,
                onConfirm = { name ->
                    editing = false
                    onRename(name)
                },
                onCancel = { editing = false },
            )

            confirmingDelete -> ConfirmInline(
                question = stringResource(R.string.rutinas_eliminar_dia_pregunta, day.name),
                onConfirm = {
                    confirmingDelete = false
                    onDelete()
                },
                onCancel = { confirmingDelete = false },
                confirmLabel = stringResource(R.string.rutinas_eliminar),
            )

            else -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(GimSpacing.m),
            ) {
                ActionText(stringResource(R.string.rutinas_editor_agregar), colors.accent, onAddExercise)
                ActionText(stringResource(R.string.rutinas_editar_dia), colors.ink2, onEdit)
                ActionText(stringResource(R.string.rutinas_duplicar_dia), colors.ink2, onDuplicate)
                ActionText(
                    label = stringResource(R.string.rutinas_renombrar_dia),
                    color = colors.ink2,
                    onClick = { editing = true },
                )
                ActionText(
                    label = stringResource(R.string.rutinas_eliminar_dia),
                    color = colors.bad,
                    onClick = { confirmingDelete = true },
                )
            }
        }
    }
}

/** Ítem del día: número, ejercicio, músculo, series/descanso y sufijos. */
@Composable
private fun DayItemRow(item: RutinaItemUi) {
    val colors = GimTheme.colors
    val shape = RoundedCornerShape(GimRadius.control)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(GimStroke.border, colors.line, shape)
            .padding(GimSpacing.m),
        horizontalArrangement = Arrangement.spacedBy(GimSpacing.m),
    ) {
        Text(
            text = item.position.toString(),
            style = GimText.etiqueta,
            color = colors.muted,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = GimText.cuerpo,
                color = colors.ink,
                maxLines = 2,
            )
            Text(
                text = stringResource(R.string.rutinas_item_meta, item.muscle, item.detail),
                style = GimText.detalle,
                color = colors.muted,
                maxLines = 2,
            )
            if (item.tags.isNotEmpty()) {
                Text(
                    text = item.tags.joinToString(" · "),
                    style = GimText.detalle,
                    color = colors.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Advertencias de `:core` (`supersetConflicts` / `heavyPairs`), con el texto ya armado. */
@Composable
private fun WarningBlock(warnings: List<String>) {
    val colors = GimTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(GimSpacing.xs)) {
        Text(
            text = stringResource(R.string.rutinas_advertencias).uppercase(),
            style = GimText.etiqueta,
            color = colors.warn,
        )
        warnings.forEach { warning ->
            Text(text = warning, style = GimText.detalle, color = colors.warn)
        }
    }
}

/**
 * Edición inline de un nombre (`docs/diseno.md` §5: la edición reemplaza lo que se
 * edita, no abre un diálogo). Campo `surface2` de 48 dp mínimo y "Guardar" / "Cancelar"
 * debajo.
 */
@Composable
private fun RenameField(
    initial: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = GimTheme.colors
    val shape = RoundedCornerShape(GimRadius.control)
    var text by remember(initial) { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(verticalArrangement = Arrangement.spacedBy(GimSpacing.xs)) {
        Text(
            text = stringResource(R.string.rutinas_nombre),
            style = GimText.etiqueta,
            color = colors.muted,
        )
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = GimText.cuerpo.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = GimHeight.row)
                .background(colors.surface2, shape)
                .border(GimStroke.border, colors.line, shape)
                .padding(horizontal = GimSpacing.m, vertical = GimSpacing.s)
                .focusRequester(focusRequester),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.m)) {
            ActionText(stringResource(R.string.accion_guardar), colors.accent) {
                onConfirm(text)
            }
            ActionText(
                label = stringResource(R.string.accion_cancelar),
                color = colors.muted,
                onClick = onCancel,
            )
        }
    }
}

/** Aviso del export/import de rutinas: resultado, error o "listo". */
@Composable
private fun TransferNotice(notice: RutinasTransferUi, onDismiss: () -> Unit) {
    if (notice is RutinasTransferUi.Confirm) return
    val colors = GimTheme.colors
    val error = notice is RutinasTransferUi.Error ||
        notice is RutinasTransferUi.ExportFailed ||
        notice is RutinasTransferUi.ReadFailed
    val text = when (notice) {
        is RutinasTransferUi.Imported -> {
            val base = stringResource(R.string.rutinas_importado, notice.routines, notice.days)
            if (notice.exercisesCreated > 0) {
                base + " " + stringResource(
                    R.string.rutinas_importado_ejercicios,
                    notice.exercisesCreated,
                )
            } else {
                base
            }
        }

        is RutinasTransferUi.Error ->
            stringResource(R.string.rutinas_import_error, notice.message)

        RutinasTransferUi.ExportFailed -> stringResource(R.string.rutinas_export_fallo)
        RutinasTransferUi.ReadFailed -> stringResource(R.string.rutinas_import_lectura)
        RutinasTransferUi.Exported -> stringResource(R.string.rutinas_exportado)
        is RutinasTransferUi.Confirm -> return
    }
    val shape = RoundedCornerShape(GimRadius.control)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface2, shape)
            .border(GimStroke.border, if (error) colors.bad else colors.line, shape)
            .padding(GimSpacing.m),
        verticalArrangement = Arrangement.spacedBy(GimSpacing.xs),
    ) {
        Text(
            text = text,
            style = GimText.detalle,
            color = if (error) colors.bad else colors.ink2,
        )
        ActionText(
            label = stringResource(R.string.rutinas_aviso_cerrar),
            color = colors.ink2,
            onClick = onDismiss,
        )
    }
}

/** Nombre visible del archivo elegido; el proveedor puede no exponerlo. */
private fun displayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "rutinas.json"

/** Acción de texto de 48 dp de alto (el mismo patrón que la acción de `SectionHeader`). */
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

/**
 * Comparte el JSON del plan con el selector del sistema. `ACTION_SEND` es el camino sin
 * permisos de storage: no hay archivo, el texto viaja en el extra.
 */
private fun sharePlan(context: Context, planName: String, json: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_SUBJECT, planName)
        putExtra(Intent.EXTRA_TEXT, json)
    }
    val chooser = Intent.createChooser(send, context.getString(R.string.rutinas_compartir))
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
