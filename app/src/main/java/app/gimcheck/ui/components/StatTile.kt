package app.gimcheck.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.gimcheck.data.settings.ThemeMode
import app.gimcheck.ui.theme.GimCheckTheme
import app.gimcheck.ui.theme.GimRadius
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimStroke
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * `StatTile` — `docs/diseno.md` §5: `k: String, v: String, s: String?`.
 *
 * Un número grande y quieto: el valor va en `stat` (30 sp Barlow, cifras tabulares), la
 * etiqueta arriba en `etiqueta` (12 sp) y el detalle opcional abajo en `detalle`.
 *
 * Superficie **plana** con borde de 1 dp `line` (§2: máximo un nivel de superficie, sin
 * sombra, sin borde interno). El `Modifier` va último y sin altura fija: en una fila de
 * dos o tres tiles el alto lo fija el contenedor con `IntrinsicSize` o `weight`.
 */
@Composable
fun StatTile(
    k: String,
    v: String,
    s: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = GimTheme.colors
    val shape = RoundedCornerShape(GimRadius.control)
    Column(
        modifier = modifier
            .background(colors.surface, shape)
            .border(GimStroke.border, colors.line, shape)
            .padding(GimSpacing.m),
    ) {
        Text(text = k, style = GimText.etiqueta, color = colors.muted, maxLines = 1)
        Text(text = v, style = GimText.stat, color = colors.ink, maxLines = 1)
        if (s != null) {
            Text(text = s, style = GimText.detalle, color = colors.muted, maxLines = 1)
        }
    }
}

/** Documentación viva: no se renderiza acá (no hay SDK de Android en el Moto, §11). */
@Preview(showBackground = true, backgroundColor = 0xFFF4F4F1)
@Composable
private fun StatTilePreview() {
    GimCheckTheme(mode = ThemeMode.LIGHT) {
        Row {
            StatTile(k = "Duración", v = "52 min", s = "último entrenamiento")
            StatTile(k = "Series", v = "18")
        }
    }
}
