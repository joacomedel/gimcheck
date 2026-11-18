package app.gimcheck.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.gimcheck.AppContainer
import app.gimcheck.data.repository.SessionResumer
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
 *
 * **Abrir la app reanuda la sesión guiada (GIM-39)**: esta raíz es el único lugar que
 * ve el ciclo de vida de la activity, así que acá se engancha
 * [SessionResumer.onAppForeground] en `ON_START`. Antes de esto nadie llamaba a
 * `resumeActive` en `src/main` y el aviso de "descanso terminado" no aparecía solo.
 * Los dos parámetros son opcionales para que un preview o un test pueda componer la
 * raíz sin base de datos; la app real los pasa desde `MainActivity`.
 */
@Composable
fun GimCheckRoot(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    lifecycle: Lifecycle? = null,
    sessionResumer: SessionResumer? = null,
    container: AppContainer? = null,
) {
    val colors = GimTheme.colors
    val entry by navController.currentBackStackEntryAsState()
    val current = GimDestination.byRoute(entry?.destination?.route)

    if (lifecycle != null && sessionResumer != null) {
        ResumeSessionOnStart(lifecycle = lifecycle, resumer = sessionResumer)
    }

    CompositionLocalProvider(LocalAppContainer provides container) {
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
}

/**
 * Reanuda la sesión guiada cada vez que la app pasa a `STARTED` (GIM-39).
 *
 * `ON_START` y no `ON_RESUME` a propósito: es el primer evento en que la app ya es
 * visible, se dispara al abrir y al volver del background, y no se repite por un
 * diálogo encima. El observer se saca en `onDispose` para no quedar colgado del
 * lifecycle de una activity que se va.
 */
@Composable
private fun ResumeSessionOnStart(lifecycle: Lifecycle, resumer: SessionResumer) {
    DisposableEffect(lifecycle, resumer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) resumer.onAppForeground()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
