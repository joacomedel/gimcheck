package app.gimcheck.data.transfer

import androidx.room.withTransaction
import app.gimcheck.core.DayItem
import app.gimcheck.core.DomainSnapshot
import app.gimcheck.core.Exercise
import app.gimcheck.core.ImportResult
import app.gimcheck.core.Muscle
import app.gimcheck.core.Routine
import app.gimcheck.core.RoutinePackage
import app.gimcheck.core.RoutineDay
import app.gimcheck.core.Secondary
import app.gimcheck.core.exportRoutinePack
import app.gimcheck.core.importRoutineFile
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.RoutineRepository
import app.gimcheck.data.repository.SettingsRepository
import app.gimcheck.data.repository.toEntity
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime

/**
 * Export e import de **rutinas** sin tocar el historial.
 *
 * El export usa el pack de `:core` ([exportRoutinePack]) y el import acepta ese pack,
 * un plan de la web o un backup completo ([importRoutineFile]). La semántica es
 * deliberadamente **aditiva**:
 *
 * - las rutinas importadas siempre entran como planes nuevos (ids nuevos), así que
 *   importar dos veces no pisa nada: la segunda copia se llama "… (importada)";
 * - los músculos y ejercicios se reutilizan por id o por nombre normalizado; sólo se
 *   crean los que faltan, y nunca se modifican los existentes;
 * - el historial (`session`) no se toca. Las rutinas viejas siguen ahí.
 *
 * La escritura va en una transacción: si falla una rutina, no queda catálogo a medias.
 */
