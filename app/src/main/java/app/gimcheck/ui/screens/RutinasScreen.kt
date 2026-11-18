package app.gimcheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gimcheck.R
import app.gimcheck.ui.components.ChipFilter
import app.gimcheck.ui.components.EmptyState
import app.gimcheck.ui.components.SectionHeader
import app.gimcheck.ui.theme.GimSpacing

/**
 * **Rutinas** — placeholder de §8 #4 (GIM-13). Lo rellena GIM-16.
 *
 * Trae el chip de plan y el `SectionHeader` con la acción "Ejercicios" (la puerta a la
 * novena pantalla del grafo) y el `EmptyState` con el copy exacto de §9 para el día
 * vacío, que es el estado en el que arranca la app con el seed.
 */
@Composable
fun RutinasScreen(
    onAddExercise: () -> Unit,
    onOpenExercises: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(
        title = stringResource(R.string.nav_rutinas),
        hint = stringResource(R.string.ph_rutinas),
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
            ChipFilter(
                label = stringResource(R.string.ph_plan_full_body),
                selected = true,
                onClick = {},
            )
        }
        SectionHeader(
            title = stringResource(R.string.ph_dia_a),
            action = stringResource(R.string.nav_ejercicios),
            onAction = onOpenExercises,
        )
        EmptyState(
            text = stringResource(R.string.empty_rutinas_text),
            actionLabel = stringResource(R.string.empty_rutinas_action),
            onAction = onAddExercise,
        )
    }
}
