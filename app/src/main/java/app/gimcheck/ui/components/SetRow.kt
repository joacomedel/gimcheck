package app.gimcheck.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import app.gimcheck.R
import app.gimcheck.data.settings.ThemeMode
import app.gimcheck.ui.theme.GimCheckTheme
import app.gimcheck.ui.theme.GimHeight
import app.gimcheck.ui.theme.GimRadius
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimStroke
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * `SetRow` — `docs/diseno.md` §5: fila de serie anotada (`#`, peso, reps, check), 48 dp,
 * **editable al toque**.
 *
 * No lleva checkbox: en la sesión guiada una serie anotada ya está hecha, y lo que se
 * puede hacer con ella es corregirla (peso/reps) o borrarla. El "check" del contrato es
 * la serie existiendo; el calentamiento va como sufijo en `muted` porque no cuenta para
 * volumen ni PR (§4).
 *
 * La fila **no** dibuja un ícono de lápiz: "Editar" es texto en `accent` (§2: si falta el
 * ícono, texto) y la fila entera es el blanco táctil de 48 dp.
 */
@Composable
fun SetRow(
    index: Int,
    weightLabel: String,
    repsLabel: String,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    isWarmup: Boolean = false,
    selected: Boolean = false,
) {
    val colors = GimTheme.colors
    val shape = RoundedCornerShape(GimRadius.control)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = GimHeight.row)
            .background(if (selected) colors.surface2 else colors.surface, shape)
            .border(GimStroke.border, colors.line, shape)
            .clickable(role = Role.Button, onClick = onEdit)
            .padding(horizontal = GimSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GimSpacing.m),
    ) {
        Text(
            text = "#$index",
            style = GimText.etiqueta,
            color = colors.muted,
            maxLines = 1,
        )
        Text(
            text = weightLabel,
            style = GimText.subtitulo,
            color = colors.ink,
            maxLines = 1,
        )
        Text(
            text = "× $repsLabel",
            style = GimText.detalle,
            color = colors.ink2,
            maxLines = 1,
        )
        if (isWarmup) {
            Text(
                text = stringResource(R.string.entrenar_calentamiento_corto),
                style = GimText.etiqueta,
                color = colors.muted,
                maxLines = 1,
            )
        }
        Text(
            text = stringResource(R.string.entrenar_editar),
            style = GimText.boton,
            color = colors.accent,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(1f)
                .padding(start = GimSpacing.s),
        )
    }
}

/** Documentación viva: no se renderiza acá (§11). */
@Preview(showBackground = true, backgroundColor = 0xFF121211)
@Composable
private fun SetRowPreview() {
    GimCheckTheme(mode = ThemeMode.DARK) {
        Row(modifier = Modifier.padding(GimSpacing.l)) {
            SetRow(index = 1, weightLabel = "82,5 kg", repsLabel = "10", onEdit = {})
        }
    }
}
