package app.gimcheck.data.settings

import app.gimcheck.core.E1rmFormula
import app.gimcheck.core.Settings

/*
 * Ajustes tipados de `docs/especificacion.md` §8.
 *
 * Se guardan en `app_setting` como key/value de texto (una fila por clave, sin
 * DataStore: ADR 0003). La clave es **exactamente** la de §8 y el valor es el
 * `toString()` del campo (`"5"`, `"true"`, `"EPLEY"`), no JSON: son escalares, y así
 * una fila se lee y se escribe sin un segundo formato que mantener.
 *
 * Un ajuste nuevo es una propiedad acá + su clave en [SettingKeys] + su lectura en
 * [AppSettingsCodec]: no hace falta migración de Room porque la tabla es key/value.
 */

/** Unidad de presentación (§8 `unit`). La base y todos los cálculos están en kg. */
enum class WeightUnit { KG, LB }

/** Cómo avisa el fin del descanso (§5, §8 `alertMode`). Default `SCREEN_VIBRATE`. */
enum class AlertMode {
    SCREEN,
    VIBRATE,
    SOUND,
    SCREEN_VIBRATE,
    SCREEN_SOUND,
    VIBRATE_SOUND,
    SCREEN_VIBRATE_SOUND,
    NONE,
}

/** Tono del aviso de descanso: las 3 opciones del sistema (§8 `restSound`). */
enum class RestSound { DEFAULT, CHIME, BEEP }

/** Patrón de vibración del aviso (§8 `restVibration`). */
enum class RestVibration { SHORT, LONG, DOUBLE }

/** Tema de la app (§8 `theme`). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Todos los ajustes de §8, tipados y con los defaults de la tabla.
 *
 * [DEFAULT] es la fuente de verdad de los valores iniciales: si §8 cambia, cambia
 * acá y el test de defaults lo detecta. Los siete campos que también existen en
 * [Settings] (el subconjunto que usan las métricas de `:core`) se convierten con
 * [toCoreSettings], así la UI no arma el `Settings` a mano.
 */
data class AppSettings(
    val warm: Int = 5,
    val trans: Int = 30,
    val drops: Int = 2,
    val dropSec: Int = 25,
    val dropVol: Double = 0.5,
    val optMin: Int = 10,
    val optMax: Int = 20,
    val includeIndirect: Boolean = false,
    val e1rmFormula: E1rmFormula = E1rmFormula.EPLEY,
    val weightStep: Double = 2.5,
    val unit: WeightUnit = WeightUnit.KG,
    val alertMode: AlertMode = AlertMode.SCREEN_VIBRATE,
    val restSound: RestSound = RestSound.DEFAULT,
    val restVibration: RestVibration = RestVibration.DOUBLE,
    val restAlertRepeat: Int = 0,
    val autoFillLast: Boolean = true,
    val bodyweightVolume: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val keepScreenOn: Boolean = true,
) {

    /** El subconjunto de §3 que consumen `dayStats`, `volume` y el PR estimado. */
    fun toCoreSettings(): Settings = Settings(
        warm = warm,
        trans = trans,
        drops = drops,
        dropSec = dropSec,
        dropVol = dropVol,
        optMin = optMin,
        optMax = optMax,
        includeIndirect = includeIndirect,
        e1rmFormula = e1rmFormula,
        bodyweightVolume = bodyweightVolume,
    )

    companion object {
        /** Defaults de `docs/especificacion.md` §8 (mismos que el seed de la web). */
        val DEFAULT: AppSettings = AppSettings()

        /** Los defaults de §3 que usa `:core`: no dependen de la base. */
        val CORE_DEFAULT: Settings = DEFAULT.toCoreSettings()
    }
}

/** Claves de §8 tal cual, para no escribirlas dos veces ni equivocarse. */
object SettingKeys {
    const val WARM = "warm"
    const val TRANS = "trans"
    const val DROPS = "drops"
    const val DROP_SEC = "dropSec"
    const val DROP_VOL = "dropVol"
    const val OPT_MIN = "optMin"
    const val OPT_MAX = "optMax"
    const val INCLUDE_INDIRECT = "includeIndirect"
    const val E1RM_FORMULA = "e1rmFormula"
    const val WEIGHT_STEP = "weightStep"
    const val UNIT = "unit"
    const val ALERT_MODE = "alertMode"
    const val REST_SOUND = "restSound"
    const val REST_VIBRATION = "restVibration"
    const val REST_ALERT_REPEAT = "restAlertRepeat"
    const val AUTO_FILL_LAST = "autoFillLast"
    const val BODYWEIGHT_VOLUME = "bodyweightVolume"
    const val THEME = "theme"
    const val KEEP_SCREEN_ON = "keepScreenOn"

    /*
     * Estado del entrenamiento libre (GIM-49), fuera de [AppSettings] porque no es una
     * preferencia: es el cursor de un entrenamiento en curso. Vive en la misma tabla
     * key/value para no migrar `session_state` (que es del modelo guiado anterior).
     */

    /** Id del ejercicio que se está cargando en Entrenar. */
    const val TRAINING_EXERCISE = "training.exercise"

