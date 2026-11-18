package app.gimcheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * Estructura común de los **placeholders** de GIM-13: título + una línea de qué va a ir
 * ahí + contenido + acción primaria al pie.
 *
 * Es temporal y a propósito: cada pantalla de §8 se rellena en su ticket (GIM-15..GIM-21)
 * y ese ticket reemplaza su archivo por la estructura real. Lo que no es temporal es la
 * composición (§7): título **dentro del contenido** (no en una barra superior que gaste
 * 56 dp repitiendo la barra inferior), padding de pantalla 16 y la acción primaria abajo,
 * ancho completo.
 *
 * `content` no scrollea: los placeholders no tienen datos. La pantalla real sí lleva
 * `LazyColumn` con `contentPadding` 16 y `verticalArrangement` 8 (§7.4).
 */
@Composable
fun PlaceholderScreen(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    primary: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = GimTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(GimSpacing.l),
    ) {
        Text(text = title, style = GimText.titulo, color = colors.ink)
        Text(text = hint, style = GimText.detalle, color = colors.muted)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = GimSpacing.l),
            verticalArrangement = Arrangement.spacedBy(GimSpacing.l),
        ) {
            content()
        }
        if (primary != null) {
            Spacer(modifier = Modifier.height(GimSpacing.xxl))
            primary()
        }
    }
}
