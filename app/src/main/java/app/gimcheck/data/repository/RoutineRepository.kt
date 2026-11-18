package app.gimcheck.data.repository

import app.gimcheck.core.DayItem
import app.gimcheck.core.Routine
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.RoutineDayEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Planes (`routine`), sus días y sus ítems.
 *
 * **Lectura**: una sola `Flow` con el árbol completo (planes → días → ítems →
 * ejercicios). Se arma combinando cinco consultas en vez de una por día: como Room
 * avisa por tabla, cualquier escritura relevante vuelve a emitir el plan entero.
 *
 * **Escritura**: todas las operaciones son `suspend` y van a [io]. Las invariantes de
 * §2 las aplica el DAO (`sets ∈ 1..20`, `superset_next` nunca en el último ítem); el
 * repositorio no las duplica, sólo genera ids y posiciones cuando falta.
 */
class RoutineRepository internal constructor(
    db: AppDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    private val routineDao = db.routineDao()
    private val dayDao = db.dayDao()
    private val exerciseDao = db.exerciseDao()

    /** Todos los planes con días e ítems, listos para `dayStats` y `volume`. */
    fun observeRoutines(): Flow<List<Routine>> = combine(
        routineDao.observeAll(),
        routineDao.observeAllDays(),
        dayDao.observeAllItems(),
        exerciseDao.observeAll(),
        exerciseDao.observeAllSecondaries(),
    ) { routines, days, items, exercises, secondaries ->
        val secondariesByExercise = secondaries.groupBy { it.exerciseId }
        val exercisesById = exercises.associate { row ->
            row.id to row.toDomain(secondariesByExercise[row.id].orEmpty())
        }
        val itemsByDay = items.groupBy { it.dayId }
        val daysByRoutine = days.groupBy { it.routineId }

        routines.map { routine ->
            routine.toDomain(
                daysByRoutine[routine.id].orEmpty().map { day ->
                    day.toDomain(
                        itemsByDay[day.id].orEmpty().mapNotNull { item ->
                            // un ítem cuyo ejercicio ya no está se descarta: el borrado
                            // del ejercicio lo saca en la misma transacción (§2), así que
                            // esto sólo tapa una fila corrupta, no un estado normal.
                            exercisesById[item.exerciseId]?.let { exercise -> item.toDomain(exercise) }
                        },
                    )
                },
            )
        }
    }

    /** Ítems de un día, materializados (lectura puntual, no reactiva). */
    suspend fun items(dayId: String): List<DayItem> = withContext(io) {
        val secondariesByExercise = exerciseDao.all().associate { row ->
            row.id to row.toDomain(exerciseDao.secondariesOf(row.id))
        }
        dayDao.items(dayId).mapNotNull { item ->
            secondariesByExercise[item.exerciseId]?.let { exercise -> item.toDomain(exercise) }
        }
    }

    /**
     * Un día con sus ítems, o `null` si ya no existe (rutina borrada, §2). Es lo que
     * necesita la rehidratación de la sesión guiada (GIM-11): el cursor sólo significa
     * algo junto al plan que lo generó.
     */
    suspend fun day(dayId: String): RoutineDay? = withContext(io) {
        val row = routineDao.getDay(dayId) ?: return@withContext null
        row.toDomain(items(dayId))
    }

    /**
     * Crea o reemplaza un plan con sus días e ítems, en tres transacciones (plan+días,
     * y los ítems de cada día). Los ids que vienen del dominio se respetan: un plan
     * importado de la web conserva los suyos.
     */
    suspend fun saveRoutine(routine: Routine) = withContext(io) {
        routineDao.upsertWithDays(
            routine = routine.toEntity(),
            days = routine.days.mapIndexed { index, day -> day.toEntity(routine.id, index) },
        )
        routine.days.forEach { day ->
            dayDao.replaceItems(day.id, day.items.mapIndexed { index, item -> item.toEntity(day.id, index) })
        }
    }

    /** Borra el plan con sus días e ítems. Las sesiones históricas quedan intactas. */
    suspend fun deleteRoutine(id: String) = withContext(io) {
        routineDao.delete(id)
    }

    /** Agrega un día vacío al final del plan y devuelve su id. */
    suspend fun addDay(routineId: String, name: String): String = withContext(io) {
        val day = RoutineDayEntity(id = newId(), routineId = routineId, name = name)
        routineDao.appendDay(day).id
    }

    suspend fun renameDay(dayId: String, name: String) = withContext(io) {
        routineDao.renameDay(dayId, name)
    }

    suspend fun deleteDay(dayId: String) = withContext(io) {
        routineDao.deleteDay(dayId)
    }

    /** Agrega el ítem al final del día y devuelve el id guardado. */
    suspend fun addItem(dayId: String, item: DayItem): String = withContext(io) {
        val stored = dayDao.addItem(item.toEntity(dayId, position = 0))
        stored.id
    }

    /**
     * Reemplaza series, drop set, descanso y reps objetivo del ítem. La posición del
     * día no se toca: para eso está [moveItem].
     */
    suspend fun updateItem(dayId: String, item: DayItem) = withContext(io) {
        val current = dayDao.item(item.id)
        requireNotNull(current) { "no existe el ítem ${item.id}" }
        dayDao.updateItem(item.toEntity(dayId, current.position))
    }

    suspend fun removeItem(itemId: String) = withContext(io) {
        dayDao.removeItem(itemId)
    }

    /** Sube o baja el ítem una posición dentro del día. */
    suspend fun moveItem(itemId: String, offset: Int) = withContext(io) {
        dayDao.moveItem(itemId, offset)
    }

    /** Marca o desmarca "superserie con el siguiente" (el último ítem no puede). */
    suspend fun setSupersetNext(itemId: String, next: Boolean) = withContext(io) {
        dayDao.setSupersetNext(itemId, next)
    }
}
