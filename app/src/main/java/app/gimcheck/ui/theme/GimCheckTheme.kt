package app.gimcheck.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import app.gimcheck.data.settings.ThemeMode

/*
 * El tema de la app: `docs/diseno.md` §3 + §4 sobre Material 3.
 *
 * Dos capas, una sola fuente:
 *   1. [GimColors] (Tokens.kt) es la paleta del contrato, con los trece nombres que usan
 *      las pantallas y los gráficos.
 *   2. [ColorScheme] es una proyección de esos trece hacia los roles de Material 3, para
 *      que los componentes de la librería (`Button`, `TextField`, `ModalBottomSheet`,
 *      `CircularProgressIndicator`) hereden el contrato sin que nadie los pinte a mano.
 *
 * El orden importa: si hay que cambiar un color, se cambia en Tokens.kt. Si se cambia
 * acá, Material 3 y las pantallas se desincronizan.
 */

/** Los colores del modo activo. Es lo que leen las pantallas y los gráficos. */
object GimTheme {

    /** Paleta del modo actual (claro u oscuro), puesta por [GimCheckTheme]. */
    val colors: GimColors
        @Composable
        @ReadOnlyComposable
        get() = LocalGimColors.current
}

private val LocalGimColors = staticCompositionLocalOf { DarkColors }

/**
 * §8 `theme`: `SYSTEM` sigue al sistema y es el default; `LIGHT` / `DARK` lo pisan.
 *
 * Vive acá —y no en `MainActivity`— porque lo usan dos consumidores que tienen que
 * coincidir: la paleta y el color de los íconos de las barras del sistema
 * (`enableEdgeToEdge` en `MainActivity`).
 */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Tema único de la app.
 *
 * Cambiar [mode] **repinta en vivo**: [mode] viene del `Flow` de `SettingsRepository`
 * (los ajustes de §8 están en Room), así que tocar "Apariencia → Tema" recompone el árbol
 * completo sin recrear la activity ni reiniciar la app.
 *
 * No hay tema "dinámico" (Material You): el contrato fija la paleta (§3.1) y un acento.
 */
@Composable
fun GimCheckTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = mode.isDark(isSystemInDarkTheme())
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalGimColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = GimTypography,
            shapes = GimShapes,
            content = content,
        )
    }
}

/**
 * Radios del contrato (§3.2) como roles de Material 3: 10 dp en controles y tarjetas,
 * 12 dp en hojas. `extraLarge` también 12: nada en esta app es más redondo que una hoja.
 */
val GimShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(GimRadius.control),
    small = RoundedCornerShape(GimRadius.control),
    medium = RoundedCornerShape(GimRadius.control),
    large = RoundedCornerShape(GimRadius.sheet),
    extraLarge = RoundedCornerShape(GimRadius.sheet),
)

/**
 * Proyección de la paleta del contrato sobre Material 3.
 *
 * Todos los roles se asignan explícitamente, incluidos los `surfaceContainer*` (los usa
 * `ModalBottomSheet` y las hojas de §5) y los `inverse*` (los usa un `Snackbar`). Un rol
 * sin asignar no queda "vacío": queda con el color default de Material 3, y ahí es donde
 * aparece el lila de la plantilla.
 *
 * Decisiones:
 * - `surfaceTint` = `surface`: sin elevación tonal (§3.2: "Nada de `elevation` salvo
 *   la hoja"). Si algún componente aplica tinte, no se ve.
 * - `secondary`/`tertiary` = `ink2`/`good` para que un `FilledTonalButton` no invente un
 *   segundo acento (§3.1: el acento es uno).
 * - `error` = `bad`, es el mismo estado.
 * - `scrim` queda en el default (negro translúcido): es el velo de la hoja, no un color
 *   del contrato.
 */
internal fun GimColors.toColorScheme(): ColorScheme {
    // Se parte del esquema del modo (el otro no está ni construido) y se pisan todos los
    // roles que el contrato define. `copy` y no una llamada con 30 argumentos: así el
    // diff de un token nuevo es una línea.
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = surface2,
        onPrimaryContainer = ink,
        inversePrimary = accent,
        secondary = ink2,
        onSecondary = bg,
        secondaryContainer = surface2,
        onSecondaryContainer = ink2,
        tertiary = good,
        onTertiary = bg,
        tertiaryContainer = surface2,
        onTertiaryContainer = good,
        background = bg,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = surface2,
        onSurfaceVariant = ink2,
        surfaceTint = surface,
        inverseSurface = ink,
        inverseOnSurface = bg,
        error = bad,
        onError = bg,
        errorContainer = surface2,
        onErrorContainer = bad,
        outline = line,
        outlineVariant = line,
        surfaceBright = surface,
        surfaceDim = bg,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surface2,
        surfaceContainerLow = surface,
        surfaceContainerLowest = bg,
    )
}