internal class RoutineTransfer(
    private val db: AppDatabase,
    private val routines: RoutineRepository,
    private val exercises: ExerciseRepository,
    private val settings: SettingsRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> LocalDateTime = { java.time.LocalDateTime.now().toKotlinLocalDateTime() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /** JSON con todas las rutinas (o sólo [routineIds]) y el catálogo que usan. */
    suspend fun export(routineIds: Set<String>? = null): String = withContext(io) {
        val all = routines.allRoutines()
        val selected = if (routineIds == null) all else all.filter { it.id in routineIds }
        val snapshot = DomainSnapshot(
            settings = settings.current().toCoreSettings(),
            muscles = exercises.muscles(),
            exercises = exercises.all(),
            routines = selected,
            activeRoutineId = settings.activeRoutineId.first(),
        )
        exportRoutinePack(
            snapshot = snapshot,
            exportedAt = now().toString(),
            routineIds = selected.map { it.id }.toSet(),
            pretty = true,
        )
    }

    /** Nombre sugerido para el selector de archivos. */
    fun suggestedName(): String {
        val date = now().date
        val month = date.monthNumber.toString().padStart(2, '0')
        val day = date.dayOfMonth.toString().padStart(2, '0')
        return "gimcheck-rutinas-${date.year}-$month-$day.json"
    }

    /**
     * Parsea y resuelve el archivo contra el catálogo actual **sin escribir**. Lo que
     * devuelve ya tiene ids locales y se puede confirmar con [import].
     */
    suspend fun preview(text: String): RoutineImportPreview = withContext(io) {
        when (val parsed = importRoutineFile(text)) {
            is ImportResult.Failure -> RoutineImportPreview.Invalid(parsed.error.message)
            is ImportResult.Success -> resolve(parsed.value)
        }
    }

    /** Escribe el preview en una transacción. */
    suspend fun import(preview: RoutineImportPreview.Ready): RoutineImportOutcome =
        withContext(io) {
            try {
                db.withTransaction {
                    preview.newMuscles.forEach { muscle ->
                        db.muscleDao().upsert(muscle.toEntity())
                    }
                    preview.newExercises.forEach { exercise ->
                        db.exerciseDao().upsertWithSecondaries(
                            exercise = exercise.toEntity(),
                            secondaries = exercise.secondaries.map { it.toEntity(exercise.id) },
                        )
                    }
                    var position = db.routineDao().maxPosition()
                    preview.routines.forEach { routine ->
                        position += 1
                        val stored = routine.copy(position = position)
                        db.routineDao().upsertWithDays(
                            routine = stored.toEntity(),
                            days = stored.days.mapIndexed { index, day -> day.toEntity(stored.id, index) },
                        )
                        stored.days.forEach { day ->
                            db.dayDao().replaceItems(
                                dayId = day.id,
                                items = day.items.mapIndexed { index, item -> item.toEntity(day.id, index) },
                            )
                        }
                    }
                }
                if (settings.activeRoutineId.first() == null) {
                    settings.setActiveRoutine(preview.routines.first().id)
                }
                RoutineImportOutcome.Imported(
                    routines = preview.routines.size,
                    days = preview.routines.sumOf { it.days.size },
                    exercisesCreated = preview.newExercises.size,
                    exercisesReused = preview.reusedExercises,
                    firstRoutineId = preview.routines.first().id,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: RuntimeException) {
                RoutineImportOutcome.Failed(error.message ?: "no se pudo escribir la importación")
            }
        }

    // --- resolución ---------------------------------------------------------------

    private suspend fun resolve(packageValue: RoutinePackage): RoutineImportPreview {
        val existingMuscles = exercises.muscles()
        val existingExercises = exercises.all()
        val existingRoutines = routines.allRoutines()

        var musclePosition = (existingMuscles.maxOfOrNull { it.position } ?: -1) + 1
        var exercisePosition = (existingExercises.maxOfOrNull { it.position } ?: -1) + 1
        val usedMuscleIds = existingMuscles.map { it.id }.toMutableSet()
        val usedExerciseIds = existingExercises.map { it.id }.toMutableSet()
        val newMuscles = mutableListOf<Muscle>()
        val newExercises = mutableListOf<Exercise>()
        var reusedExercises = 0

        val musclesByKey = LinkedHashMap<String, String>()
        var fallbackMuscleId: String? = null

        fun generalMuscle(): String {
            fallbackMuscleId?.let { return it }
            val existing = existingMuscles.firstOrNull { normalize(it.name) == normalize("General") }
            if (existing != null) {
                fallbackMuscleId = existing.id
                return existing.id
            }
            val id = newId()
            val muscle = Muscle(id = id, name = "General", position = musclePosition++)
            newMuscles += muscle
            usedMuscleIds += id
            fallbackMuscleId = id
            return id
        }

        packageValue.muscles.forEach { source ->
            val byId = source.id.takeIf { it.isNotBlank() }
                ?.let { id -> existingMuscles.firstOrNull { it.id == id } }
            val byName = existingMuscles.firstOrNull { normalize(it.name) == normalize(source.name) }
            val found = byId ?: byName
            if (found != null) {
                musclesByKey[source.id] = found.id
                return@forEach
            }
            val id = source.id.takeIf { it.isNotBlank() && it !in usedMuscleIds } ?: newId()
            val muscle = Muscle(
                id = id,
                name = source.name.trim().ifBlank { "General" },
                position = musclePosition++,
            )
            newMuscles += muscle
            usedMuscleIds += id
            musclesByKey[source.id] = id
        }

        val exercisesByKey = LinkedHashMap<String, String>()
        packageValue.exercises.forEach { source ->
            val byId = source.id.takeIf { it.isNotBlank() }
                ?.let { id -> existingExercises.firstOrNull { it.id == id } }
            val byName = existingExercises.firstOrNull { normalize(it.name) == normalize(source.name) }
            val found = byId ?: byName
            if (found != null) {
                exercisesByKey[source.id] = found.id
                reusedExercises += 1
                return@forEach
            }
            val primaryId = musclesByKey[source.primaryMuscleId] ?: generalMuscle()
            val secondaries = source.secondaries.mapNotNull { secondary ->
                val muscleId = musclesByKey[secondary.muscleId] ?: return@mapNotNull null
                if (muscleId == primaryId) return@mapNotNull null
                Secondary(muscleId, secondary.factor.coerceIn(0.0, 1.0))
            }
            val id = source.id.takeIf { it.isNotBlank() && it !in usedExerciseIds } ?: newId()
            val exercise = Exercise(
                id = id,
                name = source.name.trim(),
                primaryMuscleId = primaryId,
                secondaries = secondaries.distinctBy { it.muscleId },
                secondsPerSet = source.secondsPerSet.coerceAtLeast(1),
                restSeconds = source.restSeconds.coerceAtLeast(0),
                compound = source.compound,
                loadType = source.loadType,
                position = exercisePosition++,
            )
            newExercises += exercise
            usedExerciseIds += id
            exercisesByKey[source.id] = id
        }

        val allExercises = (existingExercises + newExercises).associateBy { it.id }
        val usedNames = existingRoutines.map { normalize(it.name) }.toMutableSet()
        val today = now().date.toString()

        fun uniqueName(source: String): String {
            val base = source.trim().ifBlank { "Rutina importada" }
            if (usedNames.add(normalize(base))) return base
            var copy = 2
            while (true) {
                val suffix = if (copy == 2) " (importada)" else " (importada $copy)"
                val candidate = "$base$suffix"
                if (usedNames.add(normalize(candidate))) return candidate
                copy += 1
            }
        }

        val imported = packageValue.routines.map { source ->
            val days = source.days.mapIndexed { dayIndex, day ->
                val id = newId()
                val items = day.items.mapIndexedNotNull { itemIndex, item ->
                    val exerciseId = exercisesByKey[item.exerciseId] ?: return@mapIndexedNotNull null
                    val exercise = allExercises[exerciseId] ?: return@mapIndexedNotNull null
                    DayItem(
                        id = newId(),
                        exercise = exercise,
                        sets = item.sets.coerceIn(1, 20),
                        dropSet = item.dropSet,
                        supersetNext = item.supersetNext && itemIndex < day.items.lastIndex,
                        restOverrideSeconds = item.restOverrideSeconds,
                        targetRepsMin = item.targetRepsMin,
                        targetRepsMax = item.targetRepsMax,
                    )
                }
                RoutineDay(
                    id = id,
                    name = day.name.trim().ifBlank { "Día ${dayIndex + 1}" },
                    items = items,
                )
            }
            Routine(
                id = newId(),
                name = uniqueName(source.name),
                notes = source.notes,
                days = days,
                activeDayId = null,
                createdAt = today,
            )
        }

        return RoutineImportPreview.Ready(
            routines = imported,
            newMuscles = newMuscles,
            newExercises = newExercises,
            reusedExercises = reusedExercises,
            sourceRoutines = packageValue.routines.size,
        )
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
}

/** Resultado de leer y resolver un archivo, antes de escribir. */
internal sealed interface RoutineImportPreview {

    data class Invalid(val message: String) : RoutineImportPreview

    data class Ready(
        val routines: List<Routine>,
        val newMuscles: List<Muscle>,
        val newExercises: List<Exercise>,
        val reusedExercises: Int,
        val sourceRoutines: Int,
    ) : RoutineImportPreview
}

/** Resultado de escribir la importación. */
internal sealed interface RoutineImportOutcome {

    data class Imported(
        val routines: Int,
        val days: Int,
        val exercisesCreated: Int,
        val exercisesReused: Int,
        val firstRoutineId: String,
    ) : RoutineImportOutcome

    data class Failed(val message: String) : RoutineImportOutcome
}
