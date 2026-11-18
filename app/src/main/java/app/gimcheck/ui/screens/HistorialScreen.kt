package app.gimcheck.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gimcheck.R
import app.gimcheck.ui.components.EmptyState

/**
 * **Historial** — placeholder de §8 #7 (GIM-13). Lo rellena GIM-19.
 *
 * Agrupado por semana ISO y con el detalle en hoja: llega con el ticket de historial.
 */
@Composable
fun HistorialScreen(
    onStartSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(
        title = stringResource(R.string.nav_historial),
        hint = stringResource(R.string.ph_historial),
        modifier = modifier,
    ) {
        EmptyState(
            text = stringResource(R.string.empty_historial_text),
            actionLabel = stringResource(R.string.empty_historial_action),
            onAction = onStartSession,
        )
    }
}
