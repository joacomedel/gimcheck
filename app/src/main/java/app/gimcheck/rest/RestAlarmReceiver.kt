package app.gimcheck.rest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Disparo de la alarma exacta de respaldo (ADR 0004): si el proceso estaba muerto,
 * Android lo despierta para entregar este broadcast y sale el aviso de "descanso
 * terminado" con el `alertMode` configurado (GIM-23).
 *
 * No escribe la base ni toca la sesión: el `rest_ends_at` sigue en `session_state` y la
 * máquina (`Restore`/`Tick`) es la que cierra el descanso cuando la app vuelve. Acá sólo
 * se avisa, que es lo único que el sistema permite hacer en segundo plano.
 */
class RestAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != ACTION_REST_ALARM) return
        // Con la app cerrada `AppVisibility` ya está en `false`: pantalla completa (o su
        // respaldo) si el modo lo pide. Si está abierta, el aviso es inline en Entrenar.
        RestAlertCenter.restFinished(context.applicationContext)
    }

    companion object {
        const val ACTION_REST_ALARM: String = "app.gimcheck.rest.action.ALARM"

        /** Intent que viaja dentro del `PendingIntent` de [RestAlarmScheduler]. */
        fun intent(context: Context): Intent =
            Intent(context, RestAlarmReceiver::class.java).setAction(ACTION_REST_ALARM)
    }
}
