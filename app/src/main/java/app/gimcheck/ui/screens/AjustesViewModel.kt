package app.gimcheck.ui.screens

import app.gimcheck.AppContainer
import app.gimcheck.core.E1rmFormula
import app.gimcheck.data.repository.SettingsRepository
import app.gimcheck.data.settings.AlertMode
import app.gimcheck.data.settings.AppSettings
import app.gimcheck.data.settings.RestSound
import app.gimcheck.data.settings.RestVibration
import app.gimcheck.data.settings.ThemeMode
import app.gimcheck.data.settings.alertModeOf
import app.gimcheck.data.settings.sounds
import app.gimcheck.data.settings.vibrates
import app.gimcheck.data.settings.showsScreen
import app.gimcheck.data.transfer.BackupExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Resultado de "Datos → Exportar backup", para que la pantalla elija el copy. */
sealed interface BackupExportResult {
    data class Ready(val name: String, val json: String) : BackupExportResult
    data object Failed : BackupExportResult
}

data class AjustesUiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings.DEFAULT,
    val export: BackupExportResult? = null,
    val exportedName: String? = null,
)

/**
 * ViewModel de **Ajustes** (GIM-29).
 *
 * Todos los ajustes de §8 son escalares en `app_setting`; acá se editan de a uno con
 * [SettingsRepository.update]. La escritura va serializada con un [Mutex] porque
 * `update` es leer-modificar-escribir: dos toques rápidos (dos steppers) podrían
 * pisarse y dejar el valor del primero.
 *
 * El backup se arma en segundo plano con [BackupExporter] y se entrega a la pantalla
 * como [BackupExportResult.Ready]; la pantalla abre el selector de archivos del
 * sistema y escribe el JSON. El ViewModel no toca URIs ni permisos.
 */
class AjustesViewModel internal constructor(
    private val settingsRepository: SettingsRepository,
    private val backupExporter: BackupExporter,
    private val scope: CoroutineScope,
) {

    constructor(container: AppContainer, scope: CoroutineScope) : this(
        settingsRepository = container.settings,
        backupExporter = container.backup,
        scope = scope,
    )

    private val _state = MutableStateFlow(AjustesUiState())
    val state: StateFlow<AjustesUiState> = _state.asStateFlow()

    private var observation: Job? = null
    private val gate = Mutex()

    fun start() {
        if (observation != null) return
        observation = scope.launch {
            settingsRepository.settings.collect { prefs ->
                _state.value = _state.value.copy(loading = false, settings = prefs)
            }
        }
    }

    fun stop() {
        observation?.cancel()
        observation = null
    }

    fun update(block: (AppSettings) -> AppSettings) {
        scope.launch {
            gate.withLock { settingsRepository.update(block) }
        }
    }

    fun setWarm(value: Int) = update { it.copy(warm = value.coerceIn(0, 60)) }
    fun setTrans(value: Int) = update { it.copy(trans = value.coerceIn(0, 300)) }
    fun setDrops(value: Int) = update { it.copy(drops = value.coerceIn(0, 10)) }
    fun setDropSec(value: Int) = update { it.copy(dropSec = value.coerceIn(0, 180)) }
    fun setDropVol(value: Double) = update { it.copy(dropVol = roundTenth(value).coerceIn(0.1, 1.0)) }
    fun setIncludeIndirect(value: Boolean) = update { it.copy(includeIndirect = value) }
    fun setOptMin(value: Int) = update {
        val min = value.coerceIn(0, 60)
        it.copy(optMin = min, optMax = it.optMax.coerceAtLeast(min + 1))
    }
    fun setOptMax(value: Int) = update { it.copy(optMax = value.coerceIn(1, 80).coerceAtLeast(it.optMin + 1)) }
    fun setWeightStep(value: Double) = update { it.copy(weightStep = value.coerceIn(0.5, 10.0)) }
    fun setFormula(value: E1rmFormula) = update { it.copy(e1rmFormula = value) }
    fun setAutoFillLast(value: Boolean) = update { it.copy(autoFillLast = value) }
    fun setBodyweightVolume(value: Boolean) = update { it.copy(bodyweightVolume = value) }
    fun setKeepScreenOn(value: Boolean) = update { it.copy(keepScreenOn = value) }
    fun setTheme(value: ThemeMode) = update { it.copy(theme = value) }

    fun setAlertMode(value: AlertMode) = update { it.copy(alertMode = value) }
    fun toggleAlertScreen(value: Boolean) = update {
        it.copy(alertMode = alertModeOf(value, it.alertMode.vibrates, it.alertMode.sounds))
    }

    fun toggleAlertVibration(value: Boolean) = update {
        it.copy(alertMode = alertModeOf(it.alertMode.showsScreen, value, it.alertMode.sounds))
    }

    fun toggleAlertSound(value: Boolean) = update {
        it.copy(alertMode = alertModeOf(it.alertMode.showsScreen, it.alertMode.vibrates, value))
    }

    fun setRestSound(value: RestSound) = update { it.copy(restSound = value) }
    fun setRestVibration(value: RestVibration) = update { it.copy(restVibration = value) }
    fun setRestAlertRepeat(value: Int) = update { it.copy(restAlertRepeat = value.coerceIn(0, 10)) }

    /** Vuelve a los defaults de §8; no borra el historial ni las rutinas. */
    fun resetSettings() {
        scope.launch { gate.withLock { settingsRepository.reset() } }
    }

    /** Arma el backup y lo publica para que la pantalla abra el selector de archivos. */
    fun buildBackup() {
        scope.launch {
            val result = runCatching<BackupExportResult> {
                BackupExportResult.Ready(backupExporter.suggestedName(), backupExporter.export())
            }.getOrDefault(BackupExportResult.Failed)
            _state.value = _state.value.copy(export = result, exportedName = null)
        }
    }

    /** El archivo se escribió en la URI elegida. */
    fun exportWritten(name: String) {
        _state.value = _state.value.copy(export = null, exportedName = name)
    }

    /** El usuario canceló el selector: no queda un backup pendiente. */
    fun exportCancelled() {
        _state.value = _state.value.copy(export = null)
    }

    fun clearFeedback() {
        _state.value = _state.value.copy(exportedName = null)
    }

    private fun roundTenth(value: Double): Double = kotlin.math.round(value * 10.0) / 10.0
}
