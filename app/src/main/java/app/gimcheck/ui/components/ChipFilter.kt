package app.gimcheck.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import app.gimcheck.data.settings.ThemeMode
import app.gimcheck.ui.theme.GimCheckTheme
import app.gimcheck.ui.theme.GimHeight
import app.gimcheck.ui.theme.GimRadius
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimStroke
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * `ChipFilter` — `docs/diseno.md` §5: borde 1 dp `line`; **seleccionado = fondo `ink` y
 * texto `bg`** (no acento: el acento está reservado a la acción primaria, §3.1).
 *
 * Alto 44 dp (`GimHeight.chip`, mínimo táctil) vía `defaultMinSize` y no `height`: el
 * chip crece con `fontScale` (§10). Radio 999 dp: es una píldora, no un rectángulo
 * redondeado (§3.2). `selectable` y no `clickable` para que el lector de pantalla anuncie
 * el estado seleccionado.
 *
 * Es la pieza de `DayChips` (§5): una fila scrollable horizontal de chips a 44 dp.
 */
@Composable
fun ChipFilter(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GimTheme.colors
    val shape = RoundedCornerShape(GimRadius.chip)
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = GimHeight.chip)
            .background(if (selected) colors.ink else Color.Transparent, shape)
            .border(GimStroke.border, if (selected) colors.ink else colors.line, shape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = GimSpacing.m, vertical = GimSpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = GimText.detalle,
            color = if (selected) colors.bg else colors.ink2,
            maxLines = 1,
        )
    }
}

/** Documentación viva: no se renderiza acá (§11). */
@Preview(showBackground = true, backgroundColor = 0xFFF4F4F1)
@Composable
private fun ChipFilterPreview() {
    GimCheckTheme(mode = ThemeMode.LIGHT) {
        Row(modifier = Modifier.padding(GimSpacing.l)) {
            ChipFilter(label = "Día A", selected = true, onClick = {})
            ChipFilter(label = "Día B", selected = false, onClick = {})
        }
    }
}
