package app.gimcheck.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import app.gimcheck.data.transfer.WebImportOutcome
import app.gimcheck.rest.RestAlertCenter
import app.gimcheck.rest.RestAlertDiagnostics
import app.gimcheck.rest.RestPermissions
import app.gimcheck.ui.components.AlertDiagnosisBlock
import app.gimcheck.ui.components.ChipFilter
import app.gimcheck.ui.components.PrimaryButton
import app.gimcheck.ui.components.SectionHeader
import app.gimcheck.ui.components.SetStepper
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Ajustes** — placeholder de §8 #9 (GIM-13). Lo rellena GIM-29.
 *
 * Ya hay dos secciones funcionales, porque son las que se verifican en el teléfono:
 *
 * - **Alertas** (GIM-43/44): diagnóstico real, permiso de overlay y prueba de 10 s.
 * - **Datos** (GIM-45): importar el backup de la web (las dos rutinas del dueño) con
 *   selector de archivo, confirmación y resultado. Reemplaza rutinas y catálogo en una
 *   transacción y **conserva el historial**.
 *
 * El resto (tiempos, rango, tema, versión) sigue como placeholder hasta GIM-29.
 */
@Composable
fun AjustesScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    var refresh by remember { mutableStateOf(0) }
    val diagnosis = remember(context, refresh) { RestAlertDiagnostics.collect(context) }
    var selfTestNote by remember { mutableStateOf<Int?>(null) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var importOutcome by remember { mutableStateOf<WebImportOutcome?>(null) }
    var readError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
        SetStepper(
            value = 90.0,
            step = 15.0,
            onValue = {},
            label = stringResource(R.string.ph_descanso_series),
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
        SectionHeader(title = stringResource(R.string.ph_seccion_datos))
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
        SectionHeader(title = stringResource(R.string.ph_seccion_apariencia))
        Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
            ChipFilter(label = stringResource(R.string.ph_tema_sistema), selected = true, onClick = {})
            ChipFilter(label = stringResource(R.string.ph_tema_claro), selected = false, onClick = {})
            ChipFilter(label = stringResource(R.string.ph_tema_oscuro), selected = false, onClick = {})
        }
    }
}

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
