package app.gimcheck.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.gimcheck.GimCheckApp
import app.gimcheck.R
import app.gimcheck.core.E1rmFormula
import app.gimcheck.data.settings.AlertMode
import app.gimcheck.data.settings.RestSound
import app.gimcheck.data.settings.RestVibration
import app.gimcheck.data.settings.ThemeMode
import app.gimcheck.data.settings.sounds
import app.gimcheck.data.settings.vibrates
import app.gimcheck.data.settings.showsScreen
import app.gimcheck.data.transfer.WebImportOutcome
import app.gimcheck.rest.RestAlertCenter
import app.gimcheck.rest.RestAlertDiagnostics
import app.gimcheck.rest.RestPermissions
import app.gimcheck.ui.LocalAppContainer
import app.gimcheck.ui.components.AlertDiagnosisBlock
import app.gimcheck.ui.components.ChipFilter
import app.gimcheck.ui.components.ConfirmInline
import app.gimcheck.ui.components.PrimaryButton
import app.gimcheck.ui.components.SectionHeader
import app.gimcheck.ui.components.SetStepper
import app.gimcheck.ui.components.ToggleRow
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Ajustes** — §6 fila 9 y `docs/diseno.md` §8 #9 (GIM-29).
 *
 * Cinco secciones, todas con datos reales:
 * - **Entrenamiento**: calentamiento, traslado, drop set y rango semanal (los consume
 *   `:core` al calcular duración y volumen).
 * - **Cálculo**: fórmula de e1RM (repinta Progreso al instante), relleno de la última
 *   serie, peso corporal en el volumen y pantalla encendida.
 * - **Alertas**: diagnóstico, permiso de overlay, prueba de 10 s y las tres capas del
 *   aviso (pantalla, vibración, sonido) más la repetición.
 * - **Apariencia**: tema sistema/claro/oscuro, aplicado por `GimCheckTheme`.
 * - **Datos**: exportar un backup completo y seguir importando el de la web.
 *
 * Los steppers escriben en `app_setting` al toque (sin "Guardar"): son preferencias
 * escalares y el repositorio emite el cambio al instante. "Volver a los ajustes por
 * defecto" pide confirmación en el lugar y no toca rutinas ni historial.
 */
