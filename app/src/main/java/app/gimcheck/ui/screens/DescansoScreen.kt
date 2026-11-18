package app.gimcheck.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gimcheck.R
import app.gimcheck.ui.components.PrimaryButton
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * **Descanso terminado** — placeholder de §8 #3 (GIM-13). Lo rellena GIM-21.
 *
 * Fuera de la barra inferior y con el título en `display` (40 sp Barlow), como pide la
 * tabla: "Descansá hasta acá" es el único texto de la app que va en ese tamaño además de
 * la cuenta regresiva. El `TimerRing` y el auto-cierre llegan con el ticket de descanso.
 */
@Composable
fun DescansoScreen(
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GimTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(GimSpacing.l),
    ) {
        Text(
            text = stringResource(R.string.ph_descanso_titulo),
            style = GimText.display,
            color = colors.ink,
        )
        Text(
            text = stringResource(R.string.ph_descanso),
            style = GimText.detalle,
            color = colors.muted,
            modifier = Modifier.padding(top = GimSpacing.s),
        )
        PrimaryButton(
            label = stringResource(R.string.accion_empezar),
            onClick = onStart,
            modifier = Modifier.padding(top = GimSpacing.xxl),
        )
    }
}
