package app.gimcheck.rest

import android.app.NotificationManager
import android.content.Context

/**
 * Lo que el sistema dice **ahora** sobre el aviso de fin de descanso (GIM-43).
 *
 * Existe porque no hay emulador ni logs: el dueño mira este bloque en el teléfono y con
 * eso sabemos si el problema es un permiso negado, el canal degradado o que el aviso se
 * disparó con la app en primer plano (ahí el FSI no corresponde por diseño).
 *
 * Todo es un valor leído del sistema en el momento de [RestAlertDiagnostics.collect];
 * nada se asume del manifest ni de los ajustes de la app.
 */
data class AlertDiagnosis(
    /** `canUseFullScreenIntent()`: el permiso `USE_FULL_SCREEN_INTENT` (14+). */
    val fullScreenIntent: Boolean,
    /** `canScheduleExactAlarms()`: la alarma de respaldo (12+). */
    val exactAlarms: Boolean,
    /** `POST_NOTIFICATIONS` (13+) o notificaciones habilitadas en versiones previas. */
    val notifications: Boolean,
    /** Id del canal que usa la notificación de fin de descanso. */
    val channelId: String,
    /**
     * Importancia **efectiva** del canal (`NotificationManager.getNotificationChannel`),
     * no la que pidió la app: si el usuario la bajó, acá se ve. `null` = el canal no
     * existe todavía (nadie lo creó en este arranque).
     */
    val channelImportance: Int?,
    /**
     * Si el último aviso se disparó con la app en primer plano. `null` = todavía no hubo
     * ningún aviso desde que se instaló esta versión. Con `true`, que la pantalla no se
     * haya abierto es lo correcto: el aviso es inline en Entrenar.
     */
    val inForegroundAtFire: Boolean?,
)

/**
 * Lectura y memoria del diagnóstico de alertas (GIM-43).
 *
 * [collect] no inventa nada: cada campo sale de [RestPermissions] (que a su vez consulta
 * `NotificationManager`, `AlarmManager` y `checkSelfPermission`) o del canal real. Los
 * parámetros con default existen sólo para que los tests puedan fijar un valor sin
 * depender de los shadows de Robolectric.
 *
 * [recordFire] corre en cada aviso (lo llama [RestAlertCenter]) y deja en
 * `SharedPreferences` si la app estaba en primer plano; la pantalla "Descanso terminado"
 * lo lee después, incluso si el aviso despertó un proceso nuevo.
 */
object RestAlertDiagnostics {

    private const val PREFS: String = "rest_alert_diagnostics"
    private const val KEY_IN_FOREGROUND: String = "last_fire_in_foreground"

    fun collect(
        context: Context,
        fullScreenIntent: Boolean = RestPermissions.fullScreenIntentGranted(context),
        exactAlarms: Boolean = RestPermissions.exactAlarmsGranted(context),
        notifications: Boolean = RestPermissions.notificationsGranted(context),
        channelImportance: Int? = channelImportance(context),
        inForegroundAtFire: Boolean? = lastFireInForeground(context),
    ): AlertDiagnosis = AlertDiagnosis(
        fullScreenIntent = fullScreenIntent,
        exactAlarms = exactAlarms,
        notifications = notifications,
        channelId = RestNotifications.CHANNEL_REST_DONE,
        channelImportance = channelImportance,
        inForegroundAtFire = inForegroundAtFire,
    )

    /** Importancia efectiva del canal de fin de descanso; `null` si no existe. */
    fun channelImportance(
        context: Context,
        channelId: String = RestNotifications.CHANNEL_REST_DONE,
    ): Int? = context.getSystemService(NotificationManager::class.java)
        ?.getNotificationChannel(channelId)
        ?.importance

    /** Anota con qué visibilidad se disparó el último aviso. */
    fun recordFire(context: Context, inForeground: Boolean) {
        try {
            prefs(context).edit().putBoolean(KEY_IN_FOREGROUND, inForeground).apply()
        } catch (e: RuntimeException) {
            // Sin diagnóstico persistido queda `null`: la pantalla lo muestra como "—".
        }
    }

    /** `null` si esta versión todavía no disparó ningún aviso. */
    fun lastFireInForeground(context: Context): Boolean? = try {
        val stored = prefs(context)
        if (stored.contains(KEY_IN_FOREGROUND)) {
            stored.getBoolean(KEY_IN_FOREGROUND, false)
        } else {
            null
        }
    } catch (e: RuntimeException) {
        null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
