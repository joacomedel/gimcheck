package app.gimcheck.data.repository

import app.gimcheck.core.GuidedSession
import app.gimcheck.core.LoggedSet
import app.gimcheck.core.Restore
import app.gimcheck.core.Session
import app.gimcheck.core.SessionEffect
import app.gimcheck.core.SessionEvent
import app.gimcheck.core.SessionSet
import app.gimcheck.core.SessionStatus as CoreStatus
import app.gimcheck.core.SessionTransition
import app.gimcheck.core.groups
import app.gimcheck.core.reduce
import app.gimcheck.data.Instants
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.SessionEntity
import app.gimcheck.data.db.SessionSetEntity
import app.gimcheck.data.db.SessionStateEntity
import app.gimcheck.data.db.SessionStatus as RowStatus
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Sesiones, series anotadas y **cursor de la sesión guiada**.
 *
 * **Fechas (deuda de GIM-9)**: `session.started_at`/`ended_at` y
 * `session_set.completed_at` están en epoch millis UTC; el dominio de `:core` usa
 * `LocalDateTime` (hora local). La conversión la hace [Instants] con [zone], en este
 * borde y en ningún otro: los tests pasan una zona fija y verifican ida y vuelta.
 *
 * **Sesión abierta única**: la garantía es `SessionDao.startSession` (§2): si ya hay
 * una `ACTIVE`, [start] falla y la UI pregunta si continuarla o descartarla. [finish] y
 * [abandon] son la respuesta a esa pregunta. Si datos viejos o un import dejaron dos
 * `ACTIVE`, la que gana es determinista: la más reciente por `started_at`
 * (`SessionDao.active`), y ese caso está cubierto por un test (GIM-11).
 *
 * **Sesión guiada (GIM-11)**: este repositorio es el borde de `session_state`. La
 * máquina de estados es de `:core` y **no se duplica acá**: [dispatch] llama a
 * [reduce] con el estado que le pasa el llamador, y obedece el efecto
 * [SessionEffect.PersistState] para escribir el cursor (`block_index`, `round_index`,
 * `item_index`, `rest_ends_at` como epoch millis, `rest_seconds`, `updated_at`) y las
 * series nuevas. [resumeActive] reconstruye el estado desde la base y le aplica
 * `Restore(now)`: si el deadline venció mientras el proceso estaba muerto, la máquina
 * deja el cursor intacto y devuelve `RestFinished` + `ShowRestDone` (mostrar "descanso
 * terminado"); si sigue vivo, devuelve `StartRest(lo que falta)`, calculado desde el
 * deadline absoluto y no desde una cuenta regresiva guardada.
 */
class SessionRepository internal constructor(
    db: AppDatabase,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Instant = Instant::now,
) {

    private val database = db
    private val sessionDao = db.sessionDao()
    private val sessionSetDao = db.sessionSetDao()
    private val sessionStateDao = db.sessionStateDao()
    private val routineDao = db.routineDao()
    private val routines = RoutineRepository(db, io, newId)

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

    /**
     * Solo las sesiones **terminadas** (`FINISHED`), más reciente primero, con sus series.
     *
     * Es el histórico que ve el producto: "Últimos" de Hoy (GIM-24), el insumo de los PR del
     * día (una abandonada no fija récords) y, más adelante, la pantalla de Historial. La
     * sesión abandonada sigue en la base con sus series —§2 no borra nada— pero no cuenta
     * como entrenamiento.
     */
    fun observeFinishedHistory(): Flow<List<Session>> =
        combine(sessionDao.observeFinishedHistory(), sessionSetDao.observeAll()) { sessions, sets ->
            val bySession = sets.groupBy { it.sessionId }
            sessions.map { row -> row.toDomain(bySession[row.id].orEmpty()) }
        }

    /**
     * Todas las sesiones terminadas con sus series, en una foto (export de backup).
     *
     * Es el mismo conjunto que muestra Historial: una sesión abandonada no es un
     * entrenamiento y no viaja en el backup.
     */
    suspend fun allSessions(): List<Session> = withContext(io) {
        val bySession = sessionSetDao.all().groupBy { it.sessionId }
        sessionDao.finishedHistory().map { row -> row.toDomain(bySession[row.id].orEmpty()) }
    }

    /** Foto del histórico terminado, para el resumen de fin de sesión (GIM-24). */
    suspend fun finishedHistory(): List<Session> = withContext(io) {
        val bySession = sessionSetDao.all().groupBy { it.sessionId }
        sessionDao.finishedHistory().map { row -> row.toDomain(bySession[row.id].orEmpty()) }
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
                status = RowStatus.ACTIVE,
                bodyWeightKg = session.bodyWeightKg,
            ),
        )
    }

    /** Cierra la sesión como terminada. */
    suspend fun finish(id: String, endedAt: LocalDateTime, notes: String? = null) = withContext(io) {
        sessionDao.close(id, Instants.toEpochMillis(endedAt, zone), RowStatus.FINISHED, notes)
    }

    /**
     * Devuelve la sesión abierta o crea una nueva (entrenamiento libre, GIM-49).
     *
     * A diferencia de [start], **no falla** si ya hay una `ACTIVE`: en el modelo del dueño
     * hay un solo entrenamiento abierto y entrar a Entrenar lo retoma. `routine_day_id`
     * queda en `null` porque las series ya no pertenecen a un día del plan; `routine_id`
     * conserva la rutina activa para el histórico.
     */
    suspend fun openOrCreate(
        routineId: String?,
        startedAt: LocalDateTime,
        routineDayId: String? = null,
    ): String = withContext(io) {
        sessionDao.active()?.let { return@withContext it.id }
        val id = newId()
        sessionDao.startSession(
            SessionEntity(
                id = id,
                routineId = routineId,
                routineDayId = routineDayId,
                startedAt = Instants.toEpochMillis(startedAt, zone),
                endedAt = null,
                status = RowStatus.ACTIVE,
                bodyWeightKg = null,
            ),
        )
        id
    }

    /** Cierra la sesión como abandonada (descartar la que quedó abierta). */
    suspend fun abandon(id: String, endedAt: LocalDateTime) = withContext(io) {
        sessionDao.close(id, Instants.toEpochMillis(endedAt, zone), RowStatus.ABANDONED, null)
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

    /**
     * Borra una sesión del histórico y sus series (`session_set` cae por CASCADE).
     *
     * Se usa desde Historial para descartar un entrenamiento cargado por error. La
     * sesión `ACTIVE` no se borra por acá: descartarla es [abandon] (conserva las
     * series y deja el registro).
     */
    suspend fun deleteSession(id: String) = withContext(io) {
        sessionDao.delete(id)
    }

    /** Última serie de trabajo de un ejercicio: insumo de `autoFillLast` (§8). */
    suspend fun lastWorkingSet(exerciseId: String): SessionSet? = withContext(io) {
        sessionSetDao.workingSetsOfExercise(exerciseId).lastOrNull()?.toDomain()
    }

    // --- sesión guiada: cursor persistido (§4, GIM-11) ---------------------------

    /**
     * Cursor de la sesión abierta, tal como quedó guardado, como `Flow`.
     *
     * Emite `null` si no hay sesión `ACTIVE` o si su día ya no existe (rutina
     * eliminada). **No aplica [Restore]**: el deadline vence solo y quién lo resuelve
     * es [resumeActive], que corre en `ON_START` vía [SessionResumer] al abrir la app,
     * o [dispatch] con un `Tick`. Este Flow es para la pantalla que ya está viva
     * (sigue el cursor cuando otra corrutina lo mueve); **no es la única fuente del
     * estado**: lo que hay que mostrar al abrir lo publica [SessionResumer.resumed]
     * (GIM-39).
     *
     * **Cada emisión es una foto completa y fresca** ([guidedNow]): los tres Flows de
     * abajo sólo avisan que algo cambió. `combine` guarda el último valor de cada uno,
     * así que armar el estado con esos valores publicaba snapshots mezclados —series
     * nuevas con el cursor viejo— y la pantalla retrocedía a la ronda/ítem anterior
     * (GIM-21: "corregir una serie no mueve el cursor"). Leer ahora, con la escritura
     * de [persistGuided] en una sola transacción, garantiza que lo emitido nunca es
     * más viejo que lo ya emitido.
     */
    fun observeGuided(): Flow<GuidedSession?> =
        combine(
            sessionDao.observeActive(),
            sessionStateDao.observeActive(),
            sessionSetDao.observeAll(),
        ) { _, _, _ -> Unit }
            .map { guidedNow() }

    /**
     * La misma foto que emite [observeGuided], leída en este instante: sesión `ACTIVE`,
     * cursor y series en un solo paso.
     *
     * Es lo que consume el ViewModel antes de pintar: leerla después de que terminó el
     * `dispatch` en curso evita que una emisión vieja pise el estado que ya se aplicó.
     */
    suspend fun guidedNow(): GuidedSession? = withContext(io) {
        val row = sessionDao.active() ?: return@withContext null
        reconstruct(row, sessionStateDao.get(row.id), sessionSetDao.ofSession(row.id))
    }

    /**
     * Rehidratación al abrir la app (§4): lee `session` + `session_state` + las series
     * y aplica `Restore(now)` con la máquina de `:core`.
     *
     * - Sin sesión `ACTIVE` → [ActiveSession.None] ("sin sesión").
     * - Descanso vigente → [ActiveSession.Resumable] con `remainingSeconds` calculado
     *   desde `rest_ends_at` (deadline absoluto, no cuenta regresiva guardada).
     * - Deadline vencido → [ActiveSession.Resumable] con `restFinished = true`, el
     *   cursor intacto y el estado ya en `RUNNING` ("descanso terminado"). La escritura
     *   del deadline limpio la hace [dispatch] por el efecto `PersistState`.
     * - Sesión `ACTIVE` cuyo día ya no existe → [ActiveSession.MissingPlan].
     */
    suspend fun resumeActive(now: Instant = this.now()): ActiveSession = withContext(io) {
        val row = sessionDao.active() ?: return@withContext ActiveSession.None
        val persisted = reconstruct(row, sessionStateDao.get(row.id), sessionSetDao.ofSession(row.id))
            ?: return@withContext ActiveSession.MissingPlan(row.id)
        val transition = dispatch(persisted, Restore(now), now)
        ActiveSession.Resumable(transition.state, transition.effects)
    }

    /**
     * Aplica un evento de la máquina: `reduce` → persistir → devolver efectos.
     *
     * No reimplementa ninguna regla: el estado nuevo y los efectos salen de
     * [reduce] (`:core`). Si la transición incluye [SessionEffect.PersistState],
     * escribe el cursor y las series nuevas en la misma transacción lógica; el
     * llamador ejecuta los efectos en el orden recibido (cronómetro, alarma,
     * navegación). Un evento fuera de estado no escribe nada.
     *
     * @param state estado que tiene el llamador (normalmente el que devolvió el
     *   `dispatch` anterior o [resumeActive]).
     */
    suspend fun dispatch(
        state: GuidedSession,
        event: SessionEvent,
        now: Instant = this.now(),
    ): SessionTransition = withContext(io) {
        val transition = reduce(state, event)
        if (SessionEffect.PersistState !in transition.effects) return@withContext transition
        persistGuided(transition.state, now)
        transition
    }

    /**
     * Escribe el cursor de [state] como lo pide el efecto `PersistState`.
     *
     * **Todo en una transacción.** Series y cursor se escriben juntos: si fueran dos
     * escrituras, `observeGuided` podía leer en el medio y publicar "series nuevas con
     * el cursor anterior" — la pantalla saltaba al ítem de la superserie y se perdía el
     * descanso recién arrancado (GIM-21).
     *
     * Terminal (`FINISHED`/`ABANDONED`): cierra la fila de `session` y guarda el
     * cursor final; así la próxima apertura no rehidrata una sesión terminada.
     */
    private suspend fun persistGuided(state: GuidedSession, now: Instant) {
        if (state.sessionId.isBlank()) return
        when (state.status) {
            CoreStatus.NOT_STARTED -> return

            CoreStatus.RUNNING, CoreStatus.RESTING -> database.withTransaction {
                ensureSessionRow(state)
                persistNewSets(state)
                writeCursor(state, now)
            }

            CoreStatus.FINISHED, CoreStatus.ABANDONED -> database.withTransaction {
                ensureSessionRow(state)
                persistNewSets(state)
                writeCursor(state, now)
                sessionDao.close(
                    state.sessionId,
                    now.toEpochMilli(),
                    if (state.status == CoreStatus.FINISHED) RowStatus.FINISHED else RowStatus.ABANDONED,
                    null,
                )
            }
        }
    }

    /**
     * La fila de `session` puede no existir todavía: el llamador puede arrancar la
     * máquina (`StartSession`) sin haber llamado a [start]. Se crea por el camino
     * normal (`startSession`), así la regla de "una sola `ACTIVE`" sigue siendo la
     * del DAO y no una segunda copia.
     */
    private suspend fun ensureSessionRow(state: GuidedSession) {
        if (sessionDao.getById(state.sessionId) != null) return
        val startedAt = state.startedAt ?: return
        sessionDao.startSession(
            SessionEntity(
                id = state.sessionId,
                routineId = routineDao.getDay(state.day.id)?.routineId,
                routineDayId = state.day.id,
                startedAt = startedAt.toEpochMilli(),
                endedAt = null,
                status = RowStatus.ACTIVE,
                bodyWeightKg = null,
            ),
        )
    }

    /** Series del estado que todavía no están en la base (el id de la serie es determinístico). */
    private suspend fun persistNewSets(state: GuidedSession) {
        val stored = sessionSetDao.idsOfSession(state.sessionId).toHashSet()
        val perItem = mutableMapOf<String, Int>()
        state.sets.forEach { logged ->
            val setIndex = perItem.getOrDefault(logged.itemId, 0)
            perItem[logged.itemId] = setIndex + 1
            val set = logged.set
            val id = set.id.ifBlank { "${state.sessionId}/${logged.itemId}/$setIndex" }
            if (id in stored) return@forEach
            sessionSetDao.upsert(
                SessionSetEntity(
                    id = id,
                    sessionId = state.sessionId,
                    dayItemId = logged.itemId.ifBlank { null },
                    exerciseId = set.exerciseId.ifBlank { null },
                    roundIndex = logged.roundIndex,
                    setIndex = setIndex,
                    weightKg = set.weightKg,
                    reps = set.reps,
                    rpe = set.rpe,
                    isDropSet = set.isDropSet,
                    isWarmup = set.isWarmup,
                    completedAt = Instants.toEpochMillis(set.completedAt, zone),
                ),
            )
        }
    }

    /** El cursor de §2/§4: índices, deadline absoluto (epoch millis) y cuándo se guardó. */
    private suspend fun writeCursor(state: GuidedSession, now: Instant) {
        sessionStateDao.upsert(
            SessionStateEntity(
                sessionId = state.sessionId,
                blockIndex = state.blockIndex,
                roundIndex = state.roundIndex,
                itemIndex = state.itemIndex,
                restEndsAt = state.restEndsAt?.toEpochMilli(),
                restSeconds = state.restSeconds,
                updatedAt = now.toEpochMilli(),
            ),
        )
    }

    /**
     * Arma [GuidedSession] desde la base. `session_state` sólo guarda el cursor, así
     * que el plan sale de `routine_day`/`day_item` y las series de `session_set`.
     *
     * `status`: `RESTING` si hay deadline guardado, `RUNNING` si no. No se guarda un
     * `status` aparte a propósito: el deadline es el único dato que decide si el
     * descanso sigue vivo (una cuenta regresiva persistida quedaría vieja).
     *
     * @return `null` si el día ya no existe o la sesión no tiene `routine_day_id`.
     */
    private suspend fun reconstruct(
        session: SessionEntity,
        state: SessionStateEntity?,
        sets: List<SessionSetEntity>,
    ): GuidedSession? {
        val dayId = session.routineDayId ?: return null
        val day = routines.day(dayId) ?: return null
        // Bloque de cada ítem, con la misma regla de superseries que `dayStats` (`:core`).
        val blockOfItem = groups(day.items)
            .flatMapIndexed { blockIndex, block -> block.map { item -> item.id to blockIndex } }
            .toMap()
        return GuidedSession(
            day = day,
            sessionId = session.id,
            status = if (state?.restEndsAt != null) CoreStatus.RESTING else CoreStatus.RUNNING,
            startedAt = Instant.ofEpochMilli(session.startedAt),
            blockIndex = state?.blockIndex ?: 0,
            roundIndex = state?.roundIndex ?: 0,
            itemIndex = state?.itemIndex ?: 0,
            restEndsAt = state?.restEndsAt?.let { Instant.ofEpochMilli(it) },
            restSeconds = state?.restSeconds,
            sets = sets.map { row ->
                // Un ítem borrado deja `day_item_id` NULL: la serie se conserva
                // (volumen/PR del histórico) pero no participa del cursor.
                LoggedSet(
                    itemId = row.dayItemId.orEmpty(),
                    blockIndex = row.dayItemId?.let { blockOfItem[it] } ?: 0,
                    roundIndex = row.roundIndex,
                    set = row.toDomain(),
                )
            },
        )
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

/**
 * Qué hay que mostrar al abrir la app (§4 "nada se pierde si la app estaba cerrada").
 *
 * Lo devuelve [SessionRepository.resumeActive] ya pasado por `Restore`: el
 * repositorio no decide si el descanso terminó, eso lo dice la máquina con sus
 * efectos.
 */
sealed interface ActiveSession {

    /** No hay sesión `ACTIVE`: la app abre en "sin sesión". */
    data object None : ActiveSession

    /**
     * Hay sesión abierta y el plan se pudo reconstruir.
     *
     * @param state cursor restaurado (bloque, ronda, ítem, deadline y series).
     * @param effects efectos de `Restore` en orden; el llamador los ejecuta.
     */
    data class Resumable(
        val state: GuidedSession,
        val effects: List<SessionEffect> = emptyList(),
    ) : ActiveSession {

        /**
         * Segundos que le quedan al descanso, **calculados desde el deadline** por la
         * máquina (`Restore` devuelve `StartRest(lo que falta)`), o `null` si no hay
         * descanso vivo.
         */
        val remainingSeconds: Int?
            get() = effects.filterIsInstance<SessionEffect.StartRest>().firstOrNull()?.seconds

        /** `true` si el deadline venció mientras el proceso estaba muerto ("descanso terminado"). */
        val restFinished: Boolean
            get() = SessionEffect.ShowRestDone in effects
    }

    /** Sesión `ACTIVE` sin día (rutina eliminada): no hay nada que guiar, sólo descartarla. */
    data class MissingPlan(val sessionId: String) : ActiveSession
}
