package app.gimcheck.rest

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * El cronómetro de descanso, fuera de la pantalla (GIM-22, `docs/especificacion.md` §4,
 * ADR 0004).
 *
 * **No cuenta hacia atrás en memoria**: recibe el deadline absoluto y en cada pulso
 * calcula `deadline − ahora`. Si el proceso muere y el servicio con él, la alarma exacta
 * avisa igual; al reabrir, el deadline persistido (`app_setting`, claves `training.*`)
 * reconstruye la cuenta con el mismo valor.
 *
 * **Quién lo arranca y lo para**: [AndroidRestGateway], que implementa el `RestGateway`
 * de `EntrenarViewModel`. La pantalla no lo toca: la única fuente es el deadline que
 * persiste el ViewModel.
 *
 * La notificación se reescribe cada 5 s (y cada segundo en los últimos 10) con
 * `setOnlyAlertOnce`, así no se despierta la CPU de más ni se alerta dos veces.
 */
class RestTimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private var endsAtMillis: Long = 0L
    private var totalSeconds: Int = 0

    /** Último restante que se escribió en la notificación (`-1` = todavía ninguno). */
    private var publishedRemaining: Int = -1

    private var ticking: Boolean = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking) return
            val remaining = remainingSeconds(endsAtMillis, System.currentTimeMillis())
            if (remaining <= 0) {
                finishRest()
                return
            }
            if (shouldPublish(remaining)) {
                publishedRemaining = remaining
                RestNotifications.post(this@RestTimerService, notification(remaining))
            }
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            shutdown(keepNotification = false)
            return START_NOT_STICKY
        }
        val end = intent.getLongExtra(EXTRA_ENDS_AT, 0L)
        if (end <= System.currentTimeMillis()) {
            // Deadline vencido o ilegible: no se arranca un cronómetro en el pasado.
            shutdown(keepNotification = false)
            return START_NOT_STICKY
        }
        endsAtMillis = end
        totalSeconds = intent.getIntExtra(EXTRA_TOTAL_SECONDS, 0).coerceAtLeast(0)
        publishedRemaining = -1
        startForegroundWith(notification(remainingSeconds(endsAtMillis, System.currentTimeMillis())))
        restartTicker()
        // Si el sistema mata el servicio, la alarma exacta es el respaldo: no se
        // re-entrega el intent (arrancaría un cronómetro con un deadline viejo).
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ticking = false
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    // --- ciclo del cronómetro ----------------------------------------------------

    private fun startForegroundWith(notification: android.app.Notification) {
        RestNotifications.ensureChannels(this)
        ServiceCompatStartForeground.start(this, notification)
    }

    private fun restartTicker() {
        ticking = true
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MILLIS)
    }

    /**
     * El descanso llegó a cero con el servicio vivo: se deja el aviso de "terminado" y
     * se apaga. El estado de la sesión lo cierra la máquina (`Tick`/`Restore`) cuando la
     * app vuelve; acá no se escribe la base.
     */
    private fun finishRest() {
        // El aviso configurable de GIM-23 (§5): notificación siempre, más pantalla,
        // vibración y sonido según `alertMode`. Con la app cerrada corresponde la
        // pantalla completa; con la app abierta, el aviso inline de Entrenar.
        RestAlertCenter.restFinished(this)
        RestAlarmScheduler(this).cancel()
        shutdown(keepNotification = true)
    }

    private fun shutdown(keepNotification: Boolean) {
        ticking = false
        handler.removeCallbacks(ticker)
        stopForeground(if (keepNotification) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(remaining: Int) =
        RestNotifications.buildRest(this, remaining.coerceAtLeast(0), totalSeconds)

    /** Cada 5 s, y cada segundo en el tramo final (ahí sí importa el segundo). */
    private fun shouldPublish(remaining: Int): Boolean =
        publishedRemaining < 0 ||
            publishedRemaining - remaining >= NOTIFICATION_STEP_SECONDS ||
            remaining <= FINAL_SECONDS

    companion object {

        const val ACTION_START: String = "app.gimcheck.rest.action.START"
        const val ACTION_STOP: String = "app.gimcheck.rest.action.STOP"
        const val EXTRA_ENDS_AT: String = "app.gimcheck.rest.extra.ENDS_AT"
        const val EXTRA_TOTAL_SECONDS: String = "app.gimcheck.rest.extra.TOTAL_SECONDS"

        /** Mismo id para el cronómetro y para "descanso terminado": una sola tarjeta. */
        const val NOTIFICATION_ID: Int = 2041

        const val TICK_MILLIS: Long = 1_000L
        const val NOTIFICATION_STEP_SECONDS: Int = 5
        const val FINAL_SECONDS: Int = 10

        /**
         * Segundos que faltan para [endsAtMillis] redondeando hacia arriba (el mismo
         * criterio que la pantalla en `EntrenarViewModel.restUi`): nunca negativo.
         */
        fun remainingSeconds(endsAtMillis: Long, nowMillis: Long): Int {
            val millis = endsAtMillis - nowMillis
            if (millis <= 0L) return 0
            return ((millis + 999L) / 1000L).toInt()
        }

        /** Intent con el deadline: es lo que el test verifica que viaje completo. */
        fun startIntent(context: Context, endsAtMillis: Long, totalSeconds: Int): Intent =
            Intent(context, RestTimerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ENDS_AT, endsAtMillis)
                .putExtra(EXTRA_TOTAL_SECONDS, totalSeconds)

        /**
         * Arranca (o reprograma) el cronómetro. Los errores de Android 12+ al arrancar
         * un servicio en primer plano desde segundo plano no se propagan: el deadline
         * ya está persistido y la alarma exacta sigue siendo el respaldo.
         */
        fun start(context: Context, endsAtMillis: Long, totalSeconds: Int) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    startIntent(context, endsAtMillis, totalSeconds),
                )
            } catch (e: RuntimeException) {
                // Ver el comentario de arriba: la alarma cubre este caso.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RestTimerService::class.java))
        }
    }
}

/**
 * `startForeground` con el tipo `specialUse` sólo existe desde API 34; en versiones
 * anteriores el tipo se ignora. Se aísla acá para no ensuciar el servicio con el
 * `if` de versión y para que el manifiesto y esta llamada no se puedan desalinear.
 */
private object ServiceCompatStartForeground {

    fun start(service: Service, notification: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        androidx.core.app.ServiceCompat.startForeground(
            service,
            RestTimerService.NOTIFICATION_ID,
            notification,
            type,
        )
    }
}
