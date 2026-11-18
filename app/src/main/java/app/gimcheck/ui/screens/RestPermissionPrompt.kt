package app.gimcheck.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gimcheck.R
import app.gimcheck.rest.RestPermissions

/**
 * Pide los permisos del cronómetro **en contexto** (GIM-22): la primera vez que hay una
 * sesión activa, no al abrir la app. Es la regla del ADR 0004 y de la política de
 * Android: un diálogo al arrancar sin explicación es un rechazo seguro.
 *
 * - `POST_NOTIFICATIONS` se pide con el diálogo del sistema (13+).
 * - Las alarmas exactas no se piden con un diálogo: se abre `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`.
 * - Si el usuario niega cualquiera de los dos, **no pasa nada**: el descanso sigue con
 *   el servicio en primer plano y la pantalla. El texto del diálogo lo dice.
 *
 * Se pregunta una sola vez por proceso (`rememberSaveable`): si el usuario dijo "Ahora
 * no", no se le vuelve a insistir en cada ronda.
 */
@Composable
internal fun RestPermissionPrompt(active: Boolean) {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    var needsNotifications by rememberSaveable { mutableStateOf(false) }
    var needsExact by rememberSaveable { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Conceda o niegue, el flujo sigue igual: después del diálogo del sistema se
        // ofrece lo único que falta (las alarmas exactas), si hace falta.
        if (needsExact) RestPermissions.requestExactAlarms(context)
        needsNotifications = false
        needsExact = false
    }

    LaunchedEffect(active) {
        if (!active || asked) return@LaunchedEffect
        asked = true
        needsNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !RestPermissions.notificationsGranted(context)
        needsExact = !RestPermissions.exactAlarmsGranted(context)
    }

    if (!needsNotifications && !needsExact) return

    AlertDialog(
        onDismissRequest = {
            needsNotifications = false
            needsExact = false
        },
        title = { Text(stringResource(R.string.rest_permiso_titulo)) },
        text = {
            Text(
                stringResource(
                    if (needsNotifications) {
                        R.string.rest_permiso_texto
                    } else {
                        R.string.rest_permiso_alarmas_texto
                    },
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        needsNotifications ->
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

                        needsExact -> {
                            RestPermissions.requestExactAlarms(context)
                            needsExact = false
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.rest_permiso_activar))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    needsNotifications = false
                    needsExact = false
                },
            ) {
                Text(stringResource(R.string.rest_permiso_ahora_no))
            }
        },
    )
}
