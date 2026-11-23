package app.gimcheck.rest

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Estado y pedido de los permisos del cronómetro de descanso (GIM-22).
 *
 * Los dos son **mejoras, no requisitos**: si el usuario niega uno, el descanso sigue
 * funcionando con lo que quede (servicio en primer plano y/o notificación). Por eso acá
 * sólo se consulta y se abre la pantalla del sistema; la decisión de cuándo pedir vive
 * en el flujo (al empezar la primera sesión) y no al abrir la app.
 */
object RestPermissions {

    /** `POST_NOTIFICATIONS` en 13+; en versiones anteriores, si el usuario las apagó. */
    fun notificationsGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    /** `canScheduleExactAlarms()`; antes de Android 12 no existe el permiso y es `true`. */
    fun exactAlarmsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return manager.canScheduleExactAlarms()
    }

    /**
     * Las alarmas exactas no se piden con un diálogo: se concede en una pantalla de
     * Ajustes, así que se abre con `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`.
     */
    fun requestExactAlarms(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Algunos OEM no traen esa pantalla: se sigue con el servicio en primer plano.
        }
    }

    /**
     * `USE_FULL_SCREEN_INTENT` (API 34+): Android sólo lo concede de fábrica a apps de
     * llamadas o alarma. Antes de 34 no existe el permiso efectivo y vale `true`.
     *
     * Si está negado, el aviso cae a overlay y después a heads-up (§5): la pantalla
     * completa es una mejora, no un requisito.
     */
    fun fullScreenIntentGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canUseFullScreenIntent()
    }

    /** Abre la pantalla del sistema para conceder el full-screen intent (API 34+). */
    fun requestFullScreenIntent(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Sin esa pantalla queda el overlay o el heads-up.
        }
    }

    /**
     * `SYSTEM_ALERT_WINDOW`: **la única vía que aparece encima de otra app** con el
     * teléfono desbloqueado (GIM-44). El FSI no sirve en ese escenario: Android lo
     * degrada a heads-up. Por eso el permiso se pide explícitamente y no como opcional
     * silencioso.
     */
    fun overlayGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** `PowerManager.isInteractive()`: `false` = pantalla apagada. */
    fun deviceInteractive(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    /** `KeyguardManager.isKeyguardLocked()`: `true` = teléfono bloqueado. */
    fun keyguardLocked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false

    /**
     * Los cinco valores que deciden el camino del aviso, leídos del sistema ahora.
     *
     * La app en primer plano la aporta [AppVisibility] (el mismo estado que usan el
     * servicio y el receptor): acá no se inventa.
     */
    fun screenState(
        context: Context,
        appInForeground: Boolean = AppVisibility.isForeground,
    ): RestScreenState = RestScreenState(
        appInForeground = appInForeground,
        deviceInteractive = deviceInteractive(context),
        keyguardLocked = keyguardLocked(context),
        fullScreenAllowed = fullScreenIntentGranted(context),
        overlayAllowed = overlayGranted(context),
    )

    /** Pide "aparecer encima de otras apps"; se concede en una pantalla de Ajustes. */
    fun requestOverlay(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Sin esa pantalla, el overlay no existe y queda el heads-up.
        }
    }
}
