package app.gimcheck.rest

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.gimcheck.data.settings.RestSound
import app.gimcheck.data.settings.RestVibration

/**
 * Los dos efectos físicos del aviso, detrás de interfaces mínimas (GIM-23, §5).
 *
 * Están separados del [RestAlerter] por una razón de test: la JVM no puede vibrar ni
 * sonar, y lo que hay que verificar en CI es **qué se pide**, no el hardware. Los tests
 * inyectan dobles; en el teléfono corren las implementaciones `System*`.
 */

/** Vibración del aviso, con el patrón de `restVibration`. */
interface RestVibrator {

    /** `false` = el aparato no tiene vibrador (o el usuario lo apagó). */
    fun vibrate(kind: RestVibration): Boolean
}

/** El del sistema: `VibratorManager` en 31+, `Vibrator` en versiones anteriores. */
class SystemRestVibrator(private val vibrator: Vibrator?) : RestVibrator {

    override fun vibrate(kind: RestVibration): Boolean {
        val device = vibrator ?: return false
        if (!device.hasVibrator()) return false
        return try {
            device.vibrate(VibrationEffect.createWaveform(RestVibrationPatterns.pattern(kind), -1))
            true
        } catch (e: RuntimeException) {
            // Un OEM puede negarse en cualquier momento: el aviso sigue con la
            // notificación y el sonido.
            false
        }
    }

    companion object {

        fun create(context: Context): SystemRestVibrator {
            val app = context.applicationContext
            val device: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                app.getSystemService(VibratorManager::class.java)?.defaultVibrator
                    ?: legacyVibrator(app)
            } else {
                legacyVibrator(app)
            }
            return SystemRestVibrator(device)
        }

        @Suppress("DEPRECATION")
        private fun legacyVibrator(context: Context): Vibrator? =
            context.getSystemService(Vibrator::class.java)
    }
}

/** Tono del aviso, con la opción de `restSound`. */
interface RestSoundPlayer {

    fun play(sound: RestSound)

    fun stop()
}

/**
 * Las 3 opciones de `restSound` (§8), todas del sistema — no hay assets de audio:
 *
 * - `DEFAULT`: el tono del canal de notificación. Si el canal quedó en silencio (el
 *   usuario lo apagó), se cae al tono de notificación del sistema (§5: "o
 *   `RingtoneManager` si el canal no suena").
 * - `CHIME`: tono de notificación del sistema.
 * - `BEEP`: tono de alarma del sistema.
 */
class SystemRestSoundPlayer(private val context: Context) : RestSoundPlayer {

    private var playing: Ringtone? = null

    override fun play(sound: RestSound) {
        stop()
        val uri: Uri? = when (sound) {
            // El canal ya suena solo: no se duplica el tono.
            RestSound.DEFAULT -> if (RestNotifications.doneChannelIsSilent(context)) {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            } else {
                null
            }

            RestSound.CHIME -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RestSound.BEEP -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        }
        if (uri == null) return
        try {
            val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.isLooping = false
            }
            ringtone.play()
            playing = ringtone
        } catch (e: RuntimeException) {
            // Sin tono disponible: queda la vibración y la notificación.
            playing = null
        }
    }

    override fun stop() {
        try {
            playing?.stop()
        } catch (e: RuntimeException) {
            // Nada que detener.
        }
        playing = null
    }
}

/**
 * El overlay de último recurso antes del heads-up (§5). Se dibuja con
 * `SYSTEM_ALERT_WINDOW`, sin abrir una actividad: el sistema no permite iniciar
 * actividades desde segundo plano, pero sí dibujar encima cuando el permiso está dado.
 */
interface RestOverlayPresenter {

    fun show(message: String, onClick: () -> Unit)

    fun hide()
}
