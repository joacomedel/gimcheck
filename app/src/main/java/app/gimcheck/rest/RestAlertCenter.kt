package app.gimcheck.rest

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.gimcheck.GimCheckApp
import app.gimcheck.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * El único punto por el que pasa el aviso de fin de descanso (GIM-23).
 *
 * Tres caminos llegan acá y todos tienen que producir **el mismo** aviso:
 *
 * | Origen | Cuándo |
 * |---|---|
 * | `EntrenarViewModel` → [AndroidRestGateway.onRestFinished] | la app está abierta y el `Tick` dio el descanso por terminado |
 * | [RestTimerService] | la app está cerrada pero el servicio sobrevivió |
 * | [RestAlarmReceiver] | el proceso estaba muerto y la alarma exacta lo despertó |
 *
 * Dos de esos caminos pueden coincidir en el mismo segundo (servicio y pantalla), de ahí
 * el antirrebote de [DEDUPE_MILLIS]: un solo aviso por fin de descanso.
 *
 * Los ajustes se leen de la base en segundo plano. Si la lectura falla se usa
 * [AppSettings.DEFAULT] (`SCREEN_VIBRATE`): **el aviso sale igual**, que es la regla de
 * §5 — nunca en silencio.
 */
object RestAlertCenter {

    /** Ventana del antirrebote: servicio, pantalla y alarma en el mismo segundo. */
    const val DEDUPE_MILLIS: Long = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val repeater = RestAlertRepeater(MainThreadRestAlertScheduler())

    /** El aviso y la vibración se disparan en el hilo principal (UI/system services). */
    private val main = Handler(Looper.getMainLooper())

    /** `SystemClock.elapsedRealtime()` del último aviso; `0` = todavía ninguno. */
    private var lastAlertAt: Long = 0L

    /** `true` mientras haya una notificación de "descanso terminado" nuestra en la sombra. */
    @Volatile
    private var posted: Boolean = false

    /**
     * Avisa que el descanso llegó a cero.
     *
     * @param inForeground por defecto, lo que diga [AppVisibility]: el llamador sólo lo
     *   pasa distinto en tests.
     */
    fun restFinished(
        context: Context,
        inForeground: Boolean = AppVisibility.isForeground,
    ) {
        val app = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        // Antirrebote: servicio, pantalla y alarma pueden coincidir en el mismo segundo.
        if (now - lastAlertAt < DEDUPE_MILLIS) return
        lastAlertAt = now
        scope.launch {
            val settings = readSettings(app)
            main.post { fire(app, settings, inForeground) }
        }
    }

    /**
     * El usuario interactuó (abrió la app o la pantalla de descanso): se corta la
     * repetición y se retira la notificación. Sin aviso pendiente no hace nada — no se
     * toca la notificación del cronómetro, que comparte id.
     */
    fun dismiss(context: Context) {
        repeater.stop()
        if (!posted) return
        posted = false
        RestNotifications.cancelRestDone(context.applicationContext)
    }

    private fun fire(context: Context, settings: AppSettings, inForeground: Boolean) {
        posted = true
        val alerter = RestAlerter(
            context = context,
            notifier = SystemRestDoneNotifier(context),
            vibrator = SystemRestVibrator.create(context),
            sound = SystemRestSoundPlayer(context),
            overlay = SystemRestOverlay(context),
        )
        val plan = alerter.alert(settings, inForeground)
        // Repetir sólo si hay algo que repetir y la app no está mostrando el aviso
        // inline: ahí el usuario ya lo ve y repetir sería ruido.
        val repeat = settings.restAlertRepeat
        if (!inForeground && repeat > 0 && plan.hasSignal) {
            repeater.start(repeat) { alerter.repeat(settings) }
        }
    }

    private suspend fun readSettings(context: Context): AppSettings = try {
        GimCheckApp.container(context as Application).settings.current()
    } catch (error: Throwable) {
        AppSettings.DEFAULT
    }
}
