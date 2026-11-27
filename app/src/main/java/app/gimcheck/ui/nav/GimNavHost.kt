package app.gimcheck.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import app.gimcheck.ui.screens.AjustesScreen
import app.gimcheck.ui.screens.CompararScreen
import app.gimcheck.ui.screens.DescansoScreen
import app.gimcheck.ui.screens.EjerciciosScreen
import app.gimcheck.ui.screens.EntrenarScreen
import app.gimcheck.ui.screens.HistorialScreen
import app.gimcheck.ui.screens.HoyScreen
import app.gimcheck.ui.screens.ProgresoScreen
import app.gimcheck.ui.screens.RutinasScreen

/**
 * El grafo de las nueve pantallas de `docs/diseno.md` §8.
 *
 * **Sin transiciones**: las cuatro `Transition` están en `None` a propósito. El default de
 * `navigation-compose` 2.10 es `fadeIn(tween(700))` / `fadeOut(tween(700))`
 * (`DefaultNavTransitions`), que es tres veces el límite de motion del contrato (§2:
 * "motion ≤ 150 ms, sin rebotes") y compone dos pantallas completas a la vez. Un cambio de
 * destino acá es instantáneo: es lo que hace que la app se sienta seca y no "animada por
 * IA".
 *
 * Rutas por `String` (`GimDestination`), sin argumentos: los detalles se abren en hoja
 * (§5), así que no hay nada que pasar entre destinos todavía. Cuando aparezca el primero
 * se migra a type-safe con `kotlinx-serialization`.
 *
 * Criterio de este ticket: las nueve rutas existen y se llega a todas sin crash.
 * - barra inferior → Hoy, Rutinas, Progreso, Historial, Ajustes
 * - Hoy → Entrenar (modal); "Descanso terminado" se abre desde el aviso de descanso
 *   (GIM-22/GIM-23), no desde Entrenar
 * - Rutinas → Ejercicios; Progreso → Comparar
 */
@Composable
fun GimNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = GimDestination.START.route,
        modifier = modifier,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(GimDestination.HOY.route) {
            HoyScreen(
                onStartSession = { navController.navigate(GimDestination.ENTRENAR.route) },
                onOpenRutinas = { navController.navigateToBottomBar(GimDestination.RUTINAS) },
            )
        }
        composable(GimDestination.ENTRENAR.route) {
            EntrenarScreen(onExit = { navController.popBackStack() })
        }
        composable(GimDestination.DESCANSO.route) {
            DescansoScreen(
                onStart = {
                    navController.popBackStack(GimDestination.ENTRENAR.route, inclusive = false)
                },
            )
        }
        composable(GimDestination.RUTINAS.route) {
            RutinasScreen(
                // El catálogo global sigue siendo GIM-18 (pantalla propia). Agregar a un
                // día ya no navega: lo resuelve el editor in situ (GIM-47).
                onOpenExercises = { navController.navigate(GimDestination.EJERCICIOS.route) },
            )
        }
        composable(GimDestination.EJERCICIOS.route) {
            EjerciciosScreen(onClearFilter = { navController.popBackStack() })
        }
        composable(GimDestination.PROGRESO.route) {
            ProgresoScreen(
                onGoHoy = { navController.navigateToBottomBar(GimDestination.HOY) },
                onOpenCompare = { navController.navigate(GimDestination.COMPARAR.route) },
            )
        }
        composable(GimDestination.HISTORIAL.route) {
            HistorialScreen(
                onStartSession = { navController.navigate(GimDestination.ENTRENAR.route) },
            )
        }
        composable(GimDestination.COMPARAR.route) {
            CompararScreen(
                onCreateRoutine = {
                    navController.navigateToBottomBar(GimDestination.RUTINAS)
                },
            )
        }
        composable(GimDestination.AJUSTES.route) {
            AjustesScreen()
        }
    }
}

/**
 * Cambio de sección desde la barra inferior, sin apilar destinos.
 *
 * `popUpTo(Hoy)` + `saveState`/`restoreState` es el patrón de la documentación: el back
 * stack nunca crece tocando la barra y cada sección conserva su scroll al volver. Tocar
 * la sección activa no hace nada (si no, se apilaría un duplicado silencioso).
 */
internal fun NavHostController.navigateToBottomBar(destination: GimDestination) {
    if (currentDestination?.route == destination.route) return
    navigate(destination.route) {
        popUpTo(GimDestination.START.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
