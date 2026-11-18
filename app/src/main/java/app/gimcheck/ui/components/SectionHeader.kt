package app.gimcheck.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import app.gimcheck.data.settings.ThemeMode
import app.gimcheck.ui.theme.GimCheckTheme
import app.gimcheck.ui.theme.GimHeight
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * `SectionHeader` — `docs/diseno.md` §5: `section` (22 sp Barlow 600, **mayúsculas** y
 * `letterSpacing 0.02em`, ya en el estilo) y la acción opcional a la derecha en `accent`.
 *
 * Las mayúsculas se aplican acá y no en cada pantalla: es lo único del contrato que va en
 * mayúsculas (junto al `display`) y si quedara a cargo de los llamadores la mitad de los
 * títulos saldría en sentence case (§2).
 *
 * [action] es el label ("Nuevo plan", "Ver sesión") y [onAction] el callback; sin las dos
 * no se dibuja nada a la derecha. Altura mínima 48 dp en la acción: es un blanco táctil,
 * no una etiqueta.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = GimTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = GimText.section,
            color = colors.ink,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            Text(
                text = action,
                style = GimText.boton,
                color = colors.accent,
                maxLines = 1,
                modifier = Modifier
                    .defaultMinSize(minHeight = GimHeight.row)
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(start = GimSpacing.m, top = GimSpacing.s, bottom = GimSpacing.s),
            )
        }
    }
}

/** Documentación viva: no se renderiza acá (§11). */
@Preview(showBackground = true, backgroundColor = 0xFFF4F4F1)
@Composable
private fun SectionHeaderPreview() {
    GimCheckTheme(mode = ThemeMode.LIGHT) {
        SectionHeader(
            title = "Últimos",
            action = "Ver sesión",
            onAction = {},
            modifier = Modifier.padding(GimSpacing.l),
        )
    }
}
