package app.gimcheck.rest

import app.gimcheck.data.settings.AlertMode
import app.gimcheck.data.settings.RestVibration

/**
 * Cómo llega la pantalla de "descanso terminado" (`docs/especificacion.md` §5).
 *
 * La cadena de respaldo del ADR 0004 es la de esta enumeración: pantalla completa si el
 * sistema puede mostrarla, overlay si hay `SYSTEM_ALERT_WINDOW`, y heads-up (la
 * notificación de alta prioridad del canal `rest_done_v2`) cuando no hay nada más.
 */
enum class RestScreenDelivery {
    /** Notificación con *full-screen intent*: el sistema abre [RestDoneActivity] solo. */
    FULL_SCREEN_INTENT,

    /** Se dibuja encima de la app que esté en primer plano con `SYSTEM_ALERT_WINDOW`. */
    OVERLAY,

    /** Sin pantalla posible: queda la notificación heads-up del canal de importancia alta. */
    HEADS_UP,

    /** No se pidió pantalla, o la app está en primer plano (ahí el aviso es inline). */
    NONE,
}

/**
 * Estado del teléfono en el momento del aviso, tal como lo ve el sistema.
 *
 * Existe por el bug real del dueño (GIM-43/44): **el full-screen intent no toma la
 * pantalla si el teléfono está desbloqueado y con otra app en uso**. Android lo degrada
 * a heads-up. Por eso la decisión no puede ser "FSI si el permiso está concedido" sino
 * "FSI sólo si puede desplegarse de verdad"; si no, overlay, que sí aparece encima.
 *
 * Es un valor puro: la política se verifica entera en la JVM y la lectura del sistema
 * vive en [RestPermissions.screenState].
 */
data class RestScreenState(
    val appInForeground: Boolean,
    val deviceInteractive: Boolean,
    val keyguardLocked: Boolean,
    val fullScreenAllowed: Boolean,
    val overlayAllowed: Boolean,
) {

    /**
     * El FSI sólo se despliega con la pantalla apagada o el teléfono bloqueado. Con el
     * teléfono en la mano y desbloqueado, el sistema lo convierte en heads-up.
     */
    val fullScreenCanTakeOver: Boolean
        get() = !appInForeground &&
            fullScreenAllowed &&
            (!deviceInteractive || keyguardLocked)
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
 * 3. Con la app en segundo plano y **el teléfono desbloqueado en uso**, el overlay es
 *    la única vía que aparece encima de otra app: el FSI queda degradado a heads-up
 *    por la plataforma. Se usa overlay si el permiso está concedido.
 * 4. Con la pantalla apagada o el teléfono bloqueado el FSI sí funciona: es la mejor
 *    experiencia (pantalla completa real). Si el sistema lo niega, heads-up: el overlay
 *    no se dibuja sobre el keyguard.
 * 5. Nunca se queda sin notificación: eso lo garantiza [RestAlerter], que publica
 *    siempre, incluso con `NONE`.
 */
object RestAlertPlans {

    fun forMode(mode: AlertMode, state: RestScreenState): RestAlertPlan {
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
            state.appInForeground -> RestScreenDelivery.NONE
            state.deviceInteractive && !state.keyguardLocked ->
                if (state.overlayAllowed) RestScreenDelivery.OVERLAY else RestScreenDelivery.HEADS_UP
            state.fullScreenAllowed -> RestScreenDelivery.FULL_SCREEN_INTENT
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
