package app.gimcheck.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.gimcheck.ui.nav.GimDestination
import app.gimcheck.ui.nav.GimNavHost
import app.gimcheck.ui.nav.navigateToBottomBar
import app.gimcheck.ui.theme.GimTheme

/**
 * Raíz del árbol de Compose: el `Scaffold` único de la app (§7.1).
 *
 * Qué resuelve acá y no en cada pantalla:
 * - **Edge-to-edge**: `contentWindowInsets = WindowInsets.safeDrawing` (§10). El padding
 *   del sistema llega por el `PaddingValues` del `Scaffold` y se aplica **una vez** al
 *   `NavHost`; ninguna de las nueve pantallas repite `statusBarsPadding()`.
 * - **Superficie base**: `containerColor = bg` y `contentColor = ink`. Todo lo que se
 *   dibuje adentro hereda el color correcto aunque nadie lo pase.
 * - **Barra inferior**: la pinta el `Scaffold`, no cada pantalla, y sólo en los cinco
 *   destinos de la barra (§7.7). En Entrenar y Descanso terminado desaparece: la sesión
 *   es modal y no se sale por un tab.
 *
 * La navegación la resuelve `GimNavHost`; acá sólo se decide *si hay barra* mirando la
 * ruta actual del back stack (`currentBackStackEntryAsState` recompone la raíz en cada
 * cambio de destino, no en cada frame).
 */
@Composable
fun GimCheckRoot(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val colors = GimTheme.colors
    val entry by navController.currentBackStackEntryAsState()
    val current = GimDestination.byRoute(entry?.destination?.route)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.bg,
        contentColor = colors.ink,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (current != null && current.inBottomBar) {
                GimBottomBar(
                    current = current,
                    onSelect = { navController.navigateToBottomBar(it) },
                )
            }
        },
    ) { innerPadding ->
        GimNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
