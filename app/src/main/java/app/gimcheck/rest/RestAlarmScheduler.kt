package app.gimcheck.rest

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context

/**
 * Alarma exacta de respaldo del descanso (ADR 0004).
 *
 * El cronómetro primario es el servicio en primer plano; esto es la red por si el
 * proceso muere (Android lo mata, un OEM "ahorra batería", el usuario detiene la app).
 * A la hora del deadline el `AlarmManager` despierta el proceso y [RestAlarmReceiver]
 * deja la notificación de "descanso terminado".
 *
 * **`SCHEDULE_EXACT_ALARM`, no `USE_EXACT_ALARM`.** La política de Google reserva
 * `USE_EXACT_ALARM` para reloj/alarma/calendario. Por eso primero se consulta
 * [RestPermissions.exactAlarmsGranted]; si no está concedido, [schedule] devuelve
 * `false` y **no pasa nada más**: el servicio en primer plano mantiene el cronómetro.
 *
 * @param canScheduleExact `null` = preguntarle al sistema. Los tests inyectan
 *   `true`/`false` para cubrir los dos caminos sin depender de la config del aparato.
 */
class RestAlarmScheduler(
    context: Context,
    private val canScheduleExact: Boolean? = null,
) {

    private val app: Context = context.applicationContext

    /**
     * Programa el aviso para [endsAtMillis] (epoch millis, el mismo `rest_ends_at`).
     * Devuelve `true` sólo si quedó programado de verdad.
     */
    @SuppressLint("ScheduleExactAlarm")
    fun schedule(endsAtMillis: Long): Boolean {
        if (endsAtMillis <= System.currentTimeMillis()) return false
        if (!canScheduleExactAlarms()) return false
        val manager = app.getSystemService(AlarmManager::class.java) ?: return false
        return try {
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                endsAtMillis,
                operation(),
            )
            true
        } catch (e: SecurityException) {
            // El permiso se puede revocar entre el chequeo y la llamada.
            false
        }
    }

    /** Cancela la alarma pendiente. Idempotente: sin alarma programada no hace nada. */
    fun cancel() {
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(operation())
    }

    /** El estado real del permiso, para la explicación en contexto y para [schedule]. */
    fun canScheduleExactAlarms(): Boolean =
        canScheduleExact ?: RestPermissions.exactAlarmsGranted(app)

    /** Mismo `PendingIntent` para programar y cancelar: si no, quedan alarmas huérfanas. */
    fun operation(): PendingIntent = PendingIntent.getBroadcast(
        app,
        REQUEST_CODE,
        RestAlarmReceiver.intent(app),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val REQUEST_CODE: Int = 2042
    }
}
