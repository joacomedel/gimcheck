package app.gimcheck.data.db

import androidx.annotation.VisibleForTesting
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Planes y días (§2 `routine`, `routine_day`). */
@Dao
internal interface RoutineDao {

    @Query("SELECT * FROM routine WHERE archived = 0 ORDER BY position, name")
    fun observeAll(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routine WHERE id = :id")
    suspend fun getById(id: String): RoutineEntity?

    @Query("SELECT * FROM routine_day WHERE routine_id = :routineId ORDER BY position, name")
    fun observeDays(routineId: String): Flow<List<RoutineDayEntity>>

    /** Todos los días de todos los planes: el repositorio arma el árbol con un solo Flow. */
    @Query("SELECT * FROM routine_day ORDER BY routine_id, position, name")
    fun observeAllDays(): Flow<List<RoutineDayEntity>>

    @Query("SELECT * FROM routine_day WHERE routine_id = :routineId ORDER BY position, name")
    suspend fun daysOf(routineId: String): List<RoutineDayEntity>

    /** Un día por id: la sesión guarda `routine_day_id` y necesita el plan al rehidratar (GIM-11). */
    @Query("SELECT * FROM routine_day WHERE id = :id")
    suspend fun getDay(id: String): RoutineDayEntity?

    @Query("SELECT COALESCE(MAX(position), -1) FROM routine_day WHERE routine_id = :routineId")
    suspend fun maxDayPosition(routineId: String): Int

    /** Posición para un plan nuevo: el final de la lista (GIM-16). */
    @Query("SELECT COALESCE(MAX(position), -1) FROM routine")
    suspend fun maxPosition(): Int

    @Query("UPDATE routine SET name = :name WHERE id = :id")
    suspend fun renameRoutine(id: String, name: String)

    @Query("UPDATE routine_day SET name = :name WHERE id = :id")
    suspend fun renameDay(id: String, name: String)

    @Query("SELECT COUNT(*) FROM routine")
    suspend fun count(): Int

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    @Upsert
    suspend fun upsertRoutine(routine: RoutineEntity)

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    @Upsert
    suspend fun upsertDay(day: RoutineDayEntity)

    @Transaction
    suspend fun upsertWithDays(routine: RoutineEntity, days: List<RoutineDayEntity>) {
        require(days.all { it.routineId == routine.id }) {
            "todos los días deben pertenecer a la rutina ${routine.id}"
        }
        upsertRoutine(routine)
        days.forEach { upsertDay(it) }
    }

    /**
     * Borra un día con sus ítems (CASCADE). Las sesiones no lo referencian por FK:
     * `session.routine_day_id` queda huérfano y se muestra como "rutina eliminada".
     */
    @Query("DELETE FROM routine_day WHERE id = :id")
    suspend fun deleteDay(id: String)

    /** Agrega un día al final del plan. La posición la asigna la transacción. */
    @Transaction
    suspend fun appendDay(day: RoutineDayEntity): RoutineDayEntity {
        val stored = day.copy(position = maxDayPosition(day.routineId) + 1)
        upsertDay(stored)
        return stored
    }

    /**
     * Borra el plan con sus días e ítems (CASCADE). **No toca `session`**: las
     * sesiones históricas quedan con `routine_id` huérfano y se muestran como
     * "rutina eliminada" (§2).
     */
    @Query("DELETE FROM routine WHERE id = :id")
    suspend fun delete(id: String)
}

/**
 * Ítems de un día (§2 `day_item`).
 *
 * Reglas de integridad que se aplican en cada escritura:
 *  - `sets ∈ 1..20` (§2): [MIN_SETS], [MAX_SETS].
 *  - `superset_next` nunca queda true en el último ítem: el alta lo fuerza a
 *    false y todo borrado limpia el que quedó al final.
 *
 * Todas las escrituras son `suspend` y transaccionales; las lecturas, `Flow`
 * (o `suspend` para uso puntual, nunca bloqueante).
 */
@Dao
internal interface DayDao {

    @Query("SELECT * FROM day_item WHERE day_id = :dayId ORDER BY position, id")
    fun observeItems(dayId: String): Flow<List<DayItemEntity>>

    /** Todos los ítems de todos los días: el árbol del plan se arma con un solo Flow. */
    @Query("SELECT * FROM day_item ORDER BY day_id, position, id")
    fun observeAllItems(): Flow<List<DayItemEntity>>

    @Query("SELECT * FROM day_item WHERE day_id = :dayId ORDER BY position, id")
    suspend fun items(dayId: String): List<DayItemEntity>

    @Query("SELECT * FROM day_item WHERE id = :itemId")
    suspend fun item(itemId: String): DayItemEntity?

    @Query("SELECT day_id FROM day_item WHERE id = :itemId")
    suspend fun dayIdOf(itemId: String): String?

    @Query("SELECT COALESCE(MAX(position), -1) FROM day_item WHERE day_id = :dayId")
    suspend fun maxPosition(dayId: String): Int

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    @Upsert
    suspend fun upsertItem(item: DayItemEntity)

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    @Query("UPDATE day_item SET position = :position WHERE id = :itemId")
    suspend fun setItemPosition(itemId: String, position: Int)

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    @Query("UPDATE day_item SET superset_next = :next WHERE id = :itemId")
    suspend fun setItemSupersetNext(itemId: String, next: Boolean)

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    @Query("DELETE FROM day_item WHERE id = :itemId")
    suspend fun deleteItemRow(itemId: String)

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    @Query("DELETE FROM day_item WHERE day_id = :dayId")
    suspend fun deleteItemsOf(dayId: String)

