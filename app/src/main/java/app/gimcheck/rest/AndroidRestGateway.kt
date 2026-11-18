package app.gimcheck.rest

import android.content.Context
import app.gimcheck.ui.screens.RestGateway
import java.time.Instant

/**
 * Arranque y parada del servicio, detrás de una interfaz: los tests del gateway
 * verifican **qué deadline** se manda sin depender del ciclo de vida real de Android.
 */
interface RestTimerStarter {

    fun start(context: Context, endsAtMillis: Long, totalSeconds: Int)

    fun stop(context: Context)

    /** El de producción: `startForegroundService` + `stopService`. */
    object Android : RestTimerStarter {
        override fun start(context: Context, endsAtMillis: Long, totalSeconds: Int) =
            RestTimerService.start(context, endsAtMillis, totalSeconds)

        override fun stop(context: Context) = RestTimerService.stop(context)
    }
}

/**
 * Implementación real del [RestGateway] que declara `EntrenarViewModel` (GIM-22).
 *
 * Es el único punto donde la máquina de `:core` se traduce en algo que el sistema
 * operativo entiende:
 *
 * | Efecto de `:core` | Qué pasa acá |
 * |---|---|
 * | `StartRest` (empezar, extender, `Restore` al abrir) | arranca/actualiza el servicio con el deadline y programa la alarma exacta |
 * | `CancelRest` (saltar, anotar durante el descanso, terminar, abandonar) | para el servicio y cancela la alarma |
 * | `RestFinished` (`Tick` o `Restore` vencido) | igual que cancelar: no queda nada colgado |
 *
 * El **deadline** viene del estado (`restEndsAt`), no de un contador: el servicio y la
 * alarma reciben el mismo `Instant` y la pantalla lo lee de la base. Una sola fuente.
 *
 * Que el permiso de alarmas exactas no esté concedido no cambia el camino: el servicio
 * en primer plano sigue siendo el cronómetro (`schedule` devuelve `false` y listo).
 */
class AndroidRestGateway(
    context: Context,
    private val alarm: RestAlarmScheduler = RestAlarmScheduler(context.applicationContext),
    private val timer: RestTimerStarter = RestTimerStarter.Android,
) : RestGateway {

    private val app: Context = context.applicationContext

    override fun onStartRest(seconds: Int, endsAt: Instant) {
        val end = endsAt.toEpochMilli()
        timer.start(app, end, seconds)
        alarm.schedule(end)
    }

    override fun onCancelRest() = stopAll()

    override fun onRestFinished() {
        stopAll()
        // El descanso llegó a cero con la app abierta: aviso inline en Entrenar, más la
        // señal de `alertMode` (§5). `AppVisibility` decide; acá no se roba la pantalla.
        RestAlertCenter.restFinished(app)
    }

    private fun stopAll() {
        timer.stop(app)
        alarm.cancel()
    }
}
