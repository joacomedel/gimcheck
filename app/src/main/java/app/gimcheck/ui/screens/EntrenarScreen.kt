package app.gimcheck.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gimcheck.R
import app.gimcheck.ui.components.ConfirmInline
import app.gimcheck.ui.components.Meter
import app.gimcheck.ui.components.PrimaryButton
import app.gimcheck.ui.components.SetStepper
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * **Entrenar** — placeholder de §8 #2 (GIM-13). Lo rellena GIM-21.
 *
 * Es la única pantalla placeholder con una regla de verdad implementada, porque es el
 * criterio de este ticket: **pide confirmación al salir** (§7.7, "Entrenar es modal y
 * pide confirmación al salir"). El botón "Salir" y el back del sistema abren el
 * `ConfirmInline` en el lugar del botón; el segundo back cancela la confirmación en vez
 * de salir.
 *
 * La barra inferior no se dibuja acá (la oculta el `Scaffold` de la raíz).
 */
@Composable
fun EntrenarScreen(
    onExit: () -> Unit,
    onSetDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var weight by remember { mutableStateOf(82.5) }
    var reps by remember { mutableStateOf(10.0) }
    var confirmingExit by remember { mutableStateOf(false) }
    val colors = GimTheme.colors

    BackHandler {
        if (confirmingExit) confirmingExit = false else confirmingExit = true
    }

    PlaceholderScreen(
        title = stringResource(R.string.nav_entrenar),
        hint = stringResource(R.string.ph_entrenar),
        modifier = modifier,
        primary = {
            Column(verticalArrangement = Arrangement.spacedBy(GimSpacing.s)) {
                if (confirmingExit) {
                    ConfirmInline(
                        question = stringResource(R.string.ph_confirmar_salida),
                        onConfirm = onExit,
                        onCancel = { confirmingExit = false },
                        confirmLabel = stringResource(R.string.accion_salir),
                    )
                } else {
                    TextButton(onClick = { confirmingExit = true }) {
                        Text(text = stringResource(R.string.accion_salir), style = GimText.boton)
                    }
                }
                PrimaryButton(
                    label = stringResource(R.string.accion_serie_hecha),
                    onClick = onSetDone,
                )
            }
        },
    ) {
        Meter(
            value = 7f,
            max = 12f,
            bandMin = 8f,
            bandMax = 12f,
            color = colors.accent,
        )
        SetStepper(
            value = weight,
            step = 2.5,
            onValue = { weight = it },
            label = stringResource(R.string.ph_peso_serie),
            large = true,
        )
        SetStepper(
            value = reps,
            step = 1.0,
            onValue = { reps = it },
            label = stringResource(R.string.ph_reps),
            large = true,
        )
    }
}
