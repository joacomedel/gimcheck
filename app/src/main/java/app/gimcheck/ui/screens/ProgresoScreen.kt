package app.gimcheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gimcheck.R
import app.gimcheck.ui.components.ChipFilter
import app.gimcheck.ui.components.EmptyState
import app.gimcheck.ui.components.Meter
import app.gimcheck.ui.components.SectionHeader
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimTheme

/**
 * **Progreso** — placeholder de §8 #6 (GIM-13). Lo rellena GIM-18.
 *
 * Los rangos 3 M / 6 M / 1 A / Todo son chips de texto (§6) y la acción "Comparar" del
 * `SectionHeader` es la puerta a Comparar. El `Meter` queda como muestra del reuso que
 * pide §6 para "series por músculo".
 */
@Composable
fun ProgresoScreen(
    onGoHoy: () -> Unit,
    onOpenCompare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GimTheme.colors
    PlaceholderScreen(
        title = stringResource(R.string.nav_progreso),
        hint = stringResource(R.string.ph_progreso),
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
            ChipFilter(label = stringResource(R.string.ph_rango_3m), selected = false, onClick = {})
            ChipFilter(label = stringResource(R.string.ph_rango_6m), selected = true, onClick = {})
            ChipFilter(label = stringResource(R.string.ph_rango_1a), selected = false, onClick = {})
        }
        SectionHeader(
            title = stringResource(R.string.ph_series_por_musculo),
            action = stringResource(R.string.nav_comparar),
            onAction = onOpenCompare,
        )
        Meter(value = 7f, max = 18f, color = colors.accent)
        EmptyState(
            text = stringResource(R.string.empty_progreso_text),
            actionLabel = stringResource(R.string.empty_progreso_action),
            onAction = onGoHoy,
        )
    }
}
