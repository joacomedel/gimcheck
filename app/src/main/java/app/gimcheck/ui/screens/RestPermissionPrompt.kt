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
 * Pide los permisos del aviso **en contexto** (GIM-22 y GIM-23): la primera vez que hay
 * una sesión activa, no al abrir la app. Es la regla del ADR 0004 y de la política de
 * Android: un diálogo al arrancar sin explicación es un rechazo seguro.
 *
 * Los cuatro, en este orden y de a uno:
 *
 * - `POST_NOTIFICATIONS` con el diálogo del sistema (13+).
 * - Alarmas exactas: no hay diálogo, se abre `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`.
 * - Pantalla completa (`USE_FULL_SCREEN_INTENT`, 14+): `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`.
 * - **Aparecer encima de otras apps** (`SYSTEM_ALERT_WINDOW`, GIM-44): es la única vía
 *   que interrumpe con el teléfono desbloqueado y otra app en primer plano, donde el
 *   full-screen intent se degrada a heads-up.
 *
 * Si el usuario niega cualquiera, **no pasa nada**: el aviso cae al siguiente escalón
 * (overlay → heads-up) y el cronómetro sigue con el servicio en primer plano. El texto
 * del diálogo lo dice.
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
    var needsFullScreen by rememberSaveable { mutableStateOf(false) }
    var needsOverlay by rememberSaveable { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Conceda o niegue, el flujo sigue igual: si falta otro permiso, el mismo
        // diálogo vuelve a aparecer con su texto (el orden lo decide el `when` de abajo).
        needsNotifications = false
    }

    LaunchedEffect(active) {
        if (!active || asked) return@LaunchedEffect
        asked = true
        needsNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !RestPermissions.notificationsGranted(context)
        needsExact = !RestPermissions.exactAlarmsGranted(context)
        // El modo por defecto (`SCREEN_VIBRATE`) incluye pantalla, y el selector vive en
        // Ajustes: si el permiso falta se ofrece una vez, con su explicación.
        // Con la app en primer plano el FSI no se usa, pero el pedido no estorba.
        needsFullScreen = !RestPermissions.fullScreenIntentGranted(context)
        needsOverlay = !RestPermissions.overlayGranted(context)
    }

    if (!needsNotifications && !needsExact && !needsFullScreen && !needsOverlay) return

    AlertDialog(
        onDismissRequest = {
            needsNotifications = false
            needsExact = false
            needsFullScreen = false
            needsOverlay = false
        },
        title = { Text(stringResource(R.string.rest_permiso_titulo)) },
        text = {
            Text(
                stringResource(
                    when {
                        needsNotifications -> R.string.rest_permiso_texto
                        needsExact -> R.string.rest_permiso_alarmas_texto
                        needsFullScreen -> R.string.rest_permiso_pantalla_texto
                        else -> R.string.rest_permiso_overlay_texto
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

                        needsFullScreen -> {
                            RestPermissions.requestFullScreenIntent(context)
                            needsFullScreen = false
                        }

                        else -> {
                            RestPermissions.requestOverlay(context)
                            needsOverlay = false
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
                    needsFullScreen = false
                    needsOverlay = false
                },
            ) {
                Text(stringResource(R.string.rest_permiso_ahora_no))
            }
        },
    )
}
