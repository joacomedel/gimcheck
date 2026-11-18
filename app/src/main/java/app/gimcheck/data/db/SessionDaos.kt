package app.gimcheck.data.db

import androidx.annotation.VisibleForTesting
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Sesiones (§2 `session`, `session_set`, `session_state`). */
@Dao
internal interface SessionDao {

    /**
     * La sesión abierta.
     *
     * §2 dice que sólo hay una `ACTIVE`; si un import o datos viejos dejaron dos,
     * el desempate es **determinista** (la más reciente por `started_at`, y el id
     * mayor como último criterio) y no el orden arbitrario de SQLite. La fila
     * elegida es la que el usuario estaba usando (GIM-11).
     */
    @Query("SELECT * FROM session WHERE status = 'ACTIVE' ORDER BY started_at DESC, id DESC LIMIT 1")
    suspend fun active(): SessionEntity?

    @Query("SELECT * FROM session WHERE status = 'ACTIVE' ORDER BY started_at DESC, id DESC LIMIT 1")
    fun observeActive(): Flow<SessionEntity?>

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    @Query("SELECT * FROM session ORDER BY started_at DESC")
    fun observeHistory(): Flow<List<SessionEntity>>

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("UPDATE session SET ended_at = :endedAt, status = :status, notes = :notes WHERE id = :id")
    suspend fun close(id: String, endedAt: Long?, status: SessionStatus, notes: String?)

    @Query("DELETE FROM session WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Arranca una sesión nueva.
     *
     * §2: sólo puede haber **una** sesión `ACTIVE` por dispositivo. No hay índice
     * único parcial (Room no los declara y SQLite los haría con SQL a mano), así
     * que la garantía es esta transacción: si ya hay una abierta, falla y la UI
     * pregunta si continuarla o descartarla.
     *
     * @throws IllegalStateException si ya hay una sesión `ACTIVE`.
     */
    @Transaction
    suspend fun startSession(session: SessionEntity) {
        require(session.status == SessionStatus.ACTIVE) {
            "startSession sólo acepta sesiones ACTIVE (llegó ${session.status})"
        }
        val running = active()
        check(running == null) {
            "ya hay una sesión abierta (${running?.id}); continuarla o descartarla primero"
        }
        upsert(session)
    }
}

/** Series anotadas (§2 `session_set`; §3.1 y §3.2 las agregan). */
@Dao
internal interface SessionSetDao {

    @Query("SELECT * FROM session_set WHERE session_id = :sessionId ORDER BY completed_at, round_index, set_index")
    fun observeOfSession(sessionId: String): Flow<List<SessionSetEntity>>

    @Query("SELECT * FROM session_set WHERE session_id = :sessionId ORDER BY completed_at, round_index, set_index")
    suspend fun ofSession(sessionId: String): List<SessionSetEntity>

    /** Ids ya guardados de una sesión: el cursor persiste sólo las series nuevas (GIM-11). */
    @Query("SELECT id FROM session_set WHERE session_id = :sessionId")
    suspend fun idsOfSession(sessionId: String): List<String>

    /** Todas las series de todas las sesiones: el histórico se arma con un solo Flow. */
    @Query("SELECT * FROM session_set ORDER BY session_id, completed_at, round_index, set_index")
    fun observeAll(): Flow<List<SessionSetEntity>>

    /** Series de trabajo de un ejercicio, en orden: insumo del PR (§3.1). */
    @Query("SELECT * FROM session_set WHERE exercise_id = :exerciseId AND is_warmup = 0 ORDER BY completed_at")
    suspend fun workingSetsOfExercise(exerciseId: String): List<SessionSetEntity>

    @Upsert
    suspend fun upsert(set: SessionSetEntity)

    @Query("UPDATE session_set SET weight_kg = :weightKg, reps = :reps, rpe = :rpe, note = :note WHERE id = :id")
    suspend fun update(id: String, weightKg: Double, reps: Int, rpe: Double?, note: String?)

    @Query("DELETE FROM session_set WHERE id = :id")
    suspend fun deleteById(id: String)
}

/** Cursor persistido de la sesión guiada (§2 `session_state`, §4). */
@Dao
internal interface SessionStateDao {

    @Query("SELECT * FROM session_state WHERE session_id = :sessionId")
    suspend fun get(sessionId: String): SessionStateEntity?

    @Query("SELECT * FROM session_state WHERE session_id = :sessionId")
    fun observe(sessionId: String): Flow<SessionStateEntity?>

    /**
     * Cursor de la sesión abierta (GIM-11). El JOIN con `session` filtra las filas
     * de sesiones ya terminadas: `session_state` no se borra al cerrar, así que la
     * única fuente de "hay sesión viva" sigue siendo `session.status`.
     */
    @Query(
        "SELECT state.session_id, state.block_index, state.round_index, state.item_index, " +
            "state.rest_ends_at, state.rest_seconds, state.updated_at " +
            "FROM session_state state JOIN session s ON s.id = state.session_id " +
            "WHERE s.status = 'ACTIVE' LIMIT 1",
    )
    fun observeActive(): Flow<SessionStateEntity?>

    @Upsert
    suspend fun upsert(state: SessionStateEntity)

    @Query("DELETE FROM session_state WHERE session_id = :sessionId")
    suspend fun delete(sessionId: String)
}
