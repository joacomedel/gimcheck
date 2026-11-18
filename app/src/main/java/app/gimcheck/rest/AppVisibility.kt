package app.gimcheck.rest

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Si la app está en primer plano, para el aviso de fin de descanso (§5).
 *
 * La diferencia importa: en primer plano el aviso es **inline** en Entrenar y no se roba
 * la pantalla; con la app cerrada se usa el full-screen intent, el overlay o el heads-up.
 * El cronómetro vive en un servicio y la alarma en un receptor —ninguno de los dos ve la
 * UI—, así que el estado se mantiene acá con el ciclo de vida de las activities.
 *
 * Se registra una sola vez desde [app.gimcheck.GimCheckApp] y no hay dependencia nueva
 * (`androidx.lifecycle-process` no está en el proyecto).
 */
object AppVisibility {

    @Volatile
    var isForeground: Boolean = false
        private set

    private var started: Int = 0

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started += 1
                isForeground = true
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                isForeground = started > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
