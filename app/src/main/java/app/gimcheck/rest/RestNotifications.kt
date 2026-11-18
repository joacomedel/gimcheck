package app.gimcheck.rest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.annotation.SuppressLint
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.gimcheck.MainActivity
import app.gimcheck.R
import app.gimcheck.ui.components.formatRestClock

/**
 * Las dos notificaciones del cronómetro de descanso (GIM-22, ADR 0004):
 *
 * - **En curso** (canal `rest_timer`, importancia `LOW`): la del servicio en primer
 *   plano. Muestra cuánto falta con el mismo formato `m:ss` de §4 y no suena.
 * - **Terminado** (canal `rest_done`, importancia `HIGH`): la que deja el receptor de
 *   la alarma exacta cuando el proceso ya no está. La alerta configurable de GIM-23
 *   (pantalla completa, vibración, sonido) se cuelga de este canal.
 *
 * **Sin `POST_NOTIFICATIONS` no se rompe nada**: se descarta el aviso y el cronómetro
 * sigue (el servicio vive y la pantalla sigue contando desde `rest_ends_at`). Es la
 * decisión del ADR: el permiso mejora el aviso, no habilita la función.
 */
internal object RestNotifications {

    const val CHANNEL_REST: String = "rest_timer"
    const val CHANNEL_REST_DONE: String = "rest_done"

    /**
     * Crea los canales. Idempotente (`createNotificationChannel` sólo actualiza) y
     * llamado antes de cada `startForeground`: en Android 8+ la notificación no se
     * muestra si el canal todavía no existe.
     */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val rest = NotificationChannel(
            CHANNEL_REST,
            context.getString(R.string.rest_notif_canal),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.rest_notif_canal_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        val done = NotificationChannel(
            CHANNEL_REST_DONE,
            context.getString(R.string.rest_notif_canal_fin),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.rest_notif_canal_fin_desc)
        }
        manager.createNotificationChannels(listOf(rest, done))
    }

    /**
     * Notificación del servicio en primer plano. [remainingSeconds] se recalcula desde
     * el deadline (el servicio la reescribe cada 5 s): la notificación nunca es la
     * fuente del tiempo, sólo una vista de `rest_ends_at`.
     */
    fun buildRest(
        context: Context,
        remainingSeconds: Int,
        totalSeconds: Int,
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_REST)
            .setSmallIcon(R.drawable.ic_rest)
            .setContentTitle(context.getString(R.string.rest_notif_titulo))
            .setContentText(
                context.getString(R.string.rest_notif_texto, formatRestClock(remainingSeconds)),
            )
            .setContentIntent(openApp(context))
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (totalSeconds > 0) {
            builder.setProgress(
                totalSeconds,
                remainingSeconds.coerceIn(0, totalSeconds),
                false,
            )
        }
        return builder.build()
    }

    /** "Descanso terminado": la deja el receptor de la alarma (o el servicio al llegar a cero). */
    fun showRestDone(context: Context) {
        val notification = NotificationCompat.Builder(context, CHANNEL_REST_DONE)
            .setSmallIcon(R.drawable.ic_rest)
            .setContentTitle(context.getString(R.string.entrenar_descanso_terminado))
            .setContentText(context.getString(R.string.rest_notif_fin_texto))
            .setContentIntent(openApp(context))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        post(context, notification)
    }

    /**
     * Publica con el mismo id que la notificación en primer plano: la de "terminado"
     * reemplaza a la del cronómetro en vez de apilarse.
     *
     * El chequeo de `POST_NOTIFICATIONS` va **en este método** (y no en un helper) porque
     * es lo que el lint de Android mira para no marcar el `notify` como error: la misma
     * condición está en [RestPermissions.notificationsGranted], para la UI.
     */
    @SuppressLint("MissingPermission", "NotificationPermission")
    fun post(context: Context, notification: Notification) {
        ensureChannels(context)
        val allowed =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        if (!allowed) return
        try {
            NotificationManagerCompat.from(context)
                .notify(RestTimerService.NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // El sistema puede negarse en cualquier momento (permiso revocado): el
            // cronómetro sigue, sólo se pierde el aviso.
        }
    }

    /** Tocar la notificación abre la app en Entrenar (la sesión sigue viva). */
    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val REQUEST_OPEN_APP: Int = 2040
}
