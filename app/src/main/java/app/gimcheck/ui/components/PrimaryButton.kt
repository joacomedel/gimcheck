package app.gimcheck.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.gimcheck.data.settings.ThemeMode
import app.gimcheck.ui.theme.GimCheckTheme
import app.gimcheck.ui.theme.GimHeight
import app.gimcheck.ui.theme.GimRadius
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * Botón primario — `docs/diseno.md` §7.3: abajo, **ancho completo, 52 dp** (§3.2
 * `GimHeight.button`), label en `boton` (15 sp IBM Plex 600).
 *
 * No está en la tabla de §5 porque no es un componente de contenido sino la acción
 * primaria de una pantalla; vive acá para que las nueve pantallas no repitan el mismo
 * `Button(colors = …, shape = …, padding = …)`. Si aparece un segundo estilo de botón,
 * es que hay dos acciones primarias compitiendo por el acento (§3.1).
 *
 * [GimHeight.button] es un **mínimo** (`heightIn`), no un alto fijo: a 200 % de
 * `fontScale` el botón crece en vez de recortar el label (§10).
 */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = GimTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = GimHeight.button),
        enabled = enabled,
        shape = RoundedCornerShape(GimRadius.control),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            disabledContainerColor = colors.surface2,
            disabledContentColor = colors.muted,
        ),
        contentPadding = PaddingValues(horizontal = GimSpacing.l),
    ) {
        Text(text = label, style = GimText.boton, maxLines = 2)
    }
}

/** Documentación viva: no se renderiza acá (§11). */
@Preview(showBackground = true, backgroundColor = 0xFF121211)
@Composable
private fun PrimaryButtonPreview() {
    GimCheckTheme(mode = ThemeMode.DARK) {
        PrimaryButton(label = "Empezar sesión", onClick = {})
    }
}
