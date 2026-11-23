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
 *
 * El estado del teléfono ([RestScreenState]) se congela **al entrar** y es el mismo que
 * usa el diagnóstico persistido: así el bloque de Ajustes dice exactamente por qué se
 * eligió overlay, FSI o heads-up, y no una lectura posterior que puede haber cambiado.
 */
object RestAlertCenter {

    /** Ventana del antirrebote: servicio, pantalla y alarma en el mismo segundo. */
    const val DEDUPE_MILLIS: Long = 3_000L

    /** Segundos de la "prueba de un toque" de Ajustes (GIM-44). */
    const val SELF_TEST_SECONDS: Int = 10

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val repeater = RestAlertRepeater(MainThreadRestAlertScheduler())

    /** El aviso y la vibración se disparan en el hilo principal (UI/system services). */
    private val main = Handler(Looper.getMainLooper())

    /** `SystemClock.elapsedRealtime()` del último aviso; `0` = todavía ninguno. */
    @Volatile
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
        val state = RestPermissions.screenState(app, inForeground)
        scope.launch {
            val settings = readSettings(app)
            main.post { fire(app, settings, state) }
        }
    }

    /**
     * "Probar el aviso" de Ajustes (GIM-44): programa el aviso real para dentro de
     * [seconds] segundos por el mismo camino que el descanso (alarma exacta →
     * [RestAlarmReceiver] → este objeto). Sirve para que el dueño lo dispare, salga de
     * la app o bloquee el teléfono, y vea en 10 s lo que va a ver entrenando.
     *
     * @return `true` si quedó programado. Sin permiso de alarmas exactas usa
     *   `setAndAllowWhileIdle` (inexacta): la prueba puede tardar hasta un minuto.
     */
    fun scheduleSelfTest(
        context: Context,
        seconds: Int = SELF_TEST_SECONDS,
    ): Boolean {
        val endsAt = System.currentTimeMillis() + seconds.coerceAtLeast(1) * 1_000L
        // La prueba es "como si el descanso terminara": no hay cronómetro que cancelar.
        return RestAlarmScheduler(context).scheduleSelfTest(endsAt)
    }

    /**
     * El usuario interactuó (abrió la app o la pantalla de descanso): se corta la
     * repetición, se retira el overlay y la notificación. Sin aviso pendiente no hace
     * nada — no se toca la notificación del cronómetro, que comparte id.
     */
    fun dismiss(context: Context) {
        val app = context.applicationContext
        repeater.stop()
        SystemRestOverlay.hideAny(app)
        if (!posted) return
        posted = false
        RestNotifications.cancelRestDone(app)
    }

    private fun fire(context: Context, settings: AppSettings, state: RestScreenState) {
        posted = true
        val alerter = RestAlerter(
            context = context,
            notifier = SystemRestDoneNotifier(context),
            vibrator = SystemRestVibrator.create(context),
            sound = SystemRestSoundPlayer(context),
            overlay = SystemRestOverlay(context),
        )
        val plan = alerter.alert(settings, state)
        // Lo que el dueño va a leer en el diagnóstico (GIM-43/44): con `true`, que no se
        // abra la pantalla completa es lo correcto (el aviso es inline); con overlay,
        // "último aviso: overlay"; y si estaba desbloqueado, el FSI no era una opción.
        RestAlertDiagnostics.recordFire(
            context = context,
            inForeground = state.appInForeground,
            delivery = plan.screen,
            deviceInteractive = state.deviceInteractive,
            keyguardLocked = state.keyguardLocked,
        )
        // Repetir sólo si hay algo que repetir y la app no está mostrando el aviso
        // inline: ahí el usuario ya lo ve y repetir sería ruido.
        val repeat = settings.restAlertRepeat
        if (!state.appInForeground && repeat > 0 && plan.hasSignal) {
            repeater.start(repeat) { alerter.repeat(settings) }
        }
    }

    private suspend fun readSettings(context: Context): AppSettings = try {
        GimCheckApp.container(context as Application).settings.current()
    } catch (error: Throwable) {
        AppSettings.DEFAULT
    }

    /**
     * Vuelve al estado inicial para un test: sin antirrebote pendiente, sin overlay y
     * sin notificación recordada. El antirrebote es estático porque el servicio y la
     * alarma comparten proceso; los tests instrumentados lo necesitan entre casos.
     */
    internal fun resetForTest(context: Context) {
        lastAlertAt = SystemClock.elapsedRealtime() - DEDUPE_MILLIS - 1
        posted = false
        repeater.stop()
        SystemRestOverlay.hideAny(context.applicationContext)
        RestNotifications.cancelRestDone(context.applicationContext)
        RestAlertDiagnostics.clear(context.applicationContext)
    }
}
