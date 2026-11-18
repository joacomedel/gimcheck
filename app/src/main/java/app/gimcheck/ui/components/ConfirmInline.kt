package app.gimcheck.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.gimcheck.R
import app.gimcheck.data.settings.ThemeMode
import app.gimcheck.ui.theme.GimCheckTheme
import app.gimcheck.ui.theme.GimHeight
import app.gimcheck.ui.theme.GimRadius
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * `ConfirmInline` — `docs/diseno.md` §5: **nunca** `AlertDialog` para borrar; la
 * confirmación reemplaza al botón **en el lugar**.
 *
 * Por qué: el diálogo saca al usuario del contexto de la fila que va a borrar y agrega
 * una capa de superficie que §2 prohíbe. Acá el bloque ocupa el mismo hueco que el botón
 * original (lo decide el llamador, que cambia el `if` que pinta uno u otro).
 *
 * El confirmar va en `bad` (es un borrado) y el cancelar como texto: la acción
 * destructiva es la que tiene relleno para que el pulgar no la toque por reflejo. La
 * altura es la de fila (48 dp), no la del botón primario: adentro de una sección no
 * compite con la acción primaria de la pantalla.
 */
@Composable
fun ConfirmInline(
    question: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String? = null,
) {
    val colors = GimTheme.colors
    val confirmText = confirmLabel ?: stringResource(R.string.accion_confirmar)
    Column(
        modifier = modifier.padding(vertical = GimSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(GimSpacing.s),
    ) {
        Text(text = question, style = GimText.cuerpo, color = colors.ink)
        Row(
            horizontalArrangement = Arrangement.spacedBy(GimSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.defaultMinSize(minHeight = GimHeight.row),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.ink2),
            ) {
                Text(text = stringResource(R.string.accion_cancelar), style = GimText.boton, maxLines = 1)
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = GimHeight.row),
                shape = RoundedCornerShape(GimRadius.control),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.bad,
                    contentColor = colors.onAccent,
                ),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text(text = confirmText, style = GimText.boton, maxLines = 1)
            }
        }
    }
}

/** Documentación viva: no se renderiza acá (§11). */
@Preview(showBackground = true, backgroundColor = 0xFFF4F4F1)
@Composable
private fun ConfirmInlinePreview() {
    GimCheckTheme(mode = ThemeMode.LIGHT) {
        ConfirmInline(
            question = "¿Eliminar el plan «Full body 3 días»?",
            onConfirm = {},
            onCancel = {},
            modifier = Modifier.padding(GimSpacing.l),
        )
    }
}
