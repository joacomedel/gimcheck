package app.gimcheck

import android.app.Application
import app.gimcheck.rest.AppVisibility

/**
 * `Application` de GimCheck: arma el [AppContainer] (inyección manual, sin Hilt).
 *
 * Qué hace y qué no: crea el contenedor y nada más. El seed **no** se dispara acá
 * sino en `MainActivity` ([AppContainer.seedIfEmptyAsync]) para que el grafo de la app
 * se construya sin tocar disco al arrancar un test: `Room` no abre el archivo hasta la
 * primera consulta.
 */
class GimCheckApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // `alertMode` de §5 distingue "app en primer plano" (aviso inline, sin robar la
        // pantalla) de "app cerrada" (full-screen intent u overlay). El cronómetro vive
        // en un servicio y la alarma en un receptor: ninguno ve la UI, así que el estado
        // se registra una sola vez acá.
        AppVisibility.register(this)
    }

    companion object {
        /** El contenedor de la app en curso. Falla si no es [GimCheckApp]. */
        fun container(context: Application): AppContainer = (context as GimCheckApp).container
    }
}