@Composable
fun AjustesScreen(
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    if (container == null) {
        PlaceholderScreen(
            title = stringResource(R.string.nav_ajustes),
            hint = stringResource(R.string.ph_ajustes),
            modifier = modifier,
        )
        return
    }

    val context = LocalContext.current
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    val scope = rememberCoroutineScope()
    val viewModel = remember(container) { AjustesViewModel(container, scope) }
    LaunchedEffect(viewModel) { viewModel.start() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stop() }
    }
    val state by viewModel.state.collectAsState()
    val settings = state.settings

    var refresh by remember { mutableStateOf(0) }
    val diagnosis = remember(context, refresh) { RestAlertDiagnostics.collect(context) }
    var selfTestNote by remember { mutableStateOf<Int?>(null) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var importOutcome by remember { mutableStateOf<WebImportOutcome?>(null) }
    var readError by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<BackupExportResult.Ready?>(null) }
    var resetting by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle) {
        if (lifecycle == null) return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh += 1
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val pickBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    null
                }
            }
            if (text.isNullOrBlank()) {
                readError = true
            } else {
                readError = false
                pendingImport = PendingImport(
                    name = uri.lastPathSegment?.substringAfterLast('/') ?: "backup.json",
                    text = text,
                )
            }
        }
    }

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val ready = pendingExport
        pendingExport = null
        if (uri == null || ready == null) {
            viewModel.exportCancelled()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                        writer.write(ready.json)
                    } != null
                }.getOrDefault(false)
            }
            if (written) viewModel.exportWritten(ready.name) else viewModel.exportCancelled()
        }
    }

    LaunchedEffect(state.export) {
        when (val export = state.export) {
            is BackupExportResult.Ready -> {
                pendingExport = export
                createBackup.launch(export.name)
            }
            BackupExportResult.Failed, null -> Unit
        }
    }

    pendingImport?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.datos_importar_titulo)) },
            text = { Text(stringResource(R.string.datos_importar_texto, pending.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImport = null
                        importOutcome = null
                        scope.launch {
                            importOutcome = importBackup(context, pending.text)
                        }
                    },
                ) {
                    Text(stringResource(R.string.datos_importar_confirmar))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) {
                    Text(stringResource(R.string.datos_cancelar))
                }
            },
        )
    }

    PlaceholderScreen(
        title = stringResource(R.string.nav_ajustes),
        hint = stringResource(R.string.ph_ajustes),
        modifier = modifier,
        scrollable = true,
    ) {
        SectionHeader(title = stringResource(R.string.ph_seccion_entrenamiento))
        SettingStepper(
            label = stringResource(R.string.ajustes_calentamiento),
            value = settings.warm.toDouble(),
            step = 1.0,
            wholeNumbers = true,
            onValue = { viewModel.setWarm(it.toInt()) },
        )
        SettingStepper(
            label = stringResource(R.string.ajustes_traslado),
            value = settings.trans.toDouble(),
            step = 15.0,
            wholeNumbers = true,
            onValue = { viewModel.setTrans(it.toInt()) },
        )
        SettingStepper(
            label = stringResource(R.string.ajustes_drops),
            value = settings.drops.toDouble(),
            step = 1.0,
            wholeNumbers = true,
            onValue = { viewModel.setDrops(it.toInt()) },
        )
        SettingStepper(
            label = stringResource(R.string.ajustes_drop_sec),
            value = settings.dropSec.toDouble(),
            step = 5.0,
            wholeNumbers = true,
            onValue = { viewModel.setDropSec(it.toInt()) },
        )
        SettingStepper(
            label = stringResource(R.string.ajustes_rango_min),
            value = settings.optMin.toDouble(),
            step = 1.0,
            wholeNumbers = true,
            onValue = { viewModel.setOptMin(it.toInt()) },
        )
        SettingStepper(
            label = stringResource(R.string.ajustes_rango_max),
            value = settings.optMax.toDouble(),
            step = 1.0,
            wholeNumbers = true,
            onValue = { viewModel.setOptMax(it.toInt()) },
        )
        SettingStepper(
            label = stringResource(R.string.ajustes_paso),
            value = settings.weightStep,
            step = 0.5,
            onValue = { viewModel.setWeightStep(it) },
        )

        SectionHeader(title = stringResource(R.string.ajustes_calculo))
        Text(
            text = stringResource(R.string.ajustes_formula),
            style = GimText.etiqueta,
            color = GimTheme.colors.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
            ChipFilter(
                label = "Epley",
                selected = settings.e1rmFormula == E1rmFormula.EPLEY,
                onClick = { viewModel.setFormula(E1rmFormula.EPLEY) },
            )
            ChipFilter(
                label = "Brzycki",
                selected = settings.e1rmFormula == E1rmFormula.BRZYCKI,
                onClick = { viewModel.setFormula(E1rmFormula.BRZYCKI) },
            )
        }
        ToggleRow(
            label = stringResource(R.string.ajustes_autofill),
            checked = settings.autoFillLast,
            onChange = viewModel::setAutoFillLast,
        )
        ToggleRow(
            label = stringResource(R.string.ajustes_bodyweight),
            checked = settings.bodyweightVolume,
            onChange = viewModel::setBodyweightVolume,
        )
        ToggleRow(
            label = stringResource(R.string.ajustes_keep_screen),
            checked = settings.keepScreenOn,
            onChange = viewModel::setKeepScreenOn,
        )

        SectionHeader(title = stringResource(R.string.ph_seccion_alertas))
        AlertDiagnosisBlock(diagnosis = diagnosis, compact = true)
        if (!diagnosis.overlay) {
            TextButton(onClick = { RestPermissions.requestOverlay(context) }) {
                Text(stringResource(R.string.rest_permiso_overlay_boton))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(GimSpacing.xs)) {
            PrimaryButton(
                label = stringResource(R.string.rest_probar_boton),
                onClick = {
                    selfTestNote = if (RestAlertCenter.scheduleSelfTest(context)) {
                        R.string.rest_probar_programado
                    } else {
                        R.string.rest_probar_fallo
                    }
                },
            )
            Text(
                text = stringResource(selfTestNote ?: R.string.rest_probar_nota),
                style = GimText.detalle,
                color = GimTheme.colors.muted,
            )
        }
        Text(
            text = stringResource(R.string.ajustes_aviso_como),
            style = GimText.etiqueta,
            color = GimTheme.colors.muted,
        )
        ToggleRow(
            label = stringResource(R.string.ajustes_aviso_pantalla),
            checked = settings.alertMode.showsScreen,
            onChange = viewModel::toggleAlertScreen,
        )
        ToggleRow(
            label = stringResource(R.string.ajustes_aviso_vibracion),
            checked = settings.alertMode.vibrates,
            onChange = viewModel::toggleAlertVibration,
        )
        ToggleRow(
            label = stringResource(R.string.ajustes_aviso_sonido),
            checked = settings.alertMode.sounds,
            onChange = viewModel::toggleAlertSound,
        )
        if (settings.alertMode == AlertMode.NONE) {
            Text(
                text = stringResource(R.string.ajustes_aviso_nada),
                style = GimText.detalle,
                color = GimTheme.colors.warn,
            )
        }
        if (settings.alertMode.sounds) {
            Text(
                text = stringResource(R.string.ajustes_sonido),
                style = GimText.etiqueta,
                color = GimTheme.colors.muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
                RestSound.entries.forEach { sound ->
                    ChipFilter(
                        label = soundLabel(sound),
                        selected = settings.restSound == sound,
                        onClick = { viewModel.setRestSound(sound) },
                    )
                }
            }
        }
        if (settings.alertMode.vibrates) {
            Text(
                text = stringResource(R.string.ajustes_vibracion),
                style = GimText.etiqueta,
                color = GimTheme.colors.muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
                RestVibration.entries.forEach { vibration ->
                    ChipFilter(
                        label = vibrationLabel(vibration),
                        selected = settings.restVibration == vibration,
                        onClick = { viewModel.setRestVibration(vibration) },
                    )
                }
            }
        }
        SettingStepper(
            label = stringResource(R.string.ajustes_repetir),
            value = settings.restAlertRepeat.toDouble(),
            step = 1.0,
            wholeNumbers = true,
            onValue = { viewModel.setRestAlertRepeat(it.toInt()) },
        )

        SectionHeader(title = stringResource(R.string.ph_seccion_apariencia))
        Text(
            text = stringResource(R.string.ajustes_tema),
            style = GimText.etiqueta,
            color = GimTheme.colors.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
            ChipFilter(
                label = stringResource(R.string.ph_tema_sistema),
                selected = settings.theme == ThemeMode.SYSTEM,
                onClick = { viewModel.setTheme(ThemeMode.SYSTEM) },
            )
            ChipFilter(
                label = stringResource(R.string.ph_tema_claro),
                selected = settings.theme == ThemeMode.LIGHT,
                onClick = { viewModel.setTheme(ThemeMode.LIGHT) },
            )
            ChipFilter(
                label = stringResource(R.string.ph_tema_oscuro),
                selected = settings.theme == ThemeMode.DARK,
                onClick = { viewModel.setTheme(ThemeMode.DARK) },
            )
        }

        SectionHeader(title = stringResource(R.string.ph_seccion_datos))
        PrimaryButton(
            label = stringResource(R.string.ajustes_exportar),
            onClick = { viewModel.buildBackup() },
        )
        Text(
            text = stringResource(R.string.ajustes_exportar_nota),
            style = GimText.detalle,
            color = GimTheme.colors.muted,
        )
        when (val export = state.export) {
            is BackupExportResult.Ready -> Text(
                text = stringResource(R.string.ajustes_export_listo, export.name),
                style = GimText.detalle,
                color = GimTheme.colors.ink2,
            )
            BackupExportResult.Failed -> Text(
                text = stringResource(R.string.ajustes_export_error),
                style = GimText.detalle,
                color = GimTheme.colors.bad,
            )
            null -> Unit
        }
        state.exportedName?.let { name ->
            Text(
                text = stringResource(R.string.ajustes_export_guardado, name),
                style = GimText.detalle,
                color = GimTheme.colors.good,
            )
        }
        PrimaryButton(
            label = stringResource(R.string.datos_importar_boton),
            onClick = { pickBackup.launch(arrayOf("*/*")) },
        )
        Text(
            text = stringResource(R.string.datos_importar_nota),
            style = GimText.detalle,
            color = GimTheme.colors.muted,
        )
        if (readError) {
            Text(
                text = stringResource(R.string.datos_import_error_lectura),
                style = GimText.detalle,
                color = GimTheme.colors.bad,
            )
        }
        importOutcome?.let { outcome ->
            Text(
                text = importMessage(outcome),
                style = GimText.detalle,
                color = GimTheme.colors.ink2,
            )
        }

        SectionHeader(title = stringResource(R.string.ajustes_acerca))
        Text(
            text = stringResource(R.string.ajustes_version, appVersion(context)),
            style = GimText.detalle,
            color = GimTheme.colors.muted,
        )
        if (resetting) {
            ConfirmInline(
                question = stringResource(R.string.ajustes_reset_pregunta),
                onConfirm = {
                    resetting = false
                    viewModel.resetSettings()
                },
                onCancel = { resetting = false },
                confirmLabel = stringResource(R.string.ajustes_reset_confirmar),
            )
        } else {
            TextButton(onClick = { resetting = true }) {
                Text(
                    text = stringResource(R.string.ajustes_reset),
                    color = GimTheme.colors.bad,
                )
            }
        }
    }
}

