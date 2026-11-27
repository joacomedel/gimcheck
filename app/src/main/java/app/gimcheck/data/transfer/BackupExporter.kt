package app.gimcheck.data.transfer

import app.gimcheck.core.DomainSnapshot
import app.gimcheck.core.exportBackup
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.MetricsRepository
import app.gimcheck.data.repository.RoutineRepository
import app.gimcheck.data.repository.SessionRepository
import app.gimcheck.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime

/**
 * Export de un backup completo (GIM-29): arma el [DomainSnapshot] de la base y lo
 * serializa con `:core` (`exportBackup`), el mismo contrato que ya importa la web y
 * que los tests de backup cubren.
 *
 * Es de solo lectura: no escribe ni migra nada. La UI lo llama desde "Datos →
 * Exportar backup" y comparte el texto con el selector de archivos del sistema
 * (`CreateDocument`), sin permiso de storage.
 *
 * Qué incluye: ajustes de cálculo, músculos, ejercicios con secundarios, rutinas con
 * días e ítems, sesiones terminadas con sus series y las métricas corporales. Qué no:
 * el historial de sesiones abandonadas (no es un entrenamiento) ni los ajustes de
 * apariencia/alertas, que el formato v1 del backup no modela todavía.
 */
internal class BackupExporter(
    private val routines: RoutineRepository,
    private val exercises: ExerciseRepository,
    private val sessions: SessionRepository,
    private val metrics: MetricsRepository,
    private val settings: SettingsRepository,
    private val now: () -> LocalDateTime = { java.time.LocalDateTime.now().toKotlinLocalDateTime() },
) {

    /** JSON listo para guardar, indentado para que se pueda leer a ojo. */
    suspend fun export(): String {
        val prefs = settings.current()
        val snapshot = DomainSnapshot(
            settings = prefs.toCoreSettings(),
            muscles = exercises.muscles(),
            exercises = exercises.all(),
            routines = routines.allRoutines(),
            sessions = sessions.allSessions(),
            bodyMetrics = metrics.observeAll().first(),
            activeRoutineId = settings.activeRoutineId.first(),
        )
        return exportBackup(snapshot, exportedAt = now().toString(), pretty = true)
    }

    /** Nombre sugerido: `gimcheck-backup-2026-11-27.json`. */
    fun suggestedName(): String {
        val date = now().date
        val month = date.monthNumber.toString().padStart(2, '0')
        val day = date.dayOfMonth.toString().padStart(2, '0')
        return "gimcheck-backup-${date.year}-$month-$day.json"
    }
}
