package app.gimcheck.rest

import app.gimcheck.data.settings.AlertMode
import app.gimcheck.data.settings.RestVibration

/**
 * Cómo llega la pantalla de "descanso terminado" (`docs/especificacion.md` §5).
 *
 * La cadena de respaldo del ADR 0004 es la de esta enumeración: pantalla completa si el
 * sistema lo permite, overlay si hay `SYSTEM_ALERT_WINDOW`, y heads-up (la notificación
 * de alta prioridad del canal `rest_done_v2`) cuando no hay nada más.
 */
enum class RestScreenDelivery {
    /** Notificación con *full-screen intent*: el sistema abre [RestDoneActivity] solo. */
    FULL_SCREEN_INTENT,

    /** El sistema negó el FSI: se dibuja encima con `SYSTEM_ALERT_WINDOW`. */
    OVERLAY,

    /** Sin FSI ni overlay: queda la notificación heads-up del canal de importancia alta. */
    HEADS_UP,

    /** No se pidió pantalla, o la app está en primer plano (ahí el aviso es inline). */
    NONE,
}

/**
 * Lo que hay que hacer para avisar el fin del descanso, ya resuelto desde `alertMode`
 * (§5). Es un valor puro: la decisión se puede testear sin Android y la ejecución
 * (notificación, vibración, sonido) queda del lado de [RestAlerter].
 */
data class RestAlertPlan(
    val screen: RestScreenDelivery = RestScreenDelivery.NONE,
    val vibrate: Boolean = false,
    val sound: Boolean = false,
) {

    /** `true` si el aviso hace algo además de dejar la notificación. */
    val hasSignal: Boolean
        get() = vibrate || sound || screen != RestScreenDelivery.NONE
}

/**
 * `alertMode` (8 combinaciones) → [RestAlertPlan].
 *
 * Reglas de §5, en orden:
 *
 * 1. `SCREEN`/`VIBRATE`/`SOUND` se combinan por nombre; `NONE` no dispara nada.
 * 2. **Con la app en primer plano nunca se roba la pantalla**: el aviso es inline en
 *    Entrenar y sólo vibra/suena.
 * 3. Si se pidió pantalla: FSI si está concedido; overlay si hay `SYSTEM_ALERT_WINDOW`;
 *    en última instancia heads-up. Nunca se queda sin notificación: eso lo garantiza
 *    [RestAlerter], que publica siempre.
 */
object RestAlertPlans {

    fun forMode(
        mode: AlertMode,
        appInForeground: Boolean,
        fullScreenAllowed: Boolean,
        overlayAllowed: Boolean,
    ): RestAlertPlan {
        val (wantsScreen, vibrate, sound) = when (mode) {
            AlertMode.SCREEN -> Triple(true, false, false)
            AlertMode.VIBRATE -> Triple(false, true, false)
            AlertMode.SOUND -> Triple(false, false, true)
            AlertMode.SCREEN_VIBRATE -> Triple(true, true, false)
            AlertMode.SCREEN_SOUND -> Triple(true, false, true)
            AlertMode.VIBRATE_SOUND -> Triple(false, true, true)
            AlertMode.SCREEN_VIBRATE_SOUND -> Triple(true, true, true)
            AlertMode.NONE -> Triple(false, false, false)
        }
        val screen = when {
            !wantsScreen -> RestScreenDelivery.NONE
            appInForeground -> RestScreenDelivery.NONE
            fullScreenAllowed -> RestScreenDelivery.FULL_SCREEN_INTENT
            overlayAllowed -> RestScreenDelivery.OVERLAY
            else -> RestScreenDelivery.HEADS_UP
        }
        return RestAlertPlan(screen = screen, vibrate = vibrate, sound = sound)
    }
}

/**
 * Patrones de vibración de `restVibration` (§8): corto, largo y doble (default).
 *
 * La forma es `[espera, vibra, pausa, vibra, …]` en milisegundos, la que espera
 * `VibrationEffect.createWaveform`. `-1` en el repeat (lo pone [SystemRestVibrator])
 * significa "una sola vez": el aviso no se queda vibrando para siempre.
 */
object RestVibrationPatterns {

    fun pattern(kind: RestVibration): LongArray = when (kind) {
        RestVibration.SHORT -> longArrayOf(0, 150)
        RestVibration.LONG -> longArrayOf(0, 900)
        RestVibration.DOUBLE -> longArrayOf(0, 250, 200, 250)
    }
}
