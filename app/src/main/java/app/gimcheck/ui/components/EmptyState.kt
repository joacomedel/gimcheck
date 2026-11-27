package app.gimcheck.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import app.gimcheck.data.settings.ThemeMode
import app.gimcheck.ui.theme.GimCheckTheme
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * `EmptyState` — `docs/diseno.md` §5 y §9: **centrado, sin ilustración, 1 frase + 1
 * botón**. La frase va en `cuerpo` `ink2` y la acción es el botón primario (52 dp, §7.3),
 * no un link: una pantalla vacía tiene exactamente una salida.
 *
 * El copy **no se escribe acá**: cada pantalla pasa el texto y el label de la tabla de §9
 * ("Todavía no hay rutinas." / "Crear rutina"). `EmptyStateCopyTest` verifica que los
 * seis pares de `strings.xml` sean los del contrato, carácter por carácter.
 *
 * Sin `fillMaxHeight`: el contenedor decide si el vacío ocupa la pantalla (lista vacía en
 * `Box(contentAlignment = Center)`) o una sección (debajo de un `SectionHeader`).
 */
@Composable
fun EmptyState(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GimTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(GimSpacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GimSpacing.l),
    ) {
        Text(
            text = text,
            style = GimText.cuerpo,
            color = colors.ink2,
            textAlign = TextAlign.Center,
        )
        PrimaryButton(label = actionLabel, onClick = onAction)
    }
}

/** Documentación viva: no se renderiza acá (§11). */
@Preview(showBackground = true, backgroundColor = 0xFFF4F4F1)
@Composable
private fun EmptyStatePreview() {
    GimCheckTheme(mode = ThemeMode.LIGHT) {
        EmptyState(
            text = "No hay sesiones anotadas.",
            actionLabel = "Entrenar",
            onAction = {},
        )
    }
}
