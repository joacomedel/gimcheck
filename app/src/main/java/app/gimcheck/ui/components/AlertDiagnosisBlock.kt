package app.gimcheck.ui.components

import android.app.NotificationManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.gimcheck.R
import app.gimcheck.data.settings.ThemeMode
import app.gimcheck.rest.AlertFire
import app.gimcheck.rest.AlertDiagnosis
import app.gimcheck.rest.RestScreenDelivery
import app.gimcheck.ui.theme.GimCheckTheme
import app.gimcheck.ui.theme.GimRadius
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * **Diagnóstico de alertas** (GIM-43/GIM-44): los valores reales que el sistema reporta
 * sobre el aviso de fin de descanso, en texto plano y sin adornos.
 *
 * No es una pantalla de depuración escondida: el dueño lo lee en el teléfono cuando el
 * aviso no se comporta como espera, y con esos datos sabemos si falta un permiso, si el
 * canal quedó degradado, qué camino tomó el último aviso y si fue con la app abierta
 * (ahí no corresponde la pantalla completa).
 *
 * [compact] dibuja una sola línea con `·` (para Ajustes); completo, el bloque con título.
 */
@Composable
fun AlertDiagnosisBlock(
    diagnosis: AlertDiagnosis,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = GimTheme.colors
    val lines = listOf(
        stringResource(R.string.rest_diag_pantalla, siNo(diagnosis.fullScreenIntent)),
        stringResource(R.string.rest_diag_overlay, siNo(diagnosis.overlay)),
        stringResource(R.string.rest_diag_alarmas, siNo(diagnosis.exactAlarms)),
        stringResource(R.string.rest_diag_notificaciones, siNo(diagnosis.notifications)),
        channelLine(diagnosis),
        stringResource(R.string.rest_diag_primer_plano, siNoDesconocido(diagnosis.inForegroundAtFire)),
        stringResource(R.string.rest_diag_ultimo_aviso, deliveryLabel(diagnosis.lastDelivery)),
        stringResource(R.string.rest_diag_pantalla_al_disparar, screenAtFire(diagnosis.fire)),
    )
    if (compact) {
        Text(
            text = lines.joinToString(separator = " · "),
            style = GimText.detalle,
            color = colors.muted,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface2, RoundedCornerShape(GimRadius.control))
            .padding(GimSpacing.l),
        verticalArrangement = Arrangement.spacedBy(GimSpacing.xs),
    ) {
        Text(
            text = stringResource(R.string.rest_diag_titulo),
            style = GimText.etiqueta,
            color = colors.muted,
        )
        lines.forEach { line ->
            Text(text = line, style = GimText.detalle, color = colors.ink2)
        }
    }
}

@Composable
private fun channelLine(diagnosis: AlertDiagnosis): String {
    val importance = diagnosis.channelImportance
        ?: return stringResource(R.string.rest_diag_canal_sin_crear, diagnosis.channelId)
    return stringResource(
        R.string.rest_diag_canal,
        diagnosis.channelId,
        importance,
        importanceLabel(importance),
    )
}

@Composable
private fun importanceLabel(importance: Int): String = stringResource(
    when (importance) {
        NotificationManager.IMPORTANCE_NONE -> R.string.rest_diag_importancia_none
        NotificationManager.IMPORTANCE_MIN -> R.string.rest_diag_importancia_min
        NotificationManager.IMPORTANCE_LOW -> R.string.rest_diag_importancia_low
        NotificationManager.IMPORTANCE_DEFAULT -> R.string.rest_diag_importancia_default
        NotificationManager.IMPORTANCE_HIGH -> R.string.rest_diag_importancia_high
        else -> R.string.rest_diag_desconocido
    },
)

@Composable
private fun deliveryLabel(delivery: RestScreenDelivery?): String = stringResource(
    when (delivery) {
        RestScreenDelivery.FULL_SCREEN_INTENT -> R.string.rest_diag_entrega_fsi
        RestScreenDelivery.OVERLAY -> R.string.rest_diag_entrega_overlay
        RestScreenDelivery.HEADS_UP -> R.string.rest_diag_entrega_heads_up
        RestScreenDelivery.NONE -> R.string.rest_diag_entrega_none
        null -> R.string.rest_diag_desconocido
    },
)

@Composable
private fun screenAtFire(fire: AlertFire?): String = when {
    fire == null -> stringResource(R.string.rest_diag_desconocido)
    fire.deviceInteractive == false -> stringResource(R.string.rest_diag_pantalla_apagada)
    fire.keyguardLocked == true -> stringResource(R.string.rest_diag_pantalla_bloqueada)
    fire.deviceInteractive == true -> stringResource(R.string.rest_diag_pantalla_desbloqueada)
    else -> stringResource(R.string.rest_diag_desconocido)
}

@Composable
private fun siNo(value: Boolean): String =
    stringResource(if (value) R.string.rest_diag_si else R.string.rest_diag_no)

@Composable
private fun siNoDesconocido(value: Boolean?): String = when (value) {
    true -> siNo(true)
    false -> siNo(false)
    null -> stringResource(R.string.rest_diag_desconocido)
}

/** Documentación viva: no se renderiza acá (§11). */
@Preview(showBackground = true, backgroundColor = 0xFFF4F4F1)
@Composable
private fun AlertDiagnosisBlockPreview() {
    GimCheckTheme(mode = ThemeMode.LIGHT) {
        AlertDiagnosisBlock(
            diagnosis = AlertDiagnosis(
                fullScreenIntent = true,
                exactAlarms = true,
                notifications = true,
                overlay = true,
                channelId = "rest_done_v2",
                channelImportance = NotificationManager.IMPORTANCE_HIGH,
                fire = AlertFire(
                    inForeground = false,
                    deviceInteractive = true,
                    keyguardLocked = false,
                    delivery = RestScreenDelivery.OVERLAY,
                ),
            ),
            modifier = Modifier.padding(GimSpacing.l),
        )
    }
}
