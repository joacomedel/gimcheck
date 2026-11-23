package app.gimcheck.data.transfer

import androidx.room.withTransaction
import app.gimcheck.core.DomainSnapshot
import app.gimcheck.core.ImportResult
import app.gimcheck.core.importWeb
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.repository.SettingsRepository
import app.gimcheck.data.seed.SnapshotWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resultado de importar un backup de la web desde Ajustes (GIM-45).
 *
 * Es un valor y no una excepción: la pantalla muestra [Invalid]/[Failed] tal cual, con
 * el mensaje que devuelve el importador de `:core` (que ya está en castellano y dice
 * qué campo falló).
 */
sealed interface WebImportOutcome {

    data class Imported(
        val routines: Int,
        val days: Int,
        val exercises: Int,
        val muscles: Int,
    ) : WebImportOutcome

    /** El archivo no es un backup de la web válido; nada se escribió. */
    data class Invalid(val message: String) : WebImportOutcome

    /** El JSON era válido pero falló la escritura; la transacción revirtió. */
    data class Failed(val message: String) : WebImportOutcome
}

/**
 * Importa el JSON completo de la web (`localStorage["rutinas-gym-v1"]`, v21) en una base
 * que ya tiene datos.
 *
 * Semántica de **reemplazo** de lo que el backup contiene:
 *
 * - borra todos los planes (con días e ítems) y todo el catálogo (ejercicios y
 *   músculos) y escribe el snapshot en **una transacción**: si algo falla, la base
 *   queda como estaba;
 * - **no toca `session` ni `session_set`**: el historial de entrenamientos sobrevive.
 *   Las filas viejas quedan con ids de rutina/ejercicio huérfanos y se muestran como
 *   "rutina eliminada"/sin nombre (mismo comportamiento que al borrar un plan, §2);
 * - **no toca los ajustes de la app** (alertas, tema, unidad…): el backup de la web no
 *   los contiene y pisarlos sería sorpresivo;
 * - deja como plan activo el `curPlan` del archivo, si existe.
 *
 * El seed del primer arranque usa el mismo escritor ([SnapshotWriter]); este camino es
 * para una instalación que ya tiene datos.
 */
class WebBackupImporter(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun import(text: String): WebImportOutcome = withContext(io) {
        when (val parsed = importWeb(text)) {
            is ImportResult.Failure -> WebImportOutcome.Invalid(parsed.error.message)
            is ImportResult.Success -> write(parsed.value.snapshot)
        }
    }

    private suspend fun write(
        snapshot: DomainSnapshot,
    ): WebImportOutcome = try {
        db.withTransaction {
            // Orden: planes primero (los ítems caen por CASCADE), después ejercicios
            // y por último músculos (la FK de exercise.primary_muscle_id es RESTRICT).
            db.routineDao().deleteAll()
            db.exerciseDao().deleteAll()
            db.muscleDao().deleteAll()
            SnapshotWriter(db).write(snapshot)
        }
        settings.setActiveRoutine(snapshot.activeRoutineId)
        WebImportOutcome.Imported(
            routines = snapshot.routines.size,
            days = snapshot.routines.sumOf { it.days.size },
            exercises = snapshot.exercises.size,
            muscles = snapshot.muscles.size,
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: RuntimeException) {
        WebImportOutcome.Failed(error.message ?: "no se pudo escribir el backup")
    }
}
