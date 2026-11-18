package app.gimcheck.rest

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
}
