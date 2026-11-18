package app.gimcheck.data.repository

import app.gimcheck.core.Exercise
import app.gimcheck.core.Muscle
import app.gimcheck.data.db.AppDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Catálogo de músculos y ejercicios.
 *
 * Lecturas por `Flow` (la UI se suscribe una vez y ve cada cambio), escrituras
 * `suspend` en [io]. Las invariantes de §2 no se repiten acá: viven en el DAO
 * (`upsertWithSecondaries` valida `factor ∈ [0,1]` y que el principal no venga como
 * secundario; `deleteExerciseCascade` limpia los `day_item`).
 */
class ExerciseRepository internal constructor(
    db: AppDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val muscleDao = db.muscleDao()
    private val exerciseDao = db.exerciseDao()

    fun observeMuscles(includeArchived: Boolean = false): Flow<List<Muscle>> =
        (if (includeArchived) muscleDao.observeAllIncludingArchived() else muscleDao.observeAll())
            .map { rows -> rows.map { it.toDomain() } }

    /**
     * El catálogo completo con sus músculos secundarios, en una sola emisión por
     * cambio: dos consultas combinadas y no una por ejercicio (N+1).
     */
    fun observeExercises(): Flow<List<Exercise>> =
        combine(exerciseDao.observeAll(), exerciseDao.observeAllSecondaries()) { exercises, secondaries ->
            val byExercise = secondaries.groupBy { it.exerciseId }
            exercises.map { row -> row.toDomain(byExercise[row.id].orEmpty()) }
        }

    suspend fun muscles(): List<Muscle> = withContext(io) { muscleDao.all().map { it.toDomain() } }

    suspend fun exercise(id: String): Exercise? = withContext(io) {
        val row = exerciseDao.getById(id) ?: return@withContext null
        row.toDomain(exerciseDao.secondariesOf(id))
    }

    suspend fun saveMuscle(muscle: Muscle) = withContext(io) {
        muscleDao.upsert(muscle.toEntity())
    }

    /**
     * Borra un músculo. Falla si algún ejercicio lo usa como principal
     * (`ON DELETE RESTRICT`): primero se reasignan, no se pierden ejercicios.
     */
    suspend fun deleteMuscle(id: String) = withContext(io) {
        muscleDao.delete(id)
    }

    suspend fun saveExercise(exercise: Exercise) = withContext(io) {
        exerciseDao.upsertWithSecondaries(
            exercise = exercise.toEntity(),
            secondaries = exercise.secondaries.map { it.toEntity(exercise.id) },
        )
    }

    /** Borra el ejercicio y lo saca de todos los días, en una transacción. */
    suspend fun deleteExercise(id: String) = withContext(io) {
        exerciseDao.deleteExerciseCascade(id)
    }
}
