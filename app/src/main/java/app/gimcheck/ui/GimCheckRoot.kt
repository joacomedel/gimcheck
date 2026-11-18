package app.gimcheck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gimcheck.R
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * Raíz del árbol de Compose: el `Scaffold` único de la app.
 *
 * Qué resuelve acá y no en cada pantalla:
 * - **Edge-to-edge**: `contentWindowInsets = WindowInsets.safeDrawing` (§10). El padding
 *   del sistema llega por el `PaddingValues` del `Scaffold`, así que la barra inferior de
 *   GIM-13 y el contenido respetan el gesto, el notch y el teclado sin repetir
 *   `statusBarsPadding()` en nueve pantallas.
 * - **Superficie base**: `containerColor = bg` y `contentColor = ink` sobre `Surface`
 *   plana, sin elevación. Todo lo que se dibuje adentro hereda el color correcto aunque
 *   nadie lo pase.
 *
 * El contenido es un cartel de arranque: las nueve pantallas y la barra inferior llegan
 * en GIM-13, que reemplaza el bloque de [ArranquePendiente] por el `NavHost`.
 */
@Composable
fun GimCheckRoot(modifier: Modifier = Modifier) {
    val colors = GimTheme.colors
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.bg,
        contentColor = colors.ink,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(GimSpacing.l),
            contentAlignment = Alignment.Center,
        ) {
            ArranquePendiente()
        }
    }
}

/**
 * Cartel temporal del esqueleto. Existe para que el APK de este ticket muestre algo con
 * las dos tipografías ya cargadas; no es una de las nueve pantallas de §8.
 */
@Composable
private fun ArranquePendiente() {
    val colors = GimTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GimSpacing.xs),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = GimText.titulo,
            color = colors.ink,
        )
        Text(
            text = stringResource(R.string.ui_arranque_pendiente),
            style = GimText.detalle,
            color = colors.muted,
        )
    }
}
