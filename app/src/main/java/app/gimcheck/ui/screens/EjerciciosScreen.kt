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
 * **Ejercicios** — placeholder de §8 #5 (GIM-13). Lo rellena GIM-17.
 *
 * Se abre desde Rutinas (acción del `SectionHeader`). El filtro por músculo es una fila
 * de `ChipFilter`; el estado vacío es el del contrato para "no hay ejercicios con ese
 * músculo".
 */
@Composable
fun EjerciciosScreen(
    onClearFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(
        title = stringResource(R.string.nav_ejercicios),
        hint = stringResource(R.string.ph_ejercicios),
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
            ChipFilter(label = stringResource(R.string.ph_musculo_pecho), selected = true, onClick = {})
            ChipFilter(label = stringResource(R.string.ph_musculo_espalda), selected = false, onClick = {})
            ChipFilter(label = stringResource(R.string.ph_musculo_piernas), selected = false, onClick = {})
        }
        EmptyState(
            text = stringResource(R.string.empty_ejercicios_text),
            actionLabel = stringResource(R.string.empty_ejercicios_action),
            onAction = onClearFilter,
        )
    }
}
