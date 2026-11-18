package app.gimcheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gimcheck.R
import app.gimcheck.ui.components.ChipFilter
import app.gimcheck.ui.components.EmptyState
import app.gimcheck.ui.theme.GimSpacing

/**
 * **Comparar** — placeholder de §8 #8 (GIM-13). Lo rellena GIM-19.
 *
 * Se abre desde Progreso. Los dos selectores de plan son chips; la tabla de dos columnas
 * la decide el ticket de comparación.
 */
@Composable
fun CompararScreen(
    onCreateRoutine: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(
        title = stringResource(R.string.nav_comparar),
        hint = stringResource(R.string.ph_comparar),
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
            ChipFilter(label = stringResource(R.string.ph_plan_full_body), selected = true, onClick = {})
            ChipFilter(label = stringResource(R.string.ph_plan_torso_pierna), selected = false, onClick = {})
        }
        EmptyState(
            text = stringResource(R.string.empty_comparar_text),
            actionLabel = stringResource(R.string.empty_comparar_action),
            onAction = onCreateRoutine,
        )
    }
}