/** Stepper de un ajuste escalar: el valor se puede escribir tocando el número. */
@Composable
private fun SettingStepper(
    label: String,
    value: Double,
    step: Double,
    onValue: (Double) -> Unit,
    wholeNumbers: Boolean = false,
) {
    SetStepper(
        value = value,
        step = step,
        onValue = onValue,
        label = label,
        wholeNumbers = wholeNumbers,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun soundLabel(sound: RestSound): String = stringResource(
    when (sound) {
        RestSound.DEFAULT -> R.string.ajustes_sonido_default
        RestSound.CHIME -> R.string.ajustes_sonido_chime
        RestSound.BEEP -> R.string.ajustes_sonido_beep
    },
)

@Composable
private fun vibrationLabel(vibration: RestVibration): String = stringResource(
    when (vibration) {
        RestVibration.SHORT -> R.string.ajustes_vibracion_corta
        RestVibration.LONG -> R.string.ajustes_vibracion_larga
        RestVibration.DOUBLE -> R.string.ajustes_vibracion_doble
    },
)

/** `versionName` del manifiesto; `—` si el paquete no se puede leer (no debería). */
private fun appVersion(context: android.content.Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "—"

/** Backup elegido y todavía sin confirmar. */
private data class PendingImport(val name: String, val text: String)

private suspend fun importBackup(
    context: android.content.Context,
    text: String,
): WebImportOutcome {
    val app = context.applicationContext as? Application
        ?: return WebImportOutcome.Failed("sin contenedor de la app")
    return GimCheckApp.container(app).webBackup.import(text)
}

/**
 * Mensaje del import con `stringResource`: la cadena se resuelve en composición (y no
 * con `LocalContext.getString`, que Compose marca como no configuration-aware).
 */
@Composable
private fun importMessage(outcome: WebImportOutcome): String = when (outcome) {
    is WebImportOutcome.Imported -> stringResource(
        R.string.datos_importado,
        outcome.routines,
        outcome.days,
        outcome.exercises,
    )

    is WebImportOutcome.Invalid -> stringResource(R.string.datos_import_invalido, outcome.message)
    is WebImportOutcome.Failed -> stringResource(R.string.datos_import_fallo, outcome.message)
}