    /** Deadline absoluto del descanso (epoch millis), para que sobreviva al cierre de la app. */
    const val TRAINING_REST_ENDS_AT = "training.restEndsAt"

    /** Duración vigente del descanso: la que muestran `−15 s`/`+15 s` y la notificación. */
    const val TRAINING_REST_SECONDS = "training.restSeconds"

    /**
     * Plan activo (GIM-16). No es un ajuste de §8: es el `curPlan` que la web guarda en
     * `localStorage`, y va como fila propia de `app_setting` para no migrar el esquema
     * de `routine` (que no tiene columna de "activo"). Fuera de [AppSettings]: no lo
     * consume ningún cálculo, sólo la UI.
     */
    const val ACTIVE_ROUTINE = "activeRoutineId"
}

/**
 * Texto ↔ [AppSettings]. Simétrico y total: una clave ausente o ilegible cae al
 * default de §8 (una fila corrupta no puede dejar la app sin ajustes), y
 * [encode] siempre escribe las 19 claves.
 */
object AppSettingsCodec {

    fun decode(values: Map<String, String>): AppSettings {
        val default = AppSettings.DEFAULT
        return AppSettings(
            warm = values.int(SettingKeys.WARM, default.warm),
            trans = values.int(SettingKeys.TRANS, default.trans),
            drops = values.int(SettingKeys.DROPS, default.drops),
            dropSec = values.int(SettingKeys.DROP_SEC, default.dropSec),
            dropVol = values.double(SettingKeys.DROP_VOL, default.dropVol),
            optMin = values.int(SettingKeys.OPT_MIN, default.optMin),
            optMax = values.int(SettingKeys.OPT_MAX, default.optMax),
            includeIndirect = values.boolean(SettingKeys.INCLUDE_INDIRECT, default.includeIndirect),
            e1rmFormula = values.enum(SettingKeys.E1RM_FORMULA, default.e1rmFormula),
            weightStep = values.double(SettingKeys.WEIGHT_STEP, default.weightStep),
            unit = values.enum(SettingKeys.UNIT, default.unit),
            alertMode = values.enum(SettingKeys.ALERT_MODE, default.alertMode),
            restSound = values.enum(SettingKeys.REST_SOUND, default.restSound),
            restVibration = values.enum(SettingKeys.REST_VIBRATION, default.restVibration),
            restAlertRepeat = values.int(SettingKeys.REST_ALERT_REPEAT, default.restAlertRepeat),
            autoFillLast = values.boolean(SettingKeys.AUTO_FILL_LAST, default.autoFillLast),
            bodyweightVolume = values.boolean(SettingKeys.BODYWEIGHT_VOLUME, default.bodyweightVolume),
            theme = values.enum(SettingKeys.THEME, default.theme),
            keepScreenOn = values.boolean(SettingKeys.KEEP_SCREEN_ON, default.keepScreenOn),
        )
    }

    /** Las 19 filas de `app_setting`, en orden estable (el orden del `Map` no importa). */
    fun encode(settings: AppSettings): Map<String, String> = linkedMapOf(
        SettingKeys.WARM to settings.warm.toString(),
        SettingKeys.TRANS to settings.trans.toString(),
        SettingKeys.DROPS to settings.drops.toString(),
        SettingKeys.DROP_SEC to settings.dropSec.toString(),
        SettingKeys.DROP_VOL to settings.dropVol.toString(),
        SettingKeys.OPT_MIN to settings.optMin.toString(),
        SettingKeys.OPT_MAX to settings.optMax.toString(),
        SettingKeys.INCLUDE_INDIRECT to settings.includeIndirect.toString(),
        SettingKeys.E1RM_FORMULA to settings.e1rmFormula.name,
        SettingKeys.WEIGHT_STEP to settings.weightStep.toString(),
        SettingKeys.UNIT to settings.unit.name,
        SettingKeys.ALERT_MODE to settings.alertMode.name,
        SettingKeys.REST_SOUND to settings.restSound.name,
        SettingKeys.REST_VIBRATION to settings.restVibration.name,
        SettingKeys.REST_ALERT_REPEAT to settings.restAlertRepeat.toString(),
        SettingKeys.AUTO_FILL_LAST to settings.autoFillLast.toString(),
        SettingKeys.BODYWEIGHT_VOLUME to settings.bodyweightVolume.toString(),
        SettingKeys.THEME to settings.theme.name,
        SettingKeys.KEEP_SCREEN_ON to settings.keepScreenOn.toString(),
    )

    private fun Map<String, String>.int(key: String, default: Int): Int =
        this[key]?.trim()?.toIntOrNull() ?: default

    private fun Map<String, String>.double(key: String, default: Double): Double =
        this[key]?.trim()?.toDoubleOrNull() ?: default

    private fun Map<String, String>.boolean(key: String, default: Boolean): Boolean =
        when (this[key]?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> default
        }

    /** Nombre de enum exacto; un valor desconocido (versión futura) cae al default. */
    private inline fun <reified T : Enum<T>> Map<String, String>.enum(key: String, default: T): T {
        val raw = this[key]?.trim() ?: return default
        return enumValues<T>().firstOrNull { it.name == raw } ?: default
    }
}
