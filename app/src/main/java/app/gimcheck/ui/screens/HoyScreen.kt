package app.gimcheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gimcheck.R
import app.gimcheck.ui.components.PrimaryButton
import app.gimcheck.ui.components.SetStepper
import app.gimcheck.ui.components.StatTile
import app.gimcheck.ui.theme.GimSpacing

/**
 * **Hoy** — placeholder de §8 #1 (GIM-13). Lo rellena GIM-20.
 *
 * Ya trae la forma: dos `StatTile` (duración y series del día) y el `SetStepper` chico
 * del peso corporal con el paso de 0.5 kg, que es lo que verifica de verdad el widget
 * (háptico + coma decimal) en el teléfono. La acción primaria es "Empezar sesión" y lleva
 * a Entrenar.
 */
@Composable
fun HoyScreen(
    onStartSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var bodyWeight by remember { mutableStateOf(82.5) }
    PlaceholderScreen(
        title = stringResource(R.string.nav_hoy),
        hint = stringResource(R.string.ph_hoy),
        modifier = modifier,
        primary = {
            PrimaryButton(
                label = stringResource(R.string.accion_empezar_sesion),
                onClick = onStartSession,
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
            StatTile(
                k = stringResource(R.string.ph_duracion),
                v = stringResource(R.string.ph_sin_dato),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                k = stringResource(R.string.ph_series),
                v = stringResource(R.string.ph_sin_dato),
                modifier = Modifier.weight(1f),
            )
        }
        SetStepper(
            value = bodyWeight,
            step = 0.5,
            onValue = { bodyWeight = it },
            label = stringResource(R.string.ph_peso_corporal),
        )
    }
}
