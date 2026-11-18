package app.gimcheck.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.gimcheck.R

/*
 * Tipografía de `docs/diseno.md` §4.
 *
 * Dos familias, empaquetadas como `.ttf` en `res/font` (la app no tiene red: Google
 * Fonts en runtime no es una opción). Pesos exactos del contrato, no "todos los que
 * había": cada TTF son ~110–210 KB en el APK.
 *
 *   Barlow Condensed 600 / 700  →  títulos de pantalla y números grandes
 *   IBM Plex Sans 400 / 500 / 600 →  todo lo demás
 *
 * Autoría, origen y licencia (SIL OFL 1.1) en `docs/fuentes.md`; el texto de la licencia
 * viaja en el APK en `res/raw/ofl_*.txt`.
 */

/** Barlow Condensed (§4): títulos, `StatTile`, cuenta regresiva, PR. */
val BarlowCondensed: FontFamily = FontFamily(
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)

/** IBM Plex Sans (§4): cuerpo, etiquetas, botones, tablas. */
val IbmPlexSans: FontFamily = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

/**
 * Cifras tabulares (§4: "Números: `fontFeatureSettings = "tnum"`").
 *
 * Barlow *sí* tiene `tnum` (sus cifras por defecto son proporcionales) y es la familia
 * de todos los números grandes: sin esto, la cuenta regresiva y los `StatTile` bailan al
 * cambiar de dígito. IBM Plex Sans ya trae cifras de ancho uniforme y no tiene la
 * feature; declararla ahí es inocuo.
 */
internal const val TABULAR_NUMBERS: String = "tnum"

/**
 * Escala del contrato (§4), con nombre propio en castellano.
 *
 * Interlineado: el contrato no lo fija; se usa ~1.1× en Barlow (display) y ~1.3× en
 * IBM Plex (cuerpo), que es lo que mantiene el bloque de texto parejo sin aire de más.
 */
object GimText {
    /** 40 sp Barlow 700 — cuenta regresiva del descanso, número de PR. */
    val display: TextStyle = TextStyle(
        fontFamily = BarlowCondensed,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_NUMBERS,
    )

    /** 30 sp Barlow 600 — valor de un `StatTile`. */
    val stat: TextStyle = TextStyle(
        fontFamily = BarlowCondensed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_NUMBERS,
    )

    /** 26 sp Barlow 600 — título de pantalla dentro del contenido. */
    val titulo: TextStyle = TextStyle(
        fontFamily = BarlowCondensed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_NUMBERS,
    )

    /**
     * 22 sp Barlow 600, mayúsculas y `letterSpacing 0.02em` — `SectionHeader`.
     *
     * `0.02em` de 22 sp = 0.44 sp: `letterSpacing` toma `TextUnit` resuelto, así que la
     * conversión va hecha y comentada en vez de repartida en cada uso.
     */
    val section: TextStyle = TextStyle(
        fontFamily = BarlowCondensed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.44.sp,
        fontFeatureSettings = TABULAR_NUMBERS,
    )

    /** 18 sp IBM Plex 600 — nombre de ejercicio en ficha, título de hoja. */
    val subtitulo: TextStyle = TextStyle(
        fontFamily = IbmPlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    )

    /** 15 sp IBM Plex 400 — texto normal. */
    val cuerpo: TextStyle = TextStyle(
        fontFamily = IbmPlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_NUMBERS,
    )

    /** 13 sp IBM Plex 400 — secundario, subtítulo de fila, tabla de Comparar. */
    val detalle: TextStyle = TextStyle(
        fontFamily = IbmPlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_NUMBERS,
    )

    /** 12 sp IBM Plex 500 — labels de campo, metadatos, ticks. */
    val etiqueta: TextStyle = TextStyle(
        fontFamily = IbmPlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_NUMBERS,
    )

    /**
     * Label del botón primario: 15 sp IBM Plex 600.
     *
     * El contrato no le da un tamaño propio ("botones" van en IBM Plex, §4) y Material 3
     * pinta `labelLarge` adentro de un `Button`; 12 sp en un botón de 52 dp se ve chico,
     * así que el rol M3 `labelLarge` se llena con el cuerpo en semibold.
     */
    val boton: TextStyle = TextStyle(
        fontFamily = IbmPlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    )
}

/**
 * La escala del contrato proyectada sobre **todos** los roles de Material 3.
 *
 * Los 15 roles están asignados a propósito: un rol sin asignar cae a la tipografía
 * default del `Typography()` de Material (Roboto), y una app con dos familias y un
 * `AlertDialog` en Roboto se nota. Mapeo:
 *
 * | Rol M3 | Estilo |
 * |---|---|
 * | `displayLarge` | `display` (40) |
 * | `displayMedium` | `stat` (30) |
 * | `displaySmall` / `headlineLarge` | `titulo` (26) |
 * | `headlineMedium` / `titleLarge` | `section` (22, mayúsculas) |
 * | `headlineSmall` / `titleMedium` | `subtitulo` (18) |
 * | `titleSmall` / `labelLarge` | `boton` (15/600) |
 * | `bodyLarge` | `cuerpo` (15) |
 * | `bodyMedium` / `bodySmall` | `detalle` (13) |
 * | `labelMedium` / `labelSmall` | `etiqueta` (12/500) |
 *
 * **No se leen de vuelta en la JVM.** En material3 1.4.0 (BOM 2026.09.00) `Typography`
 * tiene dos constructores (30 args y 15 `@OptIn(Expressive)`) y los `val` del primario
 * devuelven `null` al leer por la ruta de 15, aunque `MaterialTheme(typography =
 * GimTypography)` funcione bien en el APK. Por eso `TokensContractTest` valida el mapeo
 * contra `GimText` (la fuente de esta tabla) y sólo hace un smoke de construcción; el
 * aspecto en pantalla es deuda de la verificación visual (GIM-15).
 */
val GimTypography: Typography = Typography(
    displayLarge = GimText.display,
    displayMedium = GimText.stat,
    displaySmall = GimText.titulo,
    headlineLarge = GimText.titulo,
    headlineMedium = GimText.section,
    headlineSmall = GimText.subtitulo,
    titleLarge = GimText.section,
    titleMedium = GimText.subtitulo,
    titleSmall = GimText.boton,
    bodyLarge = GimText.cuerpo,
    bodyMedium = GimText.detalle,
    bodySmall = GimText.detalle,
    labelLarge = GimText.boton,
    labelMedium = GimText.etiqueta,
    labelSmall = GimText.etiqueta,
)
