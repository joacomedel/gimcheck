package app.gimcheck.rest

import android.content.Context
import app.gimcheck.data.settings.AppSettings

/**
 * Publicación de la notificación de fin de descanso, detrás de una interfaz.
 *
 * Existe para que [RestAlerter] se pueda testear sin depender de que Robolectric
 * publique de verdad: los tests de la matriz de `alertMode` miran las acciones, y un
 * test aparte usa la implementación real con `ShadowNotificationManager`.
 */
fun interface RestDoneNotifier {

    fun post(plan: RestAlertPlan)
}

/** La real: [RestNotifications.postRestDone] con el full-screen intent resuelto. */
class SystemRestDoneNotifier(
    private val context: Context,
    private val fullScreenAllowed: () -> Boolean = {
        RestPermissions.fullScreenIntentGranted(context)
    },
) : RestDoneNotifier {

    override fun post(plan: RestAlertPlan) {
        val fullScreen = plan.screen == RestScreenDelivery.FULL_SCREEN_INTENT &&
            fullScreenAllowed()
        RestNotifications.postRestDone(context, fullScreen)
    }
}

/**
 * El aviso de fin de descanso (`docs/especificacion.md` §5), en un solo lugar:
 *
 * | Parte del plan | Qué hace |
 * |---|---|
 * | (siempre) | publica la notificación del canal `rest_done_v2` |
 * | `vibrate` | patrón de `restVibration` con el `Vibrator` |
 * | `sound` | la opción de `restSound` (canal o `RingtoneManager`) |
 * | `FULL_SCREEN_INTENT` | lo lleva la notificación (el sistema abre [RestDoneActivity]) |
 * | `OVERLAY` | dibuja el aviso con `SYSTEM_ALERT_WINDOW` |
 * | `HEADS_UP` | nada extra: la importancia alta del canal ya hace el heads-up |
 *
 * **Nunca falla en silencio**: la notificación se publica también con `NONE` y con
 * todos los permisos negados. Todo lo demás es una mejora sobre eso.
 *
 * La decisión ([RestAlertPlans]) está separada de la ejecución para que la matriz de
 * 8 modos se verifique en la JVM con dobles de vibración/sonido/overlay.
 */
class RestAlerter(
    private val context: Context,
    private val notifier: RestDoneNotifier,
    private val vibrator: RestVibrator,
    private val sound: RestSoundPlayer,
    private val overlay: RestOverlayPresenter,
    private val fullScreenAllowed: () -> Boolean = {
        RestPermissions.fullScreenIntentGranted(context)
    },
    private val overlayAllowed: () -> Boolean = {
        RestPermissions.overlayGranted(context)
    },
) {

    /** El plan que corresponde a [settings] en este contexto (sin ejecutar nada). */
    fun plan(settings: AppSettings, inForeground: Boolean): RestAlertPlan =
        RestAlertPlans.forMode(
            mode = settings.alertMode,
            appInForeground = inForeground,
            fullScreenAllowed = fullScreenAllowed(),
            overlayAllowed = overlayAllowed(),
        )

    /** Avisa el fin del descanso. Devuelve lo que se hizo, para logs y tests. */
    fun alert(settings: AppSettings, inForeground: Boolean): RestAlertPlan {
        val plan = plan(settings, inForeground)
        // Primero la notificación: es la garantía de que el aviso no queda en silencio
        // aunque el resto falle.
        notifier.post(plan)
        fire(settings, plan)
        return plan
    }

    /**
     * Repetición de `restAlertRepeat`: sólo vibra y suena. La notificación y la pantalla
     * ya se mostraron en [alert]; volver a abrirlas cada N segundos sería un secuestro.
     */
    fun repeat(settings: AppSettings) {
        fire(settings, plan(settings, inForeground = true))
    }

    private fun fire(settings: AppSettings, plan: RestAlertPlan) {
        if (plan.vibrate) vibrator.vibrate(settings.restVibration)
        if (plan.sound) sound.play(settings.restSound)
        if (plan.screen == RestScreenDelivery.OVERLAY) {
            overlay.show(context.getString(app.gimcheck.R.string.rest_overlay_texto)) {
                RestDoneActivity.open(context)
            }
        }
    }
}