    /**
     * `position` es sólo el orden de ejecución (no se renumera): el último ítem
     * es el de `MAX(position)`. Misma sentencia que en `ExerciseDao`.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    @Query(
        "UPDATE day_item SET superset_next = 0 WHERE day_id = :dayId AND position = " +
            "(SELECT MAX(position) FROM day_item WHERE day_id = :dayId)",
    )
    suspend fun clearLastItemSupersetNext(dayId: String)

    /**
     * Agrega un ítem al final del día.
     *
     * @throws IllegalArgumentException si `sets` no está en 1..20.
     */
    @Transaction
    suspend fun addItem(item: DayItemEntity): DayItemEntity {
        require(item.sets in MIN_SETS..MAX_SETS) {
            "day_item.sets fuera de $MIN_SETS..$MAX_SETS: ${item.sets}"
        }
        val stored = item.copy(
            position = maxPosition(item.dayId) + 1,
            // el recién agregado siempre es el último: no puede encadenar superserie
            supersetNext = false,
        )
        upsertItem(stored)
        clearLastItemSupersetNext(item.dayId)
        return stored
    }

    /**
     * Reemplaza un ítem existente (series, drop set, descanso, repeticiones
     * objetivo). La posición no se toca: para reordenar está [moveItem].
     *
     * @throws IllegalArgumentException si `sets` no está en 1..20.
     */
    @Transaction
    suspend fun updateItem(item: DayItemEntity) {
        require(item.sets in MIN_SETS..MAX_SETS) {
            "day_item.sets fuera de $MIN_SETS..$MAX_SETS: ${item.sets}"
        }
        upsertItem(item)
        clearLastItemSupersetNext(item.dayId)
    }

    /** Quita el ítem y recalcula el `superset_next` del que quedó último. */
    @Transaction
    suspend fun removeItem(itemId: String) {
        val dayId = dayIdOf(itemId) ?: return
        deleteItemRow(itemId)
        clearLastItemSupersetNext(dayId)
    }

    /**
     * Sube o baja el ítem una posición (`offset` = -1 / +1) y renumera el día
     * 0..n-1 en el orden resultante.
     */
    @Transaction
    suspend fun moveItem(itemId: String, offset: Int) {
        val dayId = dayIdOf(itemId) ?: return
        val ordered = items(dayId).map { it.id }
        val from = ordered.indexOf(itemId)
        if (from < 0) return
        val to = (from + offset).coerceIn(0, ordered.lastIndex)
        if (to == from) return
        val moved = ordered.toMutableList().apply {
            removeAt(from)
            add(to, itemId)
        }
        moved.forEachIndexed { index, id -> setItemPosition(id, index) }
        clearLastItemSupersetNext(dayId)
    }

    /**
     * Marca o desmarca "superserie con el siguiente".
     *
     * @throws IllegalArgumentException si se intenta marcar el último ítem del
     *   día: encadenaría con nada (§2).
     */
    @Transaction
    suspend fun setSupersetNext(itemId: String, next: Boolean) {
        val dayId = dayIdOf(itemId) ?: return
        val ordered = items(dayId)
        val index = ordered.indexOfFirst { it.id == itemId }
        if (index < 0) return
        if (next && index == ordered.lastIndex) {
            throw IllegalArgumentException(
                "day_item.superset_next no puede ser true en el último ítem del día",
            )
        }
        setItemSupersetNext(itemId, next)
    }

    /**
     * Reemplaza todos los ítems de un día por [items], en ese orden.
     *
     * Es el camino de escritura masivo (seed e import): mantiene las mismas
     * invariantes que las operaciones de a uno, porque `internal` en el DAO no
     * alcanza para protegerlo si alguien lo llama desde el módulo.
     *
     * @throws IllegalArgumentException si un `sets` sale de 1..20 o si un ítem
     *   apunta a otro día.
     */
    @Transaction
    suspend fun replaceItems(dayId: String, items: List<DayItemEntity>) {
        require(items.all { it.dayId == dayId }) {
            "todos los ítems deben pertenecer al día $dayId"
        }
        require(items.all { it.sets in MIN_SETS..MAX_SETS }) {
            "day_item.sets fuera de $MIN_SETS..$MAX_SETS: " +
                items.filter { it.sets !in MIN_SETS..MAX_SETS }.map { it.id }
        }
        deleteItemsOf(dayId)
        items.forEachIndexed { index, item ->
            val last = index == items.lastIndex
            upsertItem(
                item.copy(
                    position = index,
                    // el último ítem del día no puede encadenar superserie (§2)
                    supersetNext = item.supersetNext && !last,
                ),
            )
        }
    }

    companion object {
        /** Series permitidas por ítem (§2 y editor de la web). */
        const val MIN_SETS: Int = 1
        const val MAX_SETS: Int = 20
    }
}
