package app.gimcheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.gimcheck.R
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

/**
 * **Ajustes** — placeholder de §8 #9 (GIM-13). Lo rellena GIM-29.
 *
 * Las secciones del contrato (Entrenamiento · Alertas · Datos · Apariencia · Acerca de)
 * como `SectionHeader`; los ajustes reales —permisos, backup, versión— llegan con el
 * ticket de ajustes. El `SetStepper` de descanso y los chips de tema ya usan los
 * componentes definitivos.
 *
 * **Alertas** ya es funcional porque es donde se verifica el aviso de fin de descanso
 * (GIM-43/44): muestra el diagnóstico real del sistema, permite pedir "aparecer encima
 * de otras apps" y programa el aviso real en 10 segundos. El diagnóstico se relee al
 * volver a la pantalla (`ON_RESUME`), porque los permisos se conceden en Ajustes del
 * sistema y no queremos mostrar un valor viejo.
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

    DisposableEffect(lifecycle) {
        if (lifecycle == null) return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh += 1
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    PlaceholderScreen(
        title = stringResource(R.string.nav_ajustes),
        hint = stringResource(R.string.ph_ajustes),
        modifier = modifier,
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
        SectionHeader(title = stringResource(R.string.ph_seccion_apariencia))
        Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
            ChipFilter(label = stringResource(R.string.ph_tema_sistema), selected = true, onClick = {})
            ChipFilter(label = stringResource(R.string.ph_tema_claro), selected = false, onClick = {})
            ChipFilter(label = stringResource(R.string.ph_tema_oscuro), selected = false, onClick = {})
        }
    }
}
