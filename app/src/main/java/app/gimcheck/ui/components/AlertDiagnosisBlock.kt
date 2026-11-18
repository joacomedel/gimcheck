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
import app.gimcheck.rest.AlertDiagnosis
import app.gimcheck.ui.theme.GimCheckTheme
import app.gimcheck.ui.theme.GimRadius
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme

/**
 * **Diagnóstico de alertas** (GIM-43): los valores reales que el sistema reporta sobre
 * el aviso de fin de descanso, en texto plano y sin adornos.
 *
 * No es una pantalla de depuración escondida: el dueño lo lee en el teléfono cuando el
 * aviso no se comporta como espera, y con esos cinco datos sabemos si falta un permiso,
 * si el canal quedó degradado o si el aviso se disparó con la app abierta (ahí no
 * corresponde la pantalla completa).
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
        stringResource(R.string.rest_diag_alarmas, siNo(diagnosis.exactAlarms)),
        stringResource(R.string.rest_diag_notificaciones, siNo(diagnosis.notifications)),
        channelLine(diagnosis),
        stringResource(R.string.rest_diag_primer_plano, siNoDesconocido(diagnosis.inForegroundAtFire)),
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
                fullScreenIntent = false,
                exactAlarms = true,
                notifications = true,
                channelId = "rest_done_v2",
                channelImportance = NotificationManager.IMPORTANCE_DEFAULT,
                inForegroundAtFire = false,
            ),
            modifier = Modifier.padding(GimSpacing.l),
        )
    }
}
