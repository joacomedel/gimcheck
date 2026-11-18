package app.gimcheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Primer arranque sin datos: el seed de assets/seed.json se carga en segundo
        // plano (idempotente; ver SeedImporter). Se dispara desde acá y no desde
        // Application para que los tests JVM no toquen disco al construir el grafo.
        GimCheckApp.container(application).seedIfEmptyAsync()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "GimCheck",
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
        }
    }
}
