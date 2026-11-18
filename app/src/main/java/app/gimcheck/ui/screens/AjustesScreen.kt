package app.gimcheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gimcheck.R
import app.gimcheck.rest.RestAlertDiagnostics
import app.gimcheck.ui.components.AlertDiagnosisBlock
import app.gimcheck.ui.components.ChipFilter
import app.gimcheck.ui.components.SectionHeader
import app.gimcheck.ui.components.SetStepper
import app.gimcheck.ui.theme.GimSpacing

/**
 * **Ajustes** — placeholder de §8 #9 (GIM-13). Lo rellena GIM-15.
 *
 * Las secciones del contrato (Entrenamiento · Alertas · Datos · Apariencia · Acerca de)
 * como `SectionHeader`; los ajustes reales —permisos, backup, versión— llegan con el
 * ticket de ajustes. El `SetStepper` de descanso y los chips de tema ya usan los
 * componentes definitivos.
 *
 * **Alertas** ya muestra una línea con el diagnóstico real del sistema (GIM-43): es
 * barata y evita tener que abrir la pantalla de descanso terminado para saber si un
 * permiso está negado o el canal quedó degradado.
 */
@Composable
fun AjustesScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val diagnosis = remember(context) { RestAlertDiagnostics.collect(context) }
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
        SectionHeader(title = stringResource(R.string.ph_seccion_apariencia))
        Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
            ChipFilter(label = stringResource(R.string.ph_tema_sistema), selected = true, onClick = {})
            ChipFilter(label = stringResource(R.string.ph_tema_claro), selected = false, onClick = {})
            ChipFilter(label = stringResource(R.string.ph_tema_oscuro), selected = false, onClick = {})
        }
    }
}
