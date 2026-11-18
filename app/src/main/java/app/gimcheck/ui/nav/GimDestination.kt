package app.gimcheck.ui.nav

import app.gimcheck.R

/**
 * Los nueve destinos de `docs/diseno.md` §8, con la ruta que usa `navigation-compose`.
 *
 * Por qué un enum y no un `sealed object` por destino con type-safe navigation: el grafo
 * no tiene **un solo argumento** (los detalles se abren en hoja, §5) y con un enum la
 * barra inferior, el título y el test de rutas leen la misma lista. Cuando aparezca el
 * primer argumento se migra a type-safe con `kotlinx-serialization`, sin cambiar la UI.
 *
 * [labelRes] es un id de `strings.xml` y no un `String`: el copy de §12 (voseo, sentence
 * case) vive en recursos, no en Kotlin.
 *
 * Orden: los cinco de la barra inferior primero, en el orden en que se pintan (§7.7);
 * después los cuatro que se abren desde adentro (`Entrenar` y `Descanso terminado`
 * fuera de la barra, §7.7; `Ejercicios` desde Rutinas; `Comparar` desde Progreso).
 */
enum class GimDestination(
    /** Ruta de `NavHost`, estable: es la clave que persiste el back stack. */
    val route: String,
    /** Nombre de pantalla de §12, para la barra y el título del placeholder. */
    val labelRes: Int,
    /** `true` = destino de la barra inferior (§7.7). */
    val inBottomBar: Boolean,
) {
    HOY("hoy", R.string.nav_hoy, true),
    RUTINAS("rutinas", R.string.nav_rutinas, true),
    PROGRESO("progreso", R.string.nav_progreso, true),
    HISTORIAL("historial", R.string.nav_historial, true),
    AJUSTES("ajustes", R.string.nav_ajustes, true),

    /** Modal con sesión viva: pide confirmación al salir (§7.7). */
    ENTRENAR("entrenar", R.string.nav_entrenar, false),

    /** Pantalla completa sin barra, se cierra sola o con "Empezar" (§8 #3). */
    DESCANSO("descanso", R.string.nav_descanso, false),

    /** Catálogo de ejercicios: se abre desde Rutinas. */
    EJERCICIOS("ejercicios", R.string.nav_ejercicios, false),

    /** Comparador de dos planes: se abre desde Progreso. */
    COMPARAR("comparar", R.string.nav_comparar, false),
    ;

    companion object {
        /** Arranque de la app: Hoy es el único destino que siempre tiene sentido. */
        val START: GimDestination = HOY

        /** Los cinco de la barra inferior, en el orden de §7.7. */
        val bottomBar: List<GimDestination> = entries.filter { it.inBottomBar }

        /** Busca por ruta; `null` si la ruta no es del grafo (no debería pasar). */
        fun byRoute(route: String?): GimDestination? = entries.firstOrNull { it.route == route }
    }
}
