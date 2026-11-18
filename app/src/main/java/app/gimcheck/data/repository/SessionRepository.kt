package app.gimcheck.data.repository

import app.gimcheck.core.Session
import app.gimcheck.core.SessionSet
import app.gimcheck.data.Instants
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.SessionEntity
import app.gimcheck.data.db.SessionSetEntity
import app.gimcheck.data.db.SessionStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Sesiones y series anotadas.
 *
 * **Fechas (deuda de GIM-9)**: `session.started_at`/`ended_at` y
 * `session_set.completed_at` están en epoch millis UTC; el dominio de `:core` usa
 * `LocalDateTime` (hora local). La conversión la hace [Instants] con [zone], en este
 * borde y en ningún otro: los tests pasan una zona fija y verifican ida y vuelta.
 *
 * **Sesión abierta única**: no la garantiza el repositorio sino `SessionDao.startSession`
 * (§2): si ya hay una `ACTIVE`, [start] falla y la UI pregunta si continuarla o
 * descartarla. [finish] y [abandon] son la respuesta a esa pregunta.
 */
class SessionRepository internal constructor(
    db: AppDatabase,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    private val sessionDao = db.sessionDao()
    private val sessionSetDao = db.sessionSetDao()

    /** La sesión abierta, o `null`. La UI observa esto para saber si hay sesión viva. */
    fun observeActive(): Flow<Session?> =
        combine(sessionDao.observeActive(), sessionSetDao.observeAll()) { active, sets ->
            active?.let { row -> row.toDomain(sets.filter { it.sessionId == row.id }) }
        }

    /** Histórico completo, más reciente primero, con sus series. */
    fun observeHistory(): Flow<List<Session>> =
        combine(sessionDao.observeHistory(), sessionSetDao.observeAll()) { sessions, sets ->
            val bySession = sets.groupBy { it.sessionId }
            sessions.map { row -> row.toDomain(bySession[row.id].orEmpty()) }
        }

    fun observeSets(sessionId: String): Flow<List<SessionSet>> =
        sessionSetDao.observeOfSession(sessionId).map { rows -> rows.map { it.toDomain() } }

    suspend fun session(id: String): Session? = withContext(io) {
        val row = sessionDao.getById(id) ?: return@withContext null
        row.toDomain(sessionSetDao.ofSession(id))
    }

    /**
     * Arranca una sesión nueva en estado `ACTIVE`.
     *
     * @throws IllegalStateException si ya hay una sesión abierta (§2).
     */
    suspend fun start(session: Session) = withContext(io) {
        sessionDao.startSession(
            SessionEntity(
                id = session.id,
                routineId = session.routineId,
                routineDayId = session.routineDayId,
                startedAt = Instants.toEpochMillis(session.startedAt, zone),
                endedAt = null,
                status = SessionStatus.ACTIVE,
                bodyWeightKg = session.bodyWeightKg,
            ),
        )
    }

    /** Cierra la sesión como terminada. */
    suspend fun finish(id: String, endedAt: LocalDateTime, notes: String? = null) = withContext(io) {
        sessionDao.close(id, Instants.toEpochMillis(endedAt, zone), SessionStatus.FINISHED, notes)
    }

    /** Cierra la sesión como abandonada (descartar la que quedó abierta). */
    suspend fun abandon(id: String, endedAt: LocalDateTime) = withContext(io) {
        sessionDao.close(id, Instants.toEpochMillis(endedAt, zone), SessionStatus.ABANDONED, null)
    }

    /**
     * Anota una serie. [roundIndex] es la ronda del bloque (superserie) y [setIndex]
     * la serie dentro del ítem (§4).
     */
    suspend fun recordSet(
        sessionId: String,
        set: SessionSet,
        dayItemId: String? = null,
        roundIndex: Int = 0,
        setIndex: Int = 0,
    ): String = withContext(io) {
        val id = set.id.ifBlank { newId() }
        sessionSetDao.upsert(
            SessionSetEntity(
                id = id,
                sessionId = sessionId,
                dayItemId = dayItemId,
                exerciseId = set.exerciseId.ifBlank { null },
                roundIndex = roundIndex,
                setIndex = setIndex,
                weightKg = set.weightKg,
                reps = set.reps,
                rpe = set.rpe,
                isDropSet = set.isDropSet,
                isWarmup = set.isWarmup,
                completedAt = Instants.toEpochMillis(set.completedAt, zone),
            ),
        )
        id
    }

    suspend fun updateSet(id: String, weightKg: Double, reps: Int, rpe: Double?, note: String? = null) =
        withContext(io) {
            sessionSetDao.update(id, weightKg, reps, rpe, note)
        }

    suspend fun deleteSet(id: String) = withContext(io) {
        sessionSetDao.deleteById(id)
    }

    /** Última serie de trabajo de un ejercicio: insumo de `autoFillLast` (§8). */
    suspend fun lastWorkingSet(exerciseId: String): SessionSet? = withContext(io) {
        sessionSetDao.workingSetsOfExercise(exerciseId).lastOrNull()?.toDomain()
    }

    private fun SessionEntity.toDomain(sets: List<SessionSetEntity>): Session = Session(
        id = id,
        startedAt = Instants.fromEpochMillis(startedAt, zone),
        endedAt = endedAt?.let { Instants.fromEpochMillis(it, zone) },
        bodyWeightKg = bodyWeightKg,
        routineId = routineId,
        routineDayId = routineDayId,
        sets = sets.map { it.toDomain() },
    )

    private fun SessionSetEntity.toDomain(): SessionSet = SessionSet(
        id = id,
        exerciseId = exerciseId.orEmpty(),
        weightKg = weightKg,
        reps = reps,
        completedAt = Instants.fromEpochMillis(completedAt, zone),
        rpe = rpe,
        isWarmup = isWarmup,
        isDropSet = isDropSet,
    )
}
