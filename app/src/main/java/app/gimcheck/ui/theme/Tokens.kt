package app.gimcheck.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Tokens de `docs/diseno.md` §3. Este es el **único** archivo de `app/` con colores
 * literales: si una pantalla escribe `Color(0xFF…)`, es bug de review.
 *
 * Por qué un archivo y no el `ColorScheme` de Material 3 como fuente: el contrato tiene
 * trece colores con nombre propio (incluido el semáforo y las series de gráfico) que
 * Material 3 no modela, y algunos roles M3 (`surfaceContainer*`) que el contrato no
 * quiere. Acá viven los trece; el `ColorScheme` es una *proyección* de ellos
 * (`GimCheckTheme.kt`), no al revés.
 *
 * Contraste: cada par de texto está medido con la fórmula WCAG 2.1 y verificado por
 * `app/src/test/java/app/gimcheck/ui/theme/TokensContractTest.kt`. La tabla con los
 * ratios está en `docs/diseno.md` §3.1.
 */

/**
 * Paleta de un modo (claro u oscuro). Inmutable y completa: no hay color "que falte y
 * caiga al default de Material" — eso es lo que hace que una app se vea genérica.
 */
@Immutable
data class GimColors(
    /** Fondo de la app (§3.1 `bg`). */
    val bg: Color,
    /** Tarjetas, filas, hojas (§3.1 `surface`). */
    val surface: Color,
    /** Campos, pistas de barra, banda de referencia (§3.1 `surface2`). */
    val surface2: Color,
    /** Bordes de 1 dp y separadores (§3.1 `line`). No es color de texto: no aplica 4.5. */
    val line: Color,
    /** Texto principal y números (§3.1 `ink`). */
    val ink: Color,
    /** Texto secundario (§3.1 `ink2`). */
    val ink2: Color,
    /** Metadatos, ejes, placeholders (§3.1 `muted`). */
    val muted: Color,
    /** Único acento: acción primaria, selección, serie principal (§3.1 `accent`). */
    val accent: Color,
    /**
     * Texto sobre un relleno `accent` (botón primario, chip seleccionado con acento).
     * Es el `onPrimary` de Material 3; va acá para que no exista un cuarto color de
     * texto escondido en el tema.
     */
    val onAccent: Color,
    /** En rango, PR nuevo, descanso listo (§3.1 `good`). */
    val good: Color,
    /** Fuera de rango, advertencia de superserie (§3.1 `warn`). */
    val warn: Color,
    /** Conflicto, eliminar, sesión abandonada (§3.1 `bad`). */
    val bad: Color,
    /** Segunda serie de un gráfico (comparar, plan B). */
    val series2: Color,
    /** Tercera categoría de un gráfico. */
    val series3: Color,
    /** `true` en la variante oscura; lo usa `GimCheckTheme` para las barras del sistema. */
    val isDark: Boolean,
) {
    /**
     * Serie principal de un gráfico. El contrato dice `series1 = accent` (§3.1), así que
     * no es un color propio: es el acento, y no puede desincronizarse.
     */
    val series1: Color get() = accent
}

/**
 * Modo claro — la variante (§3.1, modo oscuro primero).
 *
 * Dos valores se corrieron respecto de la primera versión de `docs/diseno.md` porque no
 * llegaban a 4.5:1 como texto (medido, no estimado): `muted` y `accent`. El archivo del
 * contrato se actualizó en el mismo commit.
 */
val LightColors: GimColors = GimColors(
    bg = Color(0xFFF4F4F1),
    surface = Color(0xFFFCFCFB),
    surface2 = Color(0xFFECECE7),
    line = Color(0xFFD9D8D1),
    ink = Color(0xFF0B0B0B),
    ink2 = Color(0xFF52514E),
    muted = Color(0xFF706F6A),
    accent = Color(0xFF2770C7),
    onAccent = Color(0xFFFFFFFF),
    good = Color(0xFF0A7A3D),
    warn = Color(0xFFA35F00),
    bad = Color(0xFFC0302E),
    series2 = Color(0xFFEB6834),
    series3 = Color(0xFF1BAF7A),
    isDark = false,
)

/** Modo oscuro — el principal (§3.1). */
val DarkColors: GimColors = GimColors(
    bg = Color(0xFF121211),
    surface = Color(0xFF1A1A19),
    surface2 = Color(0xFF262625),
    line = Color(0xFF34332F),
    ink = Color(0xFFF0EFEC),
    ink2 = Color(0xFFC3C2B7),
    muted = Color(0xFF8F8E86),
    accent = Color(0xFF3987E5),
    onAccent = Color(0xFF121211),
    good = Color(0xFF4CC27F),
    warn = Color(0xFFE0A03A),
    bad = Color(0xFFEC7A78),
    series2 = Color(0xFFD95926),
    series3 = Color(0xFF199E70),
    isDark = true,
)

/**
 * Espaciado: grilla de 4 (§3.2). El padding de pantalla es [l] y el `contentPadding` de
 * toda `LazyColumn` también: si una pantalla usa 20 dp, está fuera de la grilla.
 */
object GimSpacing {
    /** Separación entre label y valor. */
    val xs: Dp = 4.dp

    /** `verticalArrangement` de listas, gap entre filas. */
    val s: Dp = 8.dp

    /** Padding interno de tarjetas chicas, gap entre bloques. */
    val m: Dp = 12.dp

    /** Padding de pantalla y `contentPadding` de `LazyColumn`. */
    val l: Dp = 16.dp

    /** Separación entre secciones. */
    val xl: Dp = 24.dp

    /** Aire antes de la acción primaria. */
    val xxl: Dp = 32.dp
}

/**
 * Radios (§3.2). No hay un cuarto radio: 10 en controles y tarjetas, 999 en chips,
 * 12 en hojas, 6 en el tooltip de gráficos (§6).
 */
object GimRadius {
    /** Controles y tarjetas. */
    val control: Dp = 10.dp

    /** Chips: píldora, no un rectángulo redondeado. */
    val chip: Dp = 999.dp

    /** Hojas modales (`ModalBottomSheet`). */
    val sheet: Dp = 12.dp

    /** Tooltip de un gráfico tocado (§6). */
    val tooltip: Dp = 6.dp
}

/**
 * Alturas mínimas táctiles (§3.2). Son mínimos, no máximos: con `fontScale` 200 % la
 * fila crece (§10).
 */
object GimHeight {
    /** Fila tocable, botón de ícono, fila de sesión en Historial. */
    val row: Dp = 48.dp

    /** Chip (y `DayChips`). */
    val chip: Dp = 44.dp

    /** Stepper chico (`− valor +` en Hoy, ficha de ejercicio). */
    val stepper: Dp = 40.dp

    /** Botón primario ancho completo, abajo. */
    val button: Dp = 52.dp

    /** `ExerciseRow` en listas de rutina y ejercicios. */
    val exerciseRow: Dp = 56.dp

    /** `Meter`: alto de la barra (§5). */
    val meter: Dp = 20.dp
}

/** Grosor del único borde que existe: 1 dp `line` (§3.2). */
object GimStroke {
    val border: Dp = 1.dp
}

/** La única elevación permitida: `ModalBottomSheet` (§3.2). */
object GimElevation {
    val sheet: Dp = 2.dp
}
