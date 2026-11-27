package app.gimcheck.data.repository

import app.gimcheck.core.DayItem
import app.gimcheck.core.Routine
import app.gimcheck.core.RoutineDay
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.RoutineDayEntity
import app.gimcheck.data.db.RoutineEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

    /**
     * Todos los planes materializados, en una foto (export de backup, GIM-29).
     *
     * Reusa [observeRoutines] para no duplicar el armado días/ítems/ejercicios; el
     * `first()` es la foto del estado actual, no un Flow que la pantalla tenga que
     * observar.
     */
    suspend fun allRoutines(): List<Routine> = withContext(io) {
        observeRoutines().first()
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

    /**
     * Un plan con sus días e ítems, materializado. Es lo que necesita exportar el plan
     * (GIM-16): `exportPlan` de `:core` trabaja sobre el modelo completo, no sobre las
     * filas sueltas.
     */
    suspend fun routine(id: String): Routine? = withContext(io) {
        val row = routineDao.getById(id) ?: return@withContext null
        val secondariesByExercise = exerciseDao.allSecondaries().groupBy { it.exerciseId }
        val exercisesById = exerciseDao.all().associate { exercise ->
            exercise.id to exercise.toDomain(secondariesByExercise[exercise.id].orEmpty())
        }
        row.toDomain(
            routineDao.daysOf(id).map { day ->
                day.toDomain(
                    dayDao.items(day.id).mapNotNull { item ->
                        exercisesById[item.exerciseId]?.let { exercise -> item.toDomain(exercise) }
                    },
                )
            },
        )
    }

    /** Crea un plan vacío al final de la lista y devuelve su id. */
    suspend fun createRoutine(name: String): String = withContext(io) {
        val id = newId()
        routineDao.upsertWithDays(
            routine = RoutineEntity(id = id, name = name, position = routineDao.maxPosition() + 1),
            days = emptyList(),
        )
        id
    }

    suspend fun renameRoutine(id: String, name: String) = withContext(io) {
        routineDao.renameRoutine(id, name)
    }

    /**
     * Duplica un plan con **copia profunda** de días e ítems: ids nuevos en los tres
     * niveles, misma composición (ejercicios, series, drop set, superserie). El
     * original no se toca.
     *
     * @return el id del plan nuevo, o `null` si el original ya no existe.
     */
    suspend fun duplicateRoutine(routineId: String, name: String): String? = withContext(io) {
        val source = routineDao.getById(routineId) ?: return@withContext null
        val newRoutineId = newId()
        // Se copian primero las entidades: el árbol de dominio se resolvería con una
        // lectura por día y lo único que cambia es el id.
        val copiedDays = routineDao.daysOf(routineId).map { day ->
            val newDayId = newId()
            RoutineDayEntity(
                id = newDayId,
                routineId = newRoutineId,
                name = day.name,
                position = day.position,
            ) to dayDao.items(day.id).map { item -> item.copy(id = newId(), dayId = newDayId) }
        }
        routineDao.upsertWithDays(
            routine = RoutineEntity(
                id = newRoutineId,
                name = name,
                notes = source.notes,
                position = routineDao.maxPosition() + 1,
                createdAt = source.createdAt,
            ),
            days = copiedDays.map { (day, _) -> day },
        )
        copiedDays.forEach { (day, items) ->
            dayDao.replaceItems(day.id, items)
        }
        newRoutineId
    }

    /**
     * Borra el plan con sus días e ítems. Las sesiones históricas quedan intactas.
     *
     * **Nunca el último**: la web no deja quedarse sin plan y la app tampoco (§1.1). La
     * regla vive acá, no sólo en la pantalla, para que ningún llamador futuro la saltee.
     *
     * @return `false` si fue rechazado por ser el único plan, `true` si borró.
     */
    suspend fun deleteRoutine(id: String): Boolean = withContext(io) {
        if (routineDao.count() <= 1) return@withContext false
        routineDao.delete(id)
        true
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

    /**
     * Duplica un día al final de su plan, con ítems nuevos (mismos ejercicios y
     * marcas). El día copiado queda con `superset_next` del último ítem en false,
     * que es la invariante que aplica `replaceItems`.
     *
     * @return el id del día nuevo, o `null` si el original ya no existe.
     */
    suspend fun duplicateDay(dayId: String): String? = withContext(io) {
        val source = routineDao.getDay(dayId) ?: return@withContext null
        val copied = routineDao.appendDay(
            RoutineDayEntity(id = newId(), routineId = source.routineId, name = source.name),
        )
        dayDao.replaceItems(
            copied.id,
            dayDao.items(dayId).map { item -> item.copy(id = newId(), dayId = copied.id) },
        )
        copied.id
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

    /**
     * Reemplaza todos los ítems de un día en el orden dado: es el guardado del editor
     * del día (GIM-17), un solo camino para agregar, mover, quitar y editar series.
     *
     * La validación de dominio la hace el llamador (`validateDay` de `:core`) para poder
     * mostrar el error tipado **antes** de tocar la base. El DAO igual aplica las
     * invariantes de §2 (`sets ∈ 1..20` y `superset_next` limpio en el último ítem), así
     * que un borrador inconsistente no puede quedar persistido.
     */
    suspend fun saveDayItems(dayId: String, items: List<DayItem>) = withContext(io) {
        dayDao.replaceItems(dayId, items.mapIndexed { index, item -> item.toEntity(dayId, index) })
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
