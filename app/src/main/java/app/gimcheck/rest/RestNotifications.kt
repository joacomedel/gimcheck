package app.gimcheck.rest

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.gimcheck.MainActivity
import app.gimcheck.R
import app.gimcheck.ui.components.formatRestClock

/**
 * Las dos notificaciones del cronómetro de descanso (GIM-22, ADR 0004; el aviso de fin
 * es GIM-23):
 *
 * - **En curso** (canal `rest_timer`, importancia `LOW`): la del servicio en primer
 *   plano. Muestra cuánto falta con el mismo formato `m:ss` de §4 y no suena.
 * - **Terminado** (canal `rest_done_v2`, importancia `HIGH`): la del aviso de fin de
 *   descanso (`docs/especificacion.md` §5). Es **la que nunca puede faltar**: vibración,
 *   sonido, overlay y full-screen intent son formas de llamar la atención, pero la
 *   notificación se publica siempre, incluso con `alertMode = NONE`.
 *
 * **Sin `POST_NOTIFICATIONS` no se rompe nada**: se descarta el aviso y el cronómetro
 * sigue (el servicio vive y la pantalla sigue contando desde `rest_ends_at`). Es la
 * decisión del ADR: el permiso mejora el aviso, no habilita la función.
 */
internal object RestNotifications {

    const val CHANNEL_REST: String = "rest_timer"

    /**
     * Canal vigente del fin de descanso (GIM-43).
     *
     * Lleva `_v2` **a propósito**: las settings de un canal son inmutables. El APK de
     * GIM-22 ya creó `rest_done` en el teléfono del dueño y Android conservó esa
     * configuración al reinstalar; con el id viejo no hay forma de subir la importancia
     * ni recuperar el heads-up (y el full-screen intent no se dispara). Un id nuevo es
     * el único camino para que el sistema aplique `IMPORTANCE_HIGH` de cero.
     */
    const val CHANNEL_REST_DONE: String = "rest_done_v2"

    /** Canal del primer APK (GIM-22). Queda fuera de uso y se borra al crear los demás. */
    const val LEGACY_CHANNEL_REST_DONE: String = "rest_done"

    /**
     * Crea los canales. Idempotente (`createNotificationChannel` sólo actualiza) y
     * llamado antes de cada `startForeground`: en Android 8+ la notificación no se
     * muestra si el canal todavía no existe.
     *
     * El canal de fin de descanso es **silencioso a propósito**: el tono lo pone
     * [RestSoundPlayer] con la opción de `restSound`, así `alertMode = SCREEN` (sin
     * sonido) no suena por culpa del canal. El patrón de vibración también lo da el
     * `Vibrator` (`restVibration`, configurable), no el canal. La importancia `HIGH` es
     * la que habilita el heads-up y el full-screen intent.
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
            setShowBadge(true)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannels(listOf(rest, done))
        // El canal viejo no se puede reconfigurar (importancia, sonido y visibilidad se
        // fijan en la primera creación), así que se borra: si queda, el usuario ve dos
        // "Fin de descanso" en Ajustes y el viejo sigue gobernando notificaciones que el
        // sistema haya guardado con ese id.
        try {
            manager.deleteNotificationChannel(LEGACY_CHANNEL_REST_DONE)
        } catch (e: RuntimeException) {
            // Un OEM puede negarse: no rompe nada, el canal vigente es el `_v2`.
        }
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

    /**
     * "Descanso terminado": la deja [RestAlerter] siempre, con o sin alerta.
     *
     * Va como **alarma** (`CATEGORY_ALARM` + `PRIORITY_MAX` + `VISIBILITY_PUBLIC`): es lo
     * que hace que Android la trate como aviso urgente y no la agrupe ni la silencie con
     * otras notificaciones de la app. En Android 14+ el *full-screen intent* además exige
     * que el canal sea de importancia alta (lo es: [CHANNEL_REST_DONE]).
     *
     * @param fullScreen `true` agrega el *full-screen intent* a [RestDoneActivity]. Sólo
     *   se pide cuando el plan resolvió `FULL_SCREEN_INTENT` **y** el sistema concedió
     *   `USE_FULL_SCREEN_INTENT`: un FSI denegado se ignora en silencio y el aviso
     *   quedaría sin pantalla. Si el sistema igual lo niega, queda el heads-up.
     */
    fun postRestDone(context: Context, fullScreen: Boolean) {
        val builder = NotificationCompat.Builder(context, CHANNEL_REST_DONE)
            .setSmallIcon(R.drawable.ic_rest)
            .setContentTitle(context.getString(R.string.entrenar_descanso_terminado))
            .setContentText(context.getString(R.string.rest_notif_fin_texto))
            .setContentIntent(openApp(context))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
        if (fullScreen) builder.setFullScreenIntent(restDone(context), true)
        post(context, builder.build())
    }

    /** El usuario interactuó (abrió la app o la pantalla de descanso): se retira. */
    fun cancelRestDone(context: Context) {
        NotificationManagerCompat.from(context).cancel(RestTimerService.NOTIFICATION_ID)
    }

    /**
     * `true` si el canal de fin de descanso quedó sin tono (el usuario lo silenció o lo
     * bajó a "sin importancia"), o si todavía no existe. Ahí `restSound = DEFAULT` tiene
     * que sonar por `RingtoneManager`: el aviso no puede quedar mudo por una
     * configuración de canal.
     */
    fun doneChannelIsSilent(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        val channel = manager.getNotificationChannel(CHANNEL_REST_DONE) ?: return true
        return channel.sound == null || channel.importance == NotificationManager.IMPORTANCE_NONE
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

    /** Pantalla completa de "descanso terminado" (§5, §6 #3). */
    private fun restDone(context: Context): PendingIntent {
        val intent = Intent(context, RestDoneActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            REQUEST_REST_DONE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val REQUEST_OPEN_APP: Int = 2040
    private const val REQUEST_REST_DONE: Int = 2043
}
