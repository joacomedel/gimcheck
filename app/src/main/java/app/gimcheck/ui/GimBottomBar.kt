package app.gimcheck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gimcheck.data.settings.ThemeMode
import app.gimcheck.ui.nav.GimDestination
import app.gimcheck.ui.theme.GimCheckTheme
import app.gimcheck.ui.theme.GimHeight
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * Barra inferior de `docs/diseno.md` §7.7: cinco destinos, el seleccionado visible.
 *
 * Decisiones que se notan:
 * - **Texto, no íconos.** El contrato prohíbe emojis y sólo admite `Icons.Rounded.*` de
 *   `material-icons-core` (§2); con cinco nombres de una palabra ("Hoy", "Rutinas") el
 *   label alcanza y ahorra una dependencia entera de íconos y cinco `contentDescription`.
 * - **Sin `NavigationBar` de Material 3.** Esa pieza trae 80 dp de alto, indicador
 *   animado y `elevation`; acá es una fila plana sobre `surface` con un borde de 1 dp
 *   `line` arriba, 48 dp de alto por ítem y una marca de 2 dp bajo el seleccionado. Sin
 *   animación (§2: motion ≤ 150 ms; acá no hay motion).
 * - **Insets propios**: el `Scaffold` no le pasa `innerPadding` a su `bottomBar`, así que
 *   la barra aplica `WindowInsets.navigationBars` para no quedar debajo del gesto (§10).
 *
 * El `Scaffold` la oculta en Entrenar y Descanso terminado (§7.7).
 */
@Composable
fun GimBottomBar(
    current: GimDestination?,
    onSelect: (GimDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GimTheme.colors
    Column(modifier = modifier.fillMaxWidth().background(colors.surface)) {
        // Separador de 1 dp `line`: el único borde que existe (§3.2). No es elevación.
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            GimDestination.bottomBar.forEach { destination ->
                BottomBarItem(
                    destination = destination,
                    selected = destination == current,
                    onClick = { onSelect(destination) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(
    destination: GimDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GimTheme.colors
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = GimHeight.row)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = GimSpacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GimSpacing.xs),
    ) {
        // Marca del seleccionado: 2 dp de trazo (no es espaciado, por eso no sale de
        // la grilla de 4) y 24 dp de ancho (`GimSpacing.xl`).
        Box(
            modifier = Modifier
                .width(GimSpacing.xl)
                .height(2.dp)
                .background(if (selected) colors.accent else Color.Transparent),
        )
        Text(
            text = stringResource(destination.labelRes),
            style = GimText.etiqueta,
            color = if (selected) colors.accent else colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Documentación viva: no se renderiza acá (§11). */
@Preview(showBackground = true, backgroundColor = 0xFF121211)
@Composable
private fun GimBottomBarPreview() {
    GimCheckTheme(mode = ThemeMode.DARK) {
        GimBottomBar(current = GimDestination.RUTINAS, onSelect = {})
    }
}
