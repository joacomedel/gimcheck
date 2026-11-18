package app.gimcheck

import android.app.Application

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
    }

    companion object {
        /** El contenedor de la app en curso. Falla si no es [GimCheckApp]. */
        fun container(context: Application): AppContainer = (context as GimCheckApp).container
    }
}
