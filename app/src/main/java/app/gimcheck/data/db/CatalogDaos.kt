package app.gimcheck.data.db

import androidx.annotation.VisibleForTesting
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * DAOs del catálogo. Contrato común: **lectura con `Flow`** (la UI se suscribe) y
 * **escritura `suspend`** (Room la corre en su executor, nunca en el hilo
 * principal). Ningún DAO expone una consulta bloqueante; ver
 * `docs/adr/0003-persistencia-room.md`.
 *
 * Las escrituras usan `@Upsert` y no `@Insert(onConflict = REPLACE)`: REPLACE
 * hace un DELETE + INSERT de la fila, y con `ON DELETE CASCADE` eso borraría en
 * silencio los hijos (un ejercicio re-guardado se llevaría sus `day_item`).
 *
 * **Visibilidad (GIM-10):** todos los DAO son `internal`. Kotlin no permite
 * `internal` en un miembro de interfaz, así que la restricción se aplica al tipo:
 * nada fuera del módulo puede escribir por el camino crudo. Dentro del módulo, los
 * mutadores que saltean una invariante de §2 están marcados
 * `@VisibleForTesting(otherwise = PACKAGE_PRIVATE)` y sólo los llama el propio DAO;
 * el único camino de escritura de la app son los repositorios (`data/repository`).
 * Eso lo verifica `DaoWritePathTest`.
 */

/** Catálogo de músculos (§2 `muscle`). */
@Dao
internal interface MuscleDao {

    @Query("SELECT * FROM muscle WHERE archived = 0 ORDER BY position, name")
    fun observeAll(): Flow<List<MuscleEntity>>

    @Query("SELECT * FROM muscle ORDER BY position, name")
    fun observeAllIncludingArchived(): Flow<List<MuscleEntity>>

    @Query("SELECT * FROM muscle WHERE archived = 0 ORDER BY position, name")
    suspend fun all(): List<MuscleEntity>

    @Query("SELECT * FROM muscle WHERE id = :id")
    suspend fun getById(id: String): MuscleEntity?

    @Query("SELECT COUNT(*) FROM muscle WHERE archived = 0")
    suspend fun countActive(): Int

    @Upsert
    suspend fun upsert(muscle: MuscleEntity)

    /**
     * Borra el músculo. Con `ON DELETE RESTRICT` en `exercise.primary_muscle_id`
     * esto falla si todavía hay ejercicios usándolo: primero se reasignan.
     */
    @Query("DELETE FROM muscle WHERE id = :id")
    suspend fun delete(id: String)
}

/** Catálogo de ejercicios (§2 `exercise` + `exercise_secondary`). */
@Dao
internal interface ExerciseDao {

    @Query("SELECT * FROM exercise WHERE archived = 0 ORDER BY position, name")
    fun observeAll(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercise WHERE archived = 0 ORDER BY position, name")
    suspend fun all(): List<ExerciseEntity>

    @Query("SELECT * FROM exercise WHERE id = :id")
    suspend fun getById(id: String): ExerciseEntity?

    @Query("SELECT * FROM exercise_secondary WHERE exercise_id = :exerciseId ORDER BY muscle_id")
    suspend fun secondariesOf(exerciseId: String): List<ExerciseSecondaryEntity>

    @Query("SELECT * FROM exercise_secondary WHERE exercise_id = :exerciseId ORDER BY muscle_id")
    fun observeSecondariesOf(exerciseId: String): Flow<List<ExerciseSecondaryEntity>>

    /** Todos los secundarios de golpe: el repositorio arma el catálogo de una vez. */
    @Query("SELECT * FROM exercise_secondary ORDER BY exercise_id, muscle_id")
    fun observeAllSecondaries(): Flow<List<ExerciseSecondaryEntity>>

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    @Upsert
    suspend fun upsertExercise(exercise: ExerciseEntity)

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    @Upsert
    suspend fun upsertSecondaries(secondaries: List<ExerciseSecondaryEntity>)

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    @Query("DELETE FROM exercise_secondary WHERE exercise_id = :exerciseId")
    suspend fun deleteSecondariesOf(exerciseId: String)

    /**
     * Guarda el ejercicio y reemplaza sus secundarios en una sola transacción.
     *
     * Valida la regla de §2 `factor ∈ [0,1]` y que el músculo principal no
     * aparezca también como secundario (el principal ya suma 1).
     *
     * @throws IllegalArgumentException si un factor sale de rango o el principal
     *   viene como secundario.
     */
    @Transaction
    suspend fun upsertWithSecondaries(
        exercise: ExerciseEntity,
        secondaries: List<ExerciseSecondaryEntity>,
    ) {
        require(secondaries.all { it.exerciseId == exercise.id }) {
            "los secundarios deben apuntar al ejercicio ${exercise.id}"
        }
        require(secondaries.all { it.factor in 0.0..1.0 }) {
            "exercise_secondary.factor fuera de [0,1]: " +
                secondaries.filter { it.factor !in 0.0..1.0 }
        }
        require(secondaries.none { it.muscleId == exercise.primaryMuscleId }) {
            "el músculo principal no puede ir como secundario"
        }
        upsertExercise(exercise)
        deleteSecondariesOf(exercise.id)
        if (secondaries.isNotEmpty()) upsertSecondaries(secondaries)
    }

    // --- borrado con impacto en los días (§2: "borrar un ejercicio lo quita de
    // todos los day_item y recalcula superset_next del último ítem") ---------

    @Query("SELECT DISTINCT day_id FROM day_item WHERE exercise_id = :exerciseId")
    suspend fun dayIdsWithExercise(exerciseId: String): List<String>

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    @Query("DELETE FROM day_item WHERE exercise_id = :exerciseId")
    suspend fun deleteDayItemsOfExercise(exerciseId: String)

    /**
     * Misma sentencia que `DayDao.clearLastItemSupersetNext`, repetida acá porque
     * un DAO de Room no puede llamar a otro dentro de su propia transacción.
     * Si cambia la definición de "último ítem", cambian las dos.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    @Query(
        "UPDATE day_item SET superset_next = 0 WHERE day_id = :dayId AND position = " +
            "(SELECT MAX(position) FROM day_item WHERE day_id = :dayId)",
    )
    suspend fun clearLastItemSupersetNext(dayId: String)

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    @Query("DELETE FROM exercise WHERE id = :exerciseId")
    suspend fun deleteExerciseRow(exerciseId: String)

    /**
     * Borra un ejercicio y lo saca de todos los días, recalculando
     * `superset_next` del que quede último en cada día afectado. Una sola
     * transacción: o queda todo consistente o no queda nada.
     */
    @Transaction
    suspend fun deleteExerciseCascade(exerciseId: String) {
        val dayIds = dayIdsWithExercise(exerciseId)
        deleteDayItemsOfExercise(exerciseId)
        dayIds.forEach { dayId -> clearLastItemSupersetNext(dayId) }
        deleteExerciseRow(exerciseId)
    }
}
