package app.gimcheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.gimcheck.data.settings.AppSettings
import app.gimcheck.ui.GimCheckRoot
import app.gimcheck.ui.theme.GimCheckTheme
import app.gimcheck.ui.theme.isDark

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = GimCheckApp.container(application)
        // Primer arranque sin datos: el seed de assets/seed.json se carga en segundo
        // plano (idempotente; ver SeedImporter). Se dispara desde acá y no desde
        // Application para que los tests JVM no toquen disco al construir el grafo.
        container.seedIfEmptyAsync()

        // Ciclo de vida de la activity para el enganche de GIM-39: `GimCheckRoot` reanuda
        // la sesión guiada en `ON_START` (el aviso de "descanso terminado" al abrir).
        val activityLifecycle = lifecycle

        setContent {
            // `theme` es de §8 y vive en Room (SettingsRepository): el Flow vuelve a
            // emitir al cambiar el ajuste, este `collectAsState` recompone y
            // `GimCheckTheme` repinta sin recrear la activity.
            val settings by container.settings.settings.collectAsState(initial = AppSettings.DEFAULT)
            val dark = settings.theme.isDark(isSystemInDarkTheme())

            // Edge-to-edge con las barras transparentes (§10). Los íconos de las barras
            // siguen al tema **de la app**, no al del sistema: con el ajuste en LIGHT
            // sobre un teléfono en oscuro, íconos claros sobre fondo claro serían
            // invisibles. `SystemBarStyle.auto` recibe el detector justamente para eso.
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ) { _ -> dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            GimCheckTheme(mode = settings.theme) {
                GimCheckRoot(
                    lifecycle = activityLifecycle,
                    sessionResumer = container.sessionResumer,
                    container = container,
                )
            }
        }
    }
}
