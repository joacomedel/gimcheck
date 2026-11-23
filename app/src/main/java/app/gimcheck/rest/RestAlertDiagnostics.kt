package app.gimcheck.rest

import android.content.Context

/**
 * Lo que el sistema dijo en el momento de un aviso.
 *
 * Se guarda porque el diagnóstico se lee después, a veces desde un proceso nuevo
 * (la alarma despertó la app): persistir el estado del disparo es lo único que permite
 * saber si el aviso salió con la app abierta o cerrada, y qué camino tomó.
 */
data class AlertFire(
    val inForeground: Boolean,
    val deviceInteractive: Boolean?,
    val keyguardLocked: Boolean?,
    val delivery: RestScreenDelivery?,
)

/**
 * Diagnóstico de alertas (GIM-43/GIM-44): todo leído del sistema, nada asumido.
 *
 * - [fullScreenIntent]: `canUseFullScreenIntent()` (14+).
 * - [exactAlarms]: `canScheduleExactAlarms()` (12+).
 * - [notifications]: `POST_NOTIFICATIONS` (13+) o notificaciones habilitadas.
 * - [overlay]: `Settings.canDrawOverlays()`; **es el que decide si se puede interponer
 *   sobre otra app** con el teléfono desbloqueado. Si dice "no", el aviso sólo puede
 *   ser heads-up en ese escenario.
 * - [channelImportance]: la importancia **efectiva** del canal, no la pedida.
 * - [fire]: cómo estaba el teléfono y qué camino se usó en el último aviso (`null` =
 *   todavía no hubo ninguno desde esta instalación).
 */
data class AlertDiagnosis(
    val fullScreenIntent: Boolean,
    val exactAlarms: Boolean,
    val notifications: Boolean,
    val overlay: Boolean,
    val channelId: String,
    val channelImportance: Int?,
    val fire: AlertFire?,
) {

    /** Atajo de compatibilidad: "app en primer plano al disparar" (`null` = sin datos). */
    val inForegroundAtFire: Boolean?
        get() = fire?.inForeground

    /** Último camino usado, para el bloque de diagnóstico. */
    val lastDelivery: RestScreenDelivery?
        get() = fire?.delivery
}

/**
 * Lectura y memoria del diagnóstico de alertas (GIM-43).
 *
 * [collect] no inventa nada: cada campo sale de [RestPermissions] (que a su vez consulta
 * `NotificationManager`, `AlarmManager`, `AppOpsManager` y `checkSelfPermission`) o del
 * canal real. Los parámetros con default existen sólo para que los tests puedan fijar un
 * valor sin depender de los shadows de Robolectric.
 *
 * [recordFire] corre en cada aviso (lo llama [RestAlertCenter]) y deja en
 * `SharedPreferences` cómo estaba el teléfono y qué capa se usó; la pantalla "Descanso
 * terminado" y Ajustes lo leen después, incluso si el aviso despertó un proceso nuevo.
 */
object RestAlertDiagnostics {

    private const val PREFS: String = "rest_alert_diagnostics"
    private const val KEY_IN_FOREGROUND: String = "last_fire_in_foreground"
    private const val KEY_DELIVERY: String = "last_fire_delivery"
    private const val KEY_INTERACTIVE: String = "last_fire_interactive"
    private const val KEY_KEYGUARD: String = "last_fire_keyguard"

    fun collect(
        context: Context,
        fullScreenIntent: Boolean = RestPermissions.fullScreenIntentGranted(context),
        exactAlarms: Boolean = RestPermissions.exactAlarmsGranted(context),
        notifications: Boolean = RestPermissions.notificationsGranted(context),
        overlay: Boolean = RestPermissions.overlayGranted(context),
        channelImportance: Int? = channelImportance(context),
        fire: AlertFire? = lastFire(context),
    ): AlertDiagnosis = AlertDiagnosis(
        fullScreenIntent = fullScreenIntent,
        exactAlarms = exactAlarms,
        notifications = notifications,
        overlay = overlay,
        channelId = RestNotifications.CHANNEL_REST_DONE,
        channelImportance = channelImportance,
        fire = fire,
    )

    /** Importancia efectiva del canal de fin de descanso; `null` si no existe. */
    fun channelImportance(
        context: Context,
        channelId: String = RestNotifications.CHANNEL_REST_DONE,
    ): Int? = context.getSystemService(android.app.NotificationManager::class.java)
        ?.getNotificationChannel(channelId)
        ?.importance

    /** Anota cómo se disparó el último aviso (visibilidad, pantalla y camino elegido). */
    fun recordFire(
        context: Context,
        inForeground: Boolean,
        delivery: RestScreenDelivery? = null,
        deviceInteractive: Boolean? = null,
        keyguardLocked: Boolean? = null,
    ) {
        try {
            val editor = prefs(context).edit().putBoolean(KEY_IN_FOREGROUND, inForeground)
            if (delivery != null) editor.putString(KEY_DELIVERY, delivery.name)
            if (deviceInteractive != null) editor.putBoolean(KEY_INTERACTIVE, deviceInteractive)
            if (keyguardLocked != null) editor.putBoolean(KEY_KEYGUARD, keyguardLocked)
            editor.apply()
        } catch (e: RuntimeException) {
            // Sin diagnóstico persistido queda `null`: la pantalla lo muestra como "—".
        }
    }

    /** `null` si esta versión todavía no disparó ningún aviso. */
    fun lastFire(context: Context): AlertFire? = try {
        val stored = prefs(context)
        if (!stored.contains(KEY_IN_FOREGROUND)) {
            null
        } else {
            AlertFire(
                inForeground = stored.getBoolean(KEY_IN_FOREGROUND, false),
                deviceInteractive = stored.booleanOrNull(KEY_INTERACTIVE),
                keyguardLocked = stored.booleanOrNull(KEY_KEYGUARD),
                delivery = stored.getString(KEY_DELIVERY, null)
                    ?.let { runCatching { RestScreenDelivery.valueOf(it) }.getOrNull() },
            )
        }
    } catch (e: RuntimeException) {
        null
    }

    /** Compatibilidad con el primer diagnóstico: sólo "¿estaba en primer plano?". */
    fun lastFireInForeground(context: Context): Boolean? = lastFire(context)?.inForeground

    /** Borra el último aviso recordado (prueba de un toque y tests instrumentados). */
    fun clear(context: Context) {
        try {
            prefs(context).edit().clear().apply()
        } catch (e: RuntimeException) {
            // Sin preferencias no hay nada que borrar.
        }
    }

    private fun android.content.SharedPreferences.booleanOrNull(key: String): Boolean? =
        if (contains(key)) getBoolean(key, false) else null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
