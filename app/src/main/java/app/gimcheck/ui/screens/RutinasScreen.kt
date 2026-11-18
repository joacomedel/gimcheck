package app.gimcheck.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import app.gimcheck.ui.components.SectionHeader
import app.gimcheck.ui.components.StatTile
import app.gimcheck.ui.theme.GimHeight
import app.gimcheck.ui.theme.GimRadius
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimStroke
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * **Rutinas** — §6 fila 4 y `docs/diseno.md` §8 #4 (GIM-16).
 *
 * De arriba abajo: título con la puerta a Ejercicios · `SectionHeader` "Planes" con
 * "Nuevo plan" · una tarjeta por plan (nombre, activo marcado, días/series/duración y
 * las acciones crear, duplicar, renombrar, exportar, eliminar) · `SectionHeader` "Días"
 * con "Agregar día" · chips de día · `StatTile` de duración y series del día · ítems en
 * orden con drop set y superserie · advertencias en `warn` · acciones del día.
 *
 * **El detalle del día es solo lectura**: el editor es GIM-17. La pantalla no calcula
 * nada; todo (incluidos los textos de advertencia) viene resuelto de
 * [RutinasViewModel]. Los colores salen de [GimTheme], nunca literales.
 *
 * **Exportar** arma el JSON de la web en el ViewModel (`exportPlan` de `:core`) y acá
 * sólo se comparte con `Intent.ACTION_SEND`: no hay permiso de storage ni archivo
 * intermedio.
 */
@Composable
fun RutinasScreen(
    onAddExercise: () -> Unit,
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

    RutinasContent(
        state = state,
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
        onAddExercise = onAddExercise,
        onOpenExercises = onOpenExercises,
        modifier = modifier,
    )
}

@Composable
private fun RutinasContent(
    state: RutinasUiState,
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
    modifier: Modifier = Modifier,
) {
    val colors = GimTheme.colors
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
                onDuplicate = { onDuplicateDay(day.id) },
                onRename = { name -> onRenameDay(day.id, name) },
                onDelete = { onDeleteDay(day.id) },
            )
        }
    }
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

/** Acciones del día elegido: duplicar, renombrar y eliminar (con confirmación). */
@Composable
private fun DayActions(
    day: RutinaDayDetailUi,
    onDuplicate: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
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
